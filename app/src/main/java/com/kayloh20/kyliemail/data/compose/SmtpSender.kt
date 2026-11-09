package com.kayloh20.kyliemail.data.compose

import com.kayloh20.kyliemail.data.mail.ServerConfig
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

/** Hands messages to iCloud's outgoing server, always over an encrypted connection. */
class SmtpSender(private val server: ServerConfig = ServerConfig.ICLOUD) {

    fun session(): Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", server.smtpHost)
            put("mail.smtp.port", server.smtpPort.toString())
            put("mail.smtp.auth", "true")
            // Port 587 starts plain and upgrades; refusing to continue without the upgrade keeps passwords off the wire.
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "60000")
            put("mail.smtp.writetimeout", "60000")
        },
    )

    fun send(session: Session, message: MimeMessage, username: String, password: String) {
        session.getTransport("smtp").use { transport ->
            transport.connect(server.smtpHost, server.smtpPort, username, password)
            transport.sendMessage(message, message.allRecipients)
        }
    }
}
