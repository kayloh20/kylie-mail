package com.kayloh20.kyliemail.relay

import jakarta.mail.Session
import org.eclipse.angus.mail.imap.IMAPStore
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.Properties
import java.util.WeakHashMap
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

data class ImapServer(val host: String, val port: Int, val tls: Boolean) {
    companion object {
        val ICLOUD = ImapServer("imap.mail.me.com", 993, tls = true)
    }
}

/** Opens IMAP connections and can cut them dead at once, rather than waiting on a quiet one. */
class Imap(private val server: ImapServer) {
    private val sockets = Collections.synchronizedMap(WeakHashMap<IMAPStore, MutableList<Socket>>())
    private val protocol = if (server.tls) "imaps" else "imap"

    fun connect(username: String, password: String, readTimeoutMs: Long): IMAPStore {
        val opened = Collections.synchronizedList(mutableListOf<Socket>())
        val store = Session.getInstance(properties(readTimeoutMs, opened)).getStore(protocol) as IMAPStore
        sockets[store] = opened
        try {
            store.connect(server.host, server.port, username, password)
        } catch (e: Exception) {
            kill(store)
            throw e
        }
        return store
    }

    fun kill(store: IMAPStore) {
        val list = sockets.remove(store) ?: return
        val copy = synchronized(list) { list.toList().also { list.clear() } }
        copy.forEach { runCatching { it.close() } }
    }

    private fun properties(readTimeoutMs: Long, opened: MutableList<Socket>) = Properties().apply {
        val p = "mail.$protocol"
        if (server.tls) {
            put("$p.ssl.enable", "true")
            put("$p.ssl.checkserveridentity", "true")
            put("$p.ssl.socketFactory", TrackingSslFactory(SSLSocketFactory.getDefault() as SSLSocketFactory, opened))
        } else {
            put("$p.socketFactory", TrackingFactory(SocketFactory.getDefault(), opened))
        }
        put("$p.connectiontimeout", "15000")
        put("$p.timeout", readTimeoutMs.toString())
        put("$p.writetimeout", "15000")
        put("$p.peek", "true")
    }
}

private class TrackingFactory(private val delegate: SocketFactory, private val opened: MutableList<Socket>) : SocketFactory() {
    private fun track(socket: Socket) = socket.also { opened += it }
    override fun createSocket(): Socket = track(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = track(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        track(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = track(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        track(delegate.createSocket(address, port, localAddress, localPort))
}

private class TrackingSslFactory(private val delegate: SSLSocketFactory, private val opened: MutableList<Socket>) : SSLSocketFactory() {
    private fun track(socket: Socket) = socket.also { opened += it }
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = track(delegate.createSocket())
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        track(delegate.createSocket(s, host, port, autoClose))
    override fun createSocket(host: String, port: Int): Socket = track(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        track(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = track(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        track(delegate.createSocket(address, port, localAddress, localPort))
}
