package com.kayloh20.kyliemail.data.sync

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Sits between the app and a test server. [goSilent] makes every connection open so far stop
 * passing data in either direction without closing, the way a connection behaves after a phone
 * changes networks or a router forgets it. Connections made afterwards work normally.
 */
class SilentProxy(private val targetPort: Int) : AutoCloseable {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    private class Link(val client: Socket, val upstream: Socket) {
        @Volatile var silent = false
    }

    private val links = CopyOnWriteArrayList<Link>()

    /** Delay added to each chunk of data each way, to act like a server far away. */
    @Volatile var latencyMs = 0L

    init {
        thread(isDaemon = true, name = "silent-proxy") {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                val upstream = Socket().apply { connect(InetSocketAddress("127.0.0.1", targetPort)) }
                val link = Link(client, upstream)
                links += link
                pump(link, client.getInputStream(), upstream.getOutputStream())
                pump(link, upstream.getInputStream(), client.getOutputStream())
            }
        }
    }

    private fun pump(link: Link, from: InputStream, to: OutputStream) = thread(isDaemon = true) {
        val buffer = ByteArray(16 * 1024)
        runCatching {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                // A silent link swallows data forever instead of passing it on or closing.
                if (link.silent) continue
                if (latencyMs > 0) Thread.sleep(latencyMs)
                to.write(buffer, 0, n)
                to.flush()
            }
        }
    }

    fun goSilent() = links.forEach { it.silent = true }

    override fun close() {
        runCatching { server.close() }
        links.forEach { runCatching { it.client.close() }; runCatching { it.upstream.close() } }
    }
}
