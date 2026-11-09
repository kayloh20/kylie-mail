package com.kayloh20.kyliemail.data.mail

data class ServerConfig(
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
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
