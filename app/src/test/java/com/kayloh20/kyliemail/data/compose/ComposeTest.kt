package com.kayloh20.kyliemail.data.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Session

class ComposeTest {
    private val own = setOf("kylie@icloud.com")
    private val original = OriginalMessage(
        fromName = "Madison Clarke",
        fromAddress = "madison@example.com",
        toList = "Kylie <kylie@icloud.com>, Coach <coach@example.com>",
        ccList = "Bri <bri@example.com>, kylie@icloud.com",
        subject = "Cheer practice moved to 4pm",
        sentAt = 1_794_211_560_000, // Nov 9, 2026 08:06 UTC
        bodyText = "Hey!\n\n> earlier quote\nGo team!!",
        messageIdHeader = "<abc123@example.com>",
    )

    private fun build(mode: ReplyMode, signature: String = "") =
        ReplyBuilder.build(original, mode, own, signature, ZoneOffset.UTC, Locale.US)

    @Test fun replyGoesToTheSenderWithThreadingHeaders() {
        val reply = build(ReplyMode.REPLY)
        assertEquals("madison@example.com", reply.to)
        assertEquals("", reply.cc)
        assertEquals("Re: Cheer practice moved to 4pm", reply.subject)
        assertEquals("<abc123@example.com>", reply.inReplyTo)
        assertEquals("<abc123@example.com>", reply.references)
    }

    @Test fun replyAllCopiesEveryoneExceptYou() {
        val reply = build(ReplyMode.REPLY_ALL)
        assertEquals("madison@example.com", reply.to)
        assertEquals("coach@example.com, bri@example.com", reply.cc)
    }

    @Test fun replyingToYourOwnSentMailGoesToItsRecipients() {
        val sent = original.copy(fromAddress = "kylie@icloud.com", fromName = "Kylie")
        val reply = ReplyBuilder.build(sent, ReplyMode.REPLY, own, zone = ZoneOffset.UTC, locale = Locale.US)
        assertEquals("coach@example.com", reply.to)
    }

    @Test fun quoteAndAttribution() {
        val body = build(ReplyMode.REPLY).body
        assertTrue(body.startsWith("\n"))
        assertTrue(body.contains("On Mon, Nov 9, 2026 at 8:06 AM, Madison Clarke <madison@example.com> wrote:\n> Hey!\n>\n>> earlier quote\n> Go team!!"))
    }

    @Test fun signatureUsesTheStandardSeparatorAboveTheQuote() {
        val body = build(ReplyMode.REPLY, signature = "Kylie\nCheer captain").body
        assertTrue(body.startsWith("\n\n-- \nKylie\nCheer captain\n\nOn "))
    }

    @Test fun forwardKeepsTheOriginalHeadersAndHasNoRecipients() {
        val fwd = build(ReplyMode.FORWARD)
        assertEquals("", fwd.to)
        assertEquals("Fwd: Cheer practice moved to 4pm", fwd.subject)
        assertNull(fwd.inReplyTo)
        assertTrue(fwd.body.contains("---------- Forwarded message ----------\nFrom: Madison Clarke <madison@example.com>"))
        assertTrue(fwd.body.contains("Go team!!"))
    }

    @Test fun subjectsDontPileUpPrefixes() {
        assertEquals("Re: Hi", ReplyBuilder.build(original.copy(subject = "RE: Hi"), ReplyMode.REPLY, own).subject.replace("RE:", "Re:"))
        assertEquals("Fwd: Hi", ReplyBuilder.build(original.copy(subject = "Fwd: Hi"), ReplyMode.FORWARD, own).subject)
        assertEquals("Re: Fwd: Hi", ReplyBuilder.build(original.copy(subject = "Fwd: Hi"), ReplyMode.REPLY, own).subject)
    }

    @Test fun recipientValidation() {
        assertEquals("Add at least one recipient.", MessageComposer.validateRecipients("", " ", ""))
        assertNull(MessageComposer.validateRecipients("Madison <madison@example.com>, coach@example.com", "", ""))
        assertNull(MessageComposer.validateRecipients("a@example.com; b@example.com", "", ""))
        assertTrue(MessageComposer.validateRecipients("madison", "", "")!!.startsWith("Check the To field"))
        assertTrue(MessageComposer.validateRecipients("a@example.com", "bri@example", "")!!.startsWith("Check the Cc field"))
    }

    @Test fun outgoingMailIsOnePlainTextPartWithNothingHidden() {
        val longLine = "This line is deliberately very long. ".repeat(40)
        val outgoing = Outgoing(
            fromAddress = "kylie@icloud.com",
            fromName = "Kylie Ñúñez",
            to = "Madison Clarke <madison@example.com>",
            cc = "",
            bcc = "secret@example.com",
            subject = "Re: Cheer practice ✨",
            body = "See you at 4! 💜\n\n$longLine\n\n> quoted line",
            inReplyTo = "<abc123@example.com>",
            references = "<abc123@example.com>",
            messageId = MessageComposer.newMessageId("kylie@icloud.com"),
            date = Date(1_794_211_560_000),
        )
        val message = MessageComposer.build(Session.getInstance(Properties()), outgoing)
        // Exactly what goes to the server: the SMTP transport writes the message this way.
        val raw = ByteArrayOutputStream().also { message.writeTo(it, MessageComposer.WIRE_EXCLUDED_HEADERS) }
            .toString(Charsets.US_ASCII.name())

        // Saved so the exact bytes can be read by a person.
        File("build/compose-sample.eml").apply { parentFile?.mkdirs() }.writeText(raw)

        assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8"))
        assertFalse("no HTML part", raw.contains("text/html", ignoreCase = true))
        assertFalse("no multipart wrapper", raw.contains("multipart", ignoreCase = true))
        assertFalse("Bcc is never written into the message", raw.contains("secret@example.com"))
        assertTrue(Regex("Message-ID: <[0-9a-f-]{36}@icloud\\.com>").containsMatchIn(raw))
        assertTrue(raw.contains("In-Reply-To: <abc123@example.com>"))
        assertTrue("every line within the 998 character limit", raw.lines().all { it.length <= 998 })
        assertTrue("only ASCII on the wire", raw.all { it.code < 128 })
        assertFalse("no mailer fingerprint", raw.contains("X-Mailer", ignoreCase = true))
    }
}
