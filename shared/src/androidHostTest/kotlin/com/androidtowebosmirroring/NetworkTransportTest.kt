package com.androidtowebosmirroring

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class NetworkTransportTest {
    private fun header(input: InputStream): String {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0); text.append(b.toChar()) }
        return text.toString()
    }
    private fun mockResponse(body: String, delayMs: Long = 0, action: (String) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                server.accept().use { socket ->
                    header(socket.getInputStream())
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val bytes = body.toByteArray()
                    runCatching { socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes) }
                }
            }
            action("http://127.0.0.1:${server.localPort}/device.xml")
            worker.join(5000)
            assertFalse(worker.isAlive)
        }
    }
    @Test fun descriptionResolvesRelativeControlUrlAndPreservesServiceVersion() {
        mockResponse("""<root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>Living &amp; dining</friendlyName><UDN>uuid:tv</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType><controlURL>/control</controlURL></service></serviceList></device></root>""") { url ->
            val tv = assertNotNull(Dlna.describe(url))
            assertEquals("Living & dining", tv.name)
            assertEquals("uuid:tv", tv.id)
            assertEquals("http://127.0.0.1:${URL(url).port}/control", tv.controlUrl)
            assertTrue(tv.serviceType.endsWith(":2"))
        }
    }
    @Test fun descriptionRejectsExternalEntities() {
        mockResponse("""<!DOCTYPE root [<!ENTITY secret SYSTEM "file:///etc/passwd">]><root>&secret;</root>""") { url ->
            assertFailsWith<IllegalArgumentException> { Dlna.describe(url) }
        }
    }
    @Test fun playAcknowledgementCanTakeLongerThanFourSeconds() {
        mockResponse("", delayMs = 4500) { url ->
            Dlna.action(Receiver("tv", "TV", "DLNA", url, "urn:schemas-upnp-org:service:AVTransport:1"), "Play", "<Speed>1</Speed>")
        }
    }
    @Test fun commandTimeoutRetainsStageAndCause() {
        mockResponse("", delayMs = 250) { url ->
            val error = assertFailsWith<TvCommandTimeout> {
                Dlna.action(Receiver("tv", "TV", "DLNA", url, "urn:schemas-upnp-org:service:AVTransport:1"), "SetAVTransportURI", "", timeoutMs = 100)
            }
            assertEquals("SetAVTransportURI", error.command)
            assertTrue(error.cause is SocketTimeoutException)
        }
    }
    @Test fun onlyLatePlayAcknowledgementsWithRecentMediaProgressAreTolerated() {
        val play = TvCommandTimeout("Play", SocketTimeoutException())
        assertTrue(Dlna.canContinueAfterTimeout(play, true, 1_000_000_000L))
        assertFalse(Dlna.canContinueAfterTimeout(play, false, 0))
        assertFalse(Dlna.canContinueAfterTimeout(play, true, 5_000_000_000L))
        assertFalse(Dlna.canContinueAfterTimeout(TvCommandTimeout("SetAVTransportURI", SocketTimeoutException()), true, 0))
        assertFalse(Dlna.canContinueAfterTimeout(java.io.IOException("TV rejected Play"), true, 0))
    }
    @Test fun soapSendsSetUriBeforePlayWithEscapedMetadata() {
        ServerSocket(0).use { server ->
            val requests = java.util.concurrent.LinkedBlockingQueue<String>()
            val worker = thread(isDaemon = true) {
                repeat(2) {
                    server.accept().use { socket ->
                        val input = socket.getInputStream()
                        val head = header(input)
                        val length = head.lineSequence().first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                        val bytes = ByteArray(length)
                        java.io.DataInputStream(input).readFully(bytes)
                        requests.add(head + String(bytes))
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                }
            }
            Dlna.play(Receiver("tv", "TV", "DLNA", "http://127.0.0.1:${server.localPort}/control", "urn:schemas-upnp-org:service:AVTransport:1"), "http://127.0.0.1/live?a=1&b=2")
            val first = assertNotNull(requests.poll(5, TimeUnit.SECONDS))
            val second = assertNotNull(requests.poll(5, TimeUnit.SECONDS))
            assertTrue(first.contains("#SetAVTransportURI"))
            assertTrue(first.contains("<CurrentURI>http://127.0.0.1/live?a=1&amp;b=2</CurrentURI>"))
            assertTrue(first.contains("&lt;DIDL-Lite"))
            assertTrue(second.contains("#Play"))
            worker.join(5000)
        }
    }
    @Test fun httpRequiresSessionPathAndWaitsForKeyframe() {
        val reading = CountDownLatch(1)
        LiveServer(Receiver("tv", "TV", "DLNA", "http://127.0.0.1:1400/control")) { reading.countDown() }.use { server ->
            server.start()
            val url = URL(server.url)
            Socket(url.host, url.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("GET /wrong HTTP/1.0\r\n\r\n".toByteArray())
                assertTrue(header(socket.getInputStream()).startsWith("HTTP/1.1 404"))
            }
            Socket(url.host, url.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("GET ${url.path} HTTP/1.0\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                assertTrue(header(input).contains("Content-Type: video/mp2t"))
                assertTrue(reading.await(2, TimeUnit.SECONDS))
                assertFalse(server.hasWrittenMedia)
                val packet = ByteArray(188) { 0x47 }
                server.publish(packet)
                socket.soTimeout = 100
                assertFailsWith<SocketTimeoutException> { input.read() }
                server.keyframe(); server.publish(packet)
                socket.soTimeout = 2000
                val received = ByteArray(188); java.io.DataInputStream(input).readFully(received)
                assertContentEquals(packet, received)
                // Data delivery alone does not imply the TV has decoded/displayed it.
                val deadline = System.nanoTime() + 1_000_000_000L
                while (!server.hasWrittenMedia && System.nanoTime() < deadline) Thread.yield()
                assertTrue(server.hasWrittenMedia)
                assertTrue(System.nanoTime() - server.lastReadNanos < 1_000_000_000L)
                server.close()
                assertEquals(-1, input.read())
            }
        }
    }
}
