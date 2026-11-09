package com.kayloh20.kyliemail.data.mail

import com.sun.mail.imap.IMAPStore
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Session

class ImapConnector(private val server: ServerConfig = ServerConfig.ICLOUD) {

    fun connect(username: String, password: String): IMAPStore {
        val session = Session.getInstance(properties())
        val store = session.getStore("imaps") as IMAPStore
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

    private fun properties() = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", server.imapHost)
        put("mail.imaps.port", server.imapPort.toString())
        put("mail.imaps.ssl.enable", "true")
        put("mail.imaps.ssl.checkserveridentity", "true")
        put("mail.imaps.connectiontimeout", "20000")
        put("mail.imaps.timeout", "60000")
        put("mail.imaps.writetimeout", "20000")
        // Keep a couple of logged-in connections around so opening folders doesn't log in again each time.
        put("mail.imaps.connectionpoolsize", "2")
        put("mail.imaps.connectionpooltimeout", "300000")
        // Reading a message must never mark it read on the server by itself.
        put("mail.imaps.peek", "true")
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
