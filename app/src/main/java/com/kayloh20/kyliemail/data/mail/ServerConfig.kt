package com.kayloh20.kyliemail.data.mail

data class ServerConfig(
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    /** Always true for real servers. Only local test servers, which have no certificate, turn it off. */
    val tls: Boolean = true,
) {
    companion object {
        val ICLOUD = ServerConfig(
            imapHost = "imap.mail.me.com",
            imapPort = 993,
            smtpHost = "smtp.mail.me.com",
            smtpPort = 587,
        )
    }
}
