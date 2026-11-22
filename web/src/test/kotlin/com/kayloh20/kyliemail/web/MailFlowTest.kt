package com.kayloh20.kyliemail.web

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailFlowTest {
    @get:Rule val folder = TemporaryFolder()

    private val mail = TestMail().apply { seed() }
    private val config = WebConfig(origin = "https://mail.example.org", dataDir = "unused")
    private val json = Json { ignoreUnknownKeys = true }

    @After fun stop() = mail.close()

    private fun server(block: suspend Browser.() -> Unit) = testApplication {
        val dataDir = folder.root.toPath()
        val services = Services(
            config = config,
            vault = Vault(AccountStore(dataDir.resolve("account.json"))),
            passkeys = Passkeys(config),
            sessions = Sessions(),
            setupCode = SetupCode(dataDir.resolve("setup-code")),
            mailLogin = ImapMailLogin(com.kayloh20.kyliemail.data.mail.ImapConnector(mail.server)),
            mailServer = mail.server,
        )
        val prf = Crypto.randomBytes(32)
        val created = services.vault.create(
            mail.email, mail.email, mail.password, "handle",
            NewPasskey("pk", "Laptop", "unused", 0, false, false, Crypto.b64url(Crypto.randomBytes(32))), prf,
        )
        val token = services.sessions.create(created.accountKey)
        application { webModule(config, services) }
        Browser(this, token).block()
    }

    inner class Browser(private val app: ApplicationTestBuilder, var token: String?) {
        suspend fun get(path: String): HttpResponse = app.client.get(path) {
            token?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
        }

        suspend fun post(path: String, body: String): HttpResponse = app.client.post(path) {
            header(HttpHeaders.Origin, config.origin)
            token?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        suspend fun <T> HttpResponse.read(serializer: KSerializer<T>): T {
            assertEquals(HttpStatusCode.OK, status, bodyAsText())
            return json.decodeFromString(serializer, bodyAsText())
        }

        suspend fun folders() = get("/api/folders").read(ListSerializer(FolderInfo.serializer()))
        suspend fun inbox(before: Int? = null) =
            get("/api/messages?folder=INBOX" + (before?.let { "&before=$it" } ?: "")).read(MessagePage.serializer())
        fun query(page: MessagePage, uid: Long) = "folder=${page.folder.encodeURLParameter()}&validity=${page.validity}&uid=$uid"
    }

    @Test
    fun foldersAndMessages() = server {
        val folders = folders()
        assertEquals(listOf("Inbox", "Receipts"), folders.map { it.name })
        assertEquals("inbox", folders[0].role)
        assertEquals(4, folders[0].unread)

        val page = inbox()
        assertEquals(4, page.total)
        assertEquals(listOf("Pep rally!!", "You won't believe this deal", "Your order has shipped", "Dinner Sunday?"), page.messages.map { it.subject })
        assertEquals("Madison", page.messages[0].fromName)
        assertTrue(page.messages[0].flagged)
        assertTrue(page.messages[2].hasAttachments)
        assertEquals(null, page.before)

        val older = inbox(before = 3)
        assertEquals(listOf("Your order has shipped", "Dinner Sunday?"), older.messages.map { it.subject })
    }

    @Test
    fun readingAMessageAndMarkingItRead() = server {
        val page = inbox()
        val order = page.messages.first { it.subject == "Your order has shipped" }
        val detail = get("/api/message?${query(page, order.uid)}").read(MessageDetail.serializer())
        // GreenMail doesn't report the logo's Content-ID the way iCloud does, so it may be listed too;
        // embedding inline images is covered by MailHtmlTest.
        assertTrue(detail.attachments.map { it.filename }.containsAll(listOf("receipt.pdf", "invoice.html")), detail.attachments.toString())
        assertEquals(0, detail.remoteImages)
        assertEquals(listOf(mail.email), detail.to)

        val body = get("/api/message/body?${query(page, order.uid)}")
        assertTrue("Thanks for your order!" in body.bodyAsText())

        assertEquals(4, folders()[0].unread, "opening doesn't mark it read by itself")
        post("/api/messages/seen", """{"folder":"INBOX","validity":${page.validity},"uids":[${order.uid}],"seen":true}""").read(Done.serializer())
        assertEquals(3, folders()[0].unread)
        assertTrue(inbox().messages.first { it.uid == order.uid }.seen)
    }

    @Test
    fun emailsAreShownUnderTheirOwnStrictRules() = server {
        val page = inbox()
        val sneaky = page.messages.first { it.fromAddress == "news@tracker.example" }
        val detail = get("/api/message?${query(page, sneaky.uid)}").read(MessageDetail.serializer())
        assertEquals(4, detail.remoteImages)
        assertFalse(detail.imagesAllowed)

        val body = get("/api/message/body?${query(page, sneaky.uid)}")
        val csp = body.headers["Content-Security-Policy"]!!
        assertEquals(MailHtml.contentSecurityPolicy(allowRemoteImages = false), csp)
        assertEquals("SAMEORIGIN", body.headers["X-Frame-Options"])
        assertEquals("no-store", body.headers[HttpHeaders.CacheControl])
        val html = body.bodyAsText()
        assertFalse("<script" in html || "evil.example" in html || "tracker.example" in html, html)

        val withImages = get("/api/message/body?${query(page, sneaky.uid)}&images=1")
        assertTrue("img-src data: https:" in withImages.headers["Content-Security-Policy"]!!)
        assertTrue("tracker.example/open.gif" in withImages.bodyAsText())

        // Trusting the sender remembers it, sealed with the account key.
        post("/api/image-senders", """{"address":"News@Tracker.example"}""").read(Done.serializer())
        assertTrue(get("/api/message?${query(page, sneaky.uid)}").read(MessageDetail.serializer()).imagesAllowed)
        assertFalse("tracker.example" in folder.root.resolve("account.json").readText())

        // The rest of the site keeps its usual rules.
        assertEquals(SecurityHeaders.CONTENT_SECURITY_POLICY, get("/api/folders").headers["Content-Security-Policy"])
    }

    @Test
    fun attachmentsAlwaysDownload() = server {
        val page = inbox()
        val order = page.messages.first { it.subject == "Your order has shipped" }
        val detail = get("/api/message?${query(page, order.uid)}").read(MessageDetail.serializer())
        val html = detail.attachments.first { it.filename == "invoice.html" }
        val download = get("/api/message/attachment?${query(page, order.uid)}&path=${html.path}")
        assertEquals(HttpStatusCode.OK, download.status)
        assertTrue(download.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"), download.headers[HttpHeaders.ContentDisposition])
        assertEquals("application/octet-stream", download.headers[HttpHeaders.ContentType])
        assertEquals(listOf("sandbox; default-src 'none'"), download.headers.getAll("Content-Security-Policy"))
        assertContentEquals("<script>alert(1)</script>".toByteArray(), download.readRawBytes())

        assertEquals(HttpStatusCode.NotFound, get("/api/message/attachment?${query(page, order.uid)}&path=9").status)
    }

    @Test
    fun staleOrMissingThingsAreReported() = server {
        val page = inbox()
        assertEquals(HttpStatusCode.Conflict, get("/api/message?folder=INBOX&validity=${page.validity + 1}&uid=${page.messages[0].uid}").status)
        assertEquals(HttpStatusCode.NotFound, get("/api/message?folder=INBOX&validity=${page.validity}&uid=99999").status)
        assertEquals(HttpStatusCode.NotFound, get("/api/messages?folder=Nope").status)
        assertEquals(HttpStatusCode.BadRequest, get("/api/message?folder=INBOX").status)
    }

    @Test
    fun mailNeedsASession() = server {
        token = null
        for (path in listOf("/api/folders", "/api/messages?folder=INBOX", "/api/message?folder=INBOX&validity=1&uid=1", "/api/message/body?folder=INBOX&validity=1&uid=1")) {
            assertEquals(HttpStatusCode.Unauthorized, get(path).status, path)
        }
        assertEquals(HttpStatusCode.Unauthorized, post("/api/messages/seen", """{"folder":"INBOX","validity":1,"uids":[1],"seen":true}""").status)
    }
}
