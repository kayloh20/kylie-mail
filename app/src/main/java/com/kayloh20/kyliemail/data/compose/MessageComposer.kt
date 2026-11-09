package com.kayloh20.kyliemail.data.compose

import java.io.File
import java.util.Date
import java.util.UUID
import javax.mail.Message
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMultipart
import javax.mail.internet.AddressException
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Everything needed to write one outgoing email. */
data class Outgoing(
    val fromAddress: String,
    val fromName: String,
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: String?,
    val messageId: String,
    val date: Date,
    val attachments: List<OutgoingAttachment> = emptyList(),
)

/** A file to send, already copied into the app. */
data class OutgoingAttachment(val file: File, val filename: String, val mimeType: String)

/**
 * Builds outgoing mail as a single plain-text part: no HTML version, no tracking, no extra headers.
 * The library handles encoding, so long lines and accented characters can't get the message rejected.
 */
object MessageComposer {

    /** A Message-ID on the sender's own domain; "@localhost" ones look suspicious to spam filters. */
    fun newMessageId(fromAddress: String): String {
        val domain = fromAddress.substringAfterLast('@', "").ifBlank { "icloud.com" }.lowercase()
        return "<${UUID.randomUUID()}@$domain>"
    }

    /** Returns a sentence describing what's wrong with the recipients, or null if they are fine. */
    fun validateRecipients(to: String, cc: String, bcc: String): String? {
        var total = 0
        for ((label, list) in listOf("To" to to, "Cc" to cc, "Bcc" to bcc)) {
            if (list.isBlank()) continue
            val parsed = try {
                parse(list)
            } catch (e: AddressException) {
                val bad = e.ref?.takeIf { it.isNotBlank() } ?: list
                return "Check the $label field: \"${bad.trim()}\" isn't a valid email address."
            }
            parsed.firstOrNull { !it.address.contains('@') || it.address.substringAfter('@').indexOf('.') < 1 }?.let {
                return "Check the $label field: \"${it.address}\" isn't a complete email address."
            }
            total += parsed.size
        }
        return if (total == 0) "Add at least one recipient." else null
    }

    /** [strict] is for sending; drafts keep whatever was typed, even half-finished addresses. */
    fun build(session: Session, outgoing: Outgoing, strict: Boolean = true): MimeMessage {
        val message = FixedIdMessage(session, outgoing.messageId)
        message.setFrom(
            if (outgoing.fromName.isBlank()) InternetAddress(outgoing.fromAddress) else InternetAddress(outgoing.fromAddress, outgoing.fromName, "UTF-8"),
        )
        for ((type, list) in listOf(
            Message.RecipientType.TO to outgoing.to,
            Message.RecipientType.CC to outgoing.cc,
            Message.RecipientType.BCC to outgoing.bcc,
        )) {
            if (list.isBlank()) continue
            if (strict) message.setRecipients(type, parse(list)) else message.setHeader(type.toString(), list)
        }
        message.setSubject(outgoing.subject, "UTF-8")
        message.sentDate = outgoing.date
        outgoing.inReplyTo?.let { message.setHeader("In-Reply-To", it) }
        outgoing.references?.let { message.setHeader("References", it) }
        val text = outgoing.body.replace("\r\n", "\n")
        if (outgoing.attachments.isEmpty()) {
            message.setText(text, "UTF-8")
        } else {
            // Text first, then each file as a standard attachment. Still no HTML anywhere.
            val parts = MimeMultipart("mixed")
            parts.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
            for (attachment in outgoing.attachments) {
                parts.addBodyPart(
                    MimeBodyPart().apply {
                        attachFile(attachment.file, attachment.mimeType.ifBlank { "application/octet-stream" }, "base64")
                        fileName = attachment.filename
                        disposition = Part.ATTACHMENT
                    },
                )
            }
            message.setContent(parts)
        }
        message.saveChanges()
        return message
    }

    /**
     * iCloud accepts messages up to 20 MB including encoding, which makes files about a third larger,
     * so this leaves room for the text and headers.
     */
    const val MAX_ATTACHMENT_BYTES = 14L * 1024 * 1024

    /** Headers the SMTP transport leaves out when sending; Bcc recipients must stay invisible. */
    val WIRE_EXCLUDED_HEADERS = arrayOf("Bcc", "Content-Length")

    private fun parse(list: String): Array<InternetAddress> =
        InternetAddress.parse(list.replace(';', ','), true).also { addresses -> addresses.forEach { it.validate() } }

    /** Keeps the Message-ID chosen up front, so a retried send can be recognised. */
    private class FixedIdMessage(session: Session, private val id: String) : MimeMessage(session) {
        override fun updateMessageID() {
            setHeader("Message-ID", id)
        }
    }
}
