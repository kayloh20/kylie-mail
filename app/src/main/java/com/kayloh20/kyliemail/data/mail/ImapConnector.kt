package com.kayloh20.kyliemail.data.mail

import com.sun.mail.imap.IMAPStore
import java.net.InetAddress
import java.net.Socket
import java.util.Collections
import java.util.Properties
import java.util.WeakHashMap
import javax.mail.AuthenticationFailedException
import javax.mail.Session
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

class ImapConnector(private val server: ServerConfig = ServerConfig.ICLOUD) {

    /** Every socket each store has opened, so [kill] can cut them all at once. */
    private val sockets = Collections.synchronizedMap(WeakHashMap<IMAPStore, MutableList<Socket>>())

    fun connect(username: String, password: String, readTimeoutMs: Long = READ_TIMEOUT_MS): IMAPStore {
        val opened = Collections.synchronizedList(mutableListOf<Socket>())
        val session = Session.getInstance(properties(readTimeoutMs, opened))
        val store = session.getStore(protocol) as IMAPStore
        sockets[store] = opened
        try {
            store.connect(server.imapHost, server.imapPort, username, password)
        } catch (e: Exception) {
            kill(store)
            throw e
        }
        return store
    }

    /**
     * Cuts every connection [store] has open, right now. Anything waiting on one fails at once
     * instead of after a timeout. Closing politely can't do this: logging out over a connection
     * that has gone quiet waits just as long. The store is unusable afterwards.
     */
    fun kill(store: IMAPStore) {
        val list = sockets.remove(store) ?: return
        val copy = synchronized(list) { list.toList().also { list.clear() } }
        for (socket in copy) runCatching { socket.close() }
    }

    /**
     * Checks the credentials and returns the username the server accepted.
     * iCloud takes either the full address or, for its own domains, just the part before the @.
     */
    fun verify(email: String, password: String): String {
        var lastError: AuthenticationFailedException? = null
        for (candidate in usernameCandidates(email)) {
            try {
                connect(candidate, password).close()
                return candidate
            } catch (e: AuthenticationFailedException) {
                lastError = e
            }
        }
        throw lastError ?: AuthenticationFailedException()
    }

    private val protocol = if (server.tls) "imaps" else "imap"

    private fun properties(readTimeoutMs: Long, opened: MutableList<Socket>) = Properties().apply {
        val p = "mail.$protocol"
        put("mail.store.protocol", protocol)
        put("$p.host", server.imapHost)
        put("$p.port", server.imapPort.toString())
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
        // Keep a couple of logged-in connections around so opening folders doesn't log in again each time,
        // but not so long that one quietly dies in the pool and gets handed out later.
        put("$p.connectionpoolsize", "2")
        put("$p.connectionpooltimeout", "60000")
        // Reading a message must never mark it read on the server by itself.
        put("$p.peek", "true")
    }

    companion object {
        /** How long to wait for iCloud to say anything before giving up on a connection. */
        const val READ_TIMEOUT_MS = 30_000L

        private val appleDomains = setOf("icloud.com", "me.com", "mac.com")

        fun usernameCandidates(email: String): List<String> {
            val trimmed = email.trim()
            val at = trimmed.lastIndexOf('@')
            if (at <= 0) return listOf(trimmed)
            val domain = trimmed.substring(at + 1).lowercase()
            return if (domain in appleDomains) listOf(trimmed, trimmed.substring(0, at)) else listOf(trimmed)
        }
    }
}

/** Hands out the sockets [delegate] makes and remembers them in [opened]. */
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

/** The TLS version of [TrackingFactory]. */
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
