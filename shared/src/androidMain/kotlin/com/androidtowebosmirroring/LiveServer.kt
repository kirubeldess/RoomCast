package com.androidtowebosmirroring

import java.net.*
import java.util.UUID
import java.util.concurrent.*

/** Session-scoped HTTP stream. Slow clients are disconnected instead of buffering private video. */
class LiveServer(tv: Receiver, private val onRead: () -> Unit) : AutoCloseable {
    private val host = URL(tv.controlUrl).host
    private val tvAddress = InetAddress.getByName(host)
    private val local = DatagramSocket().use { it.connect(tvAddress, URL(tv.controlUrl).port.takeIf { port -> port > 0 } ?: 80); it.localAddress }
    private val server = ServerSocket(0, 4, local)
    private val path = "/${UUID.randomUUID()}/screen.ts"
    val url = "http://${local.hostAddress}:${server.localPort}$path"
    private val executor = Executors.newFixedThreadPool(3)
    private data class Client(val socket: Socket, val queue: StreamQueue = StreamQueue(), var ready: Boolean = false)
    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var closed = false
    @Volatile var lastReadNanos: Long = System.nanoTime()
        private set
    @Volatile var hasWrittenMedia: Boolean = false
        private set
    @Volatile var slowDisconnects = 0
        private set
    val queuedBytes: Int get() = clients.maxOfOrNull { it.queue.bytes } ?: 0
    val connectionCount: Int get() = clients.size
    fun start() {
        executor.execute {
            while (!closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (socket.inetAddress != tvAddress || clients.size >= 2) { socket.close(); continue }
                val client = Client(socket); clients += client
                executor.execute { serve(client) }
            }
        }
    }
    @Synchronized fun keyframe() { clients.forEach { it.ready = true } }
    @Synchronized fun publish(packet: ByteArray) {
        clients.forEach { if (it.ready && !it.queue.offer(packet)) {
            slowDisconnects++; clients.remove(it); runCatching { it.socket.close() }
        } }
    }
    private fun serve(client: Client) {
        try {
            client.socket.use { socket ->
                socket.soTimeout = 5000
                socket.tcpNoDelay = true
                val input = socket.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n") && header.length < 8192) { val b = input.read(); if (b < 0) return; header.append(b.toChar()) }
                val request = header.toString().substringBefore("\r\n").split(' ')
                val out = socket.getOutputStream().buffered(32 * 1024)
                if (request.size != 3 || request[1] != path || request[0] !in listOf("GET", "HEAD")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
                }
                // Close-delimited HTTP/1.0 avoids chunk markers in MPEG-TS on older native players.
                out.write("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-store\r\nConnection: close\r\ntransferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=1\r\n\r\n".toByteArray()); out.flush()
                if (request[0] == "HEAD") return
                onRead()
                var pendingBytes = 0
                var lastFlush = System.nanoTime()
                while (!closed && !socket.isClosed) {
                    val packet = client.queue.poll(10, TimeUnit.MILLISECONDS)
                    if (packet != null) { out.write(packet); pendingBytes += packet.size }
                    // Queue emptiness is not a progress signal: a busy encoder can keep it nonempty
                    // while the socket is successfully sending. Flush and record bounded batches.
                    if (pendingBytes > 0 && (pendingBytes >= 188 * 64 || System.nanoTime() - lastFlush >= 20_000_000L)) {
                        out.flush()
                        lastFlush = System.nanoTime(); lastReadNanos = lastFlush
                        hasWrittenMedia = true; pendingBytes = 0
                    }
                }
            }
        } catch (_: Exception) { /* TV disconnected or session stopped. */ }
        finally { clients.remove(client) }
    }
    override fun close() { closed = true; server.close(); clients.forEach { runCatching { it.socket.close() } }; clients.clear(); executor.shutdownNow() }
}
