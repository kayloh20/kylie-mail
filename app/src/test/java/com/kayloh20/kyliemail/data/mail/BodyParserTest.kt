package com.kayloh20.kyliemail.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage

class BodyParserTest {
    private fun parse(raw: String) =
        BodyParser.parse(MimeMessage(Session.getInstance(Properties()), raw.trimIndent().replace("\n", "\r\n").byteInputStream()))

    /** How Apple Mail sends text with a PDF and a photo dropped into the middle of it. */
    private val appleMail = """
        From: Madison <madison@example.com>
        Subject: Flyer
        MIME-Version: 1.0
        Content-Type: multipart/alternative; boundary="A"

        --A
        Content-Type: text/plain; charset=us-ascii

        Hi! Here is the flyer.

        And the photo after it. Bye!
        --A
        Content-Type: multipart/mixed; boundary="B"

        --B
        Content-Type: text/html; charset=us-ascii

        <html><head><style>p{color:#c0c}</style></head><body><p>Hi! Here is the flyer.</p></body></html>
        --B
        Content-Disposition: inline; filename=flyer.pdf
        Content-Type: application/pdf; name="flyer.pdf"
        Content-Transfer-Encoding: base64

        JVBERi0xLjQKJcfsj6IK
        --B
        Content-Type: text/html; charset=us-ascii

        <html><body><p>And the photo after it.</p></body></html>
        --B
        Content-Disposition: inline; filename=photo.png
        Content-Type: image/png; name="photo.png"
        Content-Transfer-Encoding: base64

        iVBORw0KGgoAAAANSUhEUg==
        --B
        Content-Type: text/html; charset=us-ascii

        <html><body><p>Bye!</p></body></html>
        --B--
        --A--
    """

    @Test fun appleMailKeepsEveryPieceInOrder() {
        val body = parse(appleMail)
        val html = body.html!!
        val order = listOf("Hi! Here is the flyer.", "attachment/2.2", "And the photo after it.", "cid:kylie-part-2.4", "Bye!")
        val positions = order.map { html.indexOf(it) }
        assertTrue("all present: $positions", positions.all { it >= 0 })
        assertEquals("in order", positions.sorted(), positions)
        assertTrue("styles kept", html.contains("p{color:#c0c}"))
        assertEquals(listOf("flyer.pdf", "photo.png"), body.attachments.map { it.filename })
        assertTrue(body.attachments.all { it.inBody })
        assertEquals(listOf("2.2", "2.4"), body.attachments.map { it.path })
        assertEquals(listOf("kylie-part-2.4"), body.inlineImages.map { it.contentId })
        assertTrue(body.text!!.contains("And the photo after it. Bye!"))
    }

    @Test fun gmailStyleAttachmentsStayInTheList() {
        val body = parse(
            """
            Subject: Receipt
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="M"

            --M
            Content-Type: multipart/alternative; boundary="ALT"

            --ALT
            Content-Type: text/plain; charset=UTF-8

            Your receipt is attached.
            --ALT
            Content-Type: text/html; charset=UTF-8

            <div dir="ltr">Your receipt is attached.</div>
            --ALT--
            --M
            Content-Type: application/pdf; name="receipt.pdf"
            Content-Disposition: attachment; filename="receipt.pdf"
            Content-Transfer-Encoding: base64

            JVBERi0xLjQKJcfsj6IKJVBERi0xLjQKJcfsj6IK
            --M--
            """,
        )
        assertTrue(body.html!!.contains("Your receipt is attached."))
        assertFalse("not shown in the text", body.html.contains("attachment/"))
        assertEquals(1, body.attachments.size)
        val receipt = body.attachments.single()
        assertEquals("receipt.pdf", receipt.filename)
        assertEquals("2", receipt.path)
        assertFalse(receipt.inBody)
        assertEquals(30L, receipt.size)
        assertEquals("Your receipt is attached.", body.text)
    }

    @Test fun newsletterImagesAreEmbeddedNotListed() {
        val body = parse(
            """
            Subject: News
            MIME-Version: 1.0
            Content-Type: multipart/alternative; boundary="ALT"

            --ALT
            Content-Type: text/plain

            News
            --ALT
            Content-Type: multipart/related; boundary="REL"

            --REL
            Content-Type: text/html

            <img src="cid:logo@shop"> News
            --REL
            Content-Type: image/png
            Content-ID: <logo@shop>
            Content-Transfer-Encoding: base64

            iVBORw0KGgoAAAANSUhEUg==
            --REL--
            --ALT--
            """,
        )
        assertTrue(body.attachments.isEmpty())
        assertEquals(listOf("<logo@shop>"), body.inlineImages.map { it.contentId })
        assertTrue(body.html!!.contains("cid:logo@shop"))
    }

    @Test fun plainTextStaysPlain() {
        val body = parse(
            """
            Subject: Hi
            Content-Type: text/plain; charset=UTF-8

            Just text.
            """,
        )
        assertNull(body.html)
        assertEquals("Just text.", body.text)
        assertTrue(body.attachments.isEmpty())
    }

    @Test fun plainTextWithAnAttachmentAtTheEnd() {
        val body = parse(
            """
            Subject: Notes
            MIME-Version: 1.0
            Content-Type: multipart/mixed; boundary="M"

            --M
            Content-Type: text/plain

            Notes attached.
            --M
            Content-Type: text/plain; name="notes.txt"
            Content-Disposition: attachment; filename="notes.txt"

            the notes
            --M--
            """,
        )
        assertNull("one text part needs no HTML", body.html)
        assertEquals("Notes attached.", body.text)
        assertEquals(listOf("notes.txt"), body.attachments.map { it.filename })
    }

    @Test fun sizes() {
        assertEquals("900 B", BodyParser.formatSize(900))
        assertEquals("12 KB", BodyParser.formatSize(12_000))
        assertEquals("2.5 MB", BodyParser.formatSize(2_621_440))
    }
}
