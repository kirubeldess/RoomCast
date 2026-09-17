package com.androidtowebosmirroring

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.*
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class TvCommandTimeout(val command: String, cause: SocketTimeoutException) :
    java.io.IOException("TV did not acknowledge $command in time", cause)

object Dlna {
    internal fun canContinueAfterTimeout(error: Throwable?, hasWrittenMedia: Boolean, writeAgeNanos: Long): Boolean =
        error is TvCommandTimeout && error.command == "Play" && hasWrittenMedia && writeAgeNanos in 0 until 5_000_000_000L
    private fun connection(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 3000; readTimeout = 4000; instanceFollowRedirects = false
    }
    fun describe(location: String): Receiver? {
        val conn = connection(location)
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Android's Harmony parser does not implement every desktop JAXP feature.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 256 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size <= 256 * 1024) { "TV description is too large" }
            val xml = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            require('\u0000' !in xml && !xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true)) { "Unsafe TV description" }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities are disabled") }
            }
            val doc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
            fun Element.value(name: String) = getElementsByTagNameNS("*", name).item(0)?.textContent?.trim().orEmpty()
            val root = doc.documentElement
            val base = root.value("URLBase").ifEmpty { location }
            val services = root.getElementsByTagNameNS("*", "service")
            for (i in 0 until services.length) {
                val service = services.item(i) as Element
                val type = service.value("serviceType")
                if (type.startsWith("urn:schemas-upnp-org:service:AVTransport:")) {
                    val control = URL(URL(base), service.value("controlURL"))
                    require(control.protocol == "http" || control.protocol == "https")
                    return Receiver(root.value("UDN").ifEmpty { location }, root.value("friendlyName").ifEmpty { "TV" }, "DLNA", control.toString(), type)
                }
            }
            return null
        } finally { conn.disconnect() }
    }
    fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun action(tv: Receiver, name: String, arguments: String, timeoutMs: Int = 15000) {
        val conn = connection(tv.controlUrl)
        try {
            conn.readTimeout = timeoutMs
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"${tv.serviceType}#$name\"")
            val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$name xmlns:u=\"${escape(tv.serviceType)}\"><InstanceID>0</InstanceID>$arguments</u:$name></s:Body></s:Envelope>"
            conn.outputStream.use { it.write(body.toByteArray()) }
            check(conn.responseCode in 200..299) { "TV rejected $name (HTTP ${conn.responseCode}). Check TV media-sharing permissions and live-stream support." }
        } catch (e: SocketTimeoutException) {
            throw TvCommandTimeout(name, e)
        } finally { conn.disconnect() }
    }
    fun play(tv: Receiver, url: String) {
        check(!Thread.currentThread().isInterrupted) { "Session stopped" }
        val metadata = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>Roomcast live screen</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:video/mp2t:*\">${escape(url)}</res></item></DIDL-Lite>"
        action(tv, "SetAVTransportURI", "<CurrentURI>${escape(url)}</CurrentURI><CurrentURIMetaData>${escape(metadata)}</CurrentURIMetaData>")
        check(!Thread.currentThread().isInterrupted) { "Session stopped" }
        action(tv, "Play", "<Speed>1</Speed>")
    }
}

class Discovery(context: Context, private val found: (Receiver) -> Unit, private val done: (String) -> Unit) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    @Volatile private var closed = false
    @Volatile private var socket: DatagramSocket? = null
    fun start() {
        listOf("_airplay._tcp." to "AirPlay", "_googlecast._tcp." to "Google Cast").forEach { (type, label) ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, code: Int) {}
                override fun onStopDiscoveryFailed(t: String, code: Int) {}
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onServiceFound(info: NsdServiceInfo) { if (!closed) found(Receiver("$label:${info.serviceName}", info.serviceName, label)) }
            }
            listeners += listener
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
        }
        executor.execute {
            val lock = wifi.createMulticastLock("roomcast-discovery").apply { setReferenceCounted(false) }
            var result = "Search complete. Select a DLNA TV to begin. AirPlay and Cast are identification only."
            try {
                lock.acquire()
                val addresses = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                val locations = linkedSetOf<String>()
                // Send on each interface, including a tethering interface with no Android Network object.
                for (address in addresses) {
                    if (closed) break
                    MulticastSocket(InetSocketAddress(address, 0)).use { sock ->
                        sock.networkInterface = NetworkInterface.getByInetAddress(address)
                        socket = sock; sock.soTimeout = 400
                        val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n".toByteArray()
                        repeat(2) { sock.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900)) }
                        val deadline = System.nanoTime() + 3_000_000_000L
                        while (!closed && System.nanoTime() < deadline) {
                            val packet = DatagramPacket(ByteArray(8192), 8192)
                            try { sock.receive(packet) } catch (_: SocketTimeoutException) { continue }
                            val response = String(packet.data, 0, packet.length)
                            response.lineSequence().firstOrNull { it.startsWith("location:", true) }?.substringAfter(':')?.trim()?.let { locations += it }
                        }
                    }
                }
                for (location in locations.take(32)) {
                    if (closed) break
                    runCatching { Dlna.describe(location) }.getOrNull()?.let { if (!closed) found(it) }
                }
            } catch (e: Exception) { result = "Discovery failed: ${e.message}. Check Wi-Fi or hotspot connectivity." }
            finally { socket = null; if (lock.isHeld) lock.release(); if (!closed) done(result) }
        }
    }
    override fun close() {
        closed = true; socket?.close()
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }; listeners.clear(); executor.shutdownNow()
    }
}
