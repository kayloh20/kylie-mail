package com.kayloh20.kyliemail.data.mail

import com.sun.mail.imap.IMAPStore
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Session

class ImapConnector(private val server: ServerConfig = ServerConfig.ICLOUD) {

    fun connect(username: String, password: String, readTimeoutMs: Long = 60_000): IMAPStore {
        val session = Session.getInstance(properties(readTimeoutMs))
        val store = session.getStore(protocol) as IMAPStore
        store.connect(server.imapHost, server.imapPort, username, password)
        return store
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

    private fun properties(readTimeoutMs: Long) = Properties().apply {
        val p = "mail.$protocol"
        put("mail.store.protocol", protocol)
        put("$p.host", server.imapHost)
        put("$p.port", server.imapPort.toString())
        if (server.tls) {
            put("$p.ssl.enable", "true")
            put("$p.ssl.checkserveridentity", "true")
        }
        put("$p.connectiontimeout", "20000")
        put("$p.timeout", readTimeoutMs.toString())
        put("$p.writetimeout", "20000")
        // Keep a couple of logged-in connections around so opening folders doesn't log in again each time.
        put("$p.connectionpoolsize", "2")
        put("$p.connectionpooltimeout", "300000")
        // Reading a message must never mark it read on the server by itself.
        put("$p.peek", "true")
    }

    companion object {
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
