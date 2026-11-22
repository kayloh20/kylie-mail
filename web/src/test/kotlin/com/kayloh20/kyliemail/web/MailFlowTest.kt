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
    // Built once the temporary folder exists, so everything the server saves lands there.
    private val config by lazy { WebConfig(origin = "https://mail.example.org", dataDir = folder.root.path) }
    private val json = Json { ignoreUnknownKeys = true }

    @After fun stop() = mail.close()

    private lateinit var services: Services
    private lateinit var accountKey: ByteArray

    private fun server(undoMs: Long = 400, undoSendMs: Long = 300, block: suspend Browser.() -> Unit) = testApplication {
        val dataDir = folder.root.toPath()
        val services = Services(
            config = config,
            vault = Vault(AccountStore(dataDir.resolve("account.json"))),
            passkeys = Passkeys(config),
            sessions = Sessions(),
            setupCode = SetupCode(dataDir.resolve("setup-code")),
            mailLogin = ImapMailLogin(com.kayloh20.kyliemail.data.mail.ImapConnector(mail.server)),
            mailServer = mail.server,
            undoMs = undoMs,
            undoSendMs = undoSendMs,
            sentCopyDelayMs = 100,
        )
        this@MailFlowTest.services = services
        val prf = Crypto.randomBytes(32)
        val created = services.vault.create(
            mail.email, mail.email, mail.password, "handle",
            NewPasskey("pk", "Laptop", "unused", 0, false, false, Crypto.b64url(Crypto.randomBytes(32))), prf,
        )
        accountKey = created.accountKey.copyOf()
        val token = services.sessions.create(created.accountKey)
        application { webModule(config, services) }
        Browser(this, token).block()
    }

    inner class Browser(val app: ApplicationTestBuilder, var token: String?) {
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
        suspend fun page(folder: String) =
            get("/api/messages?folder=${folder.encodeURLParameter()}").read(MessagePage.serializer())
        suspend fun subjects(folder: String) = page(folder).messages.map { it.subject }

        suspend fun remove(page: MessagePage, uids: List<Long>, action: String, target: String? = null): HttpResponse =
            post(
                "/api/messages/remove",
                """{"folder":"${page.folder}","validity":${page.validity},"uids":[${uids.joinToString(",")}],"action":"$action"""" +
                    (target?.let { ""","target":"$it"""" } ?: "") + "}",
            )

        /** Waits for [check] to hold, as changes reach the server after the undo window. */
        suspend fun eventually(what: String, check: suspend () -> Boolean) {
            val until = System.currentTimeMillis() + 8_000
            while (!check()) {
                if (System.currentTimeMillis() > until) throw AssertionError("never happened: $what")
                kotlinx.coroutines.delay(100)
            }
        }

        fun query(page: MessagePage, uid: Long) = "folder=${page.folder.encodeURLParameter()}&validity=${page.validity}&uid=$uid"
    }

    @Test
    fun foldersAndMessages() = server {
        val folders = folders()
        assertEquals(listOf("Inbox", "Drafts", "Sent", "Archive", "Trash", "Receipts"), folders.map { it.name })
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
        post("/api/messages/flags", """{"folder":"INBOX","validity":${page.validity},"uids":[${order.uid}],"flag":"seen","value":true}""").read(Done.serializer())
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
        assertEquals(HttpStatusCode.Unauthorized, post("/api/messages/flags", """{"folder":"INBOX","validity":1,"uids":[1],"flag":"seen","value":true}""").status)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/messages/remove", """{"folder":"INBOX","validity":1,"uids":[1],"action":"delete"}""").status)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/messages/undo", """{"id":"x"}""").status)
    }

    @Test
    fun deletingMovesToTrashAfterAMomentAndShowsRightAway() = server {
        val inbox = inbox()
        val pep = inbox.messages.first { it.subject == "Pep rally!!" }
        val removal = remove(inbox, listOf(pep.uid), "delete").read(Removal.serializer())
        assertEquals("Deleted Messages", removal.target)
        assertEquals("Trash", removal.targetName)

        // Gone from the inbox at once, and the counts already show where it's going.
        assertFalse("Pep rally!!" in subjects("INBOX"))
        val folders = folders().associateBy { it.name }
        assertEquals(3, folders.getValue("Inbox").total)
        assertEquals(3, folders.getValue("Inbox").unread)
        assertEquals(1, folders.getValue("Trash").total)

        eventually("it reaches the Trash") { subjects("Deleted Messages") == listOf("Pep rally!!") }
        assertEquals(3, inbox().total)
        assertEquals(UndoReply(false), post("/api/messages/undo", """{"id":"${removal.id}"}""").read(UndoReply.serializer()), "too late to undo")
    }

    @Test
    fun undoKeepsTheMessageWhereItWas() = server(undoMs = 2_000) {
        val inbox = inbox()
        val removal = remove(inbox, inbox.messages.take(2).map { it.uid }, "archive").read(Removal.serializer())
        assertEquals("Archive", removal.target)
        assertEquals(2, inbox().messages.size)
        assertEquals(UndoReply(true), post("/api/messages/undo", """{"id":"${removal.id}"}""").read(UndoReply.serializer()))
        assertEquals(4, inbox().messages.size)
        kotlinx.coroutines.delay(2_500)
        assertEquals(4, inbox().messages.size, "nothing was sent")
        assertEquals(emptyList(), subjects("Archive"))
    }

    @Test
    fun movingAndDeletingForGood() = server {
        val inbox = inbox()
        val mom = inbox.messages.first { it.subject == "Dinner Sunday?" }
        assertEquals("Receipts", remove(inbox, listOf(mom.uid), "move", "Receipts").read(Removal.serializer()).targetName)
        eventually("it reaches Receipts") { "Dinner Sunday?" in subjects("Receipts") }

        val receipts = page("Receipts")
        remove(receipts, listOf(receipts.messages.first { it.subject == "Dinner Sunday?" }.uid), "delete").read(Removal.serializer())
        eventually("it reaches the Trash") { subjects("Deleted Messages") == listOf("Dinner Sunday?") }

        // Deleting from the Trash deletes for good.
        val trash = page("Deleted Messages")
        assertEquals(null, remove(trash, listOf(trash.messages[0].uid), "delete").read(Removal.serializer()).target)
        eventually("it's gone for good") { subjects("Deleted Messages").isEmpty() }
        assertEquals(listOf(3, 0, 0, 0, 0, 5), folders().map { it.total })
    }

    @Test
    fun flagsChangeRightAway() = server {
        val inbox = inbox()
        val mom = inbox.messages.first { it.subject == "Dinner Sunday?" }
        post("/api/messages/flags", """{"folder":"INBOX","validity":${inbox.validity},"uids":[${mom.uid}],"flag":"flagged","value":true}""").read(Done.serializer())
        post("/api/messages/flags", """{"folder":"INBOX","validity":${inbox.validity},"uids":[${mom.uid}],"flag":"seen","value":true}""").read(Done.serializer())
        val now = inbox().messages.first { it.uid == mom.uid }
        assertTrue(now.flagged && now.seen)
        assertEquals(HttpStatusCode.BadRequest, post("/api/messages/flags", """{"folder":"INBOX","validity":${inbox.validity},"uids":[${mom.uid}],"flag":"deleted","value":true}""").status)
    }

    @Test
    fun signingOutSendsWaitingChanges() = server(undoMs = 60_000) {
        val inbox = inbox()
        remove(inbox, listOf(inbox.messages[0].uid), "delete").read(Removal.serializer())
        post("/api/signout", "{}")
        // Read the server directly: the session is gone.
        val store = com.kayloh20.kyliemail.data.mail.ImapConnector(mail.server).connect(mail.email, mail.password)
        try {
            eventually("the delete is sent on sign-out, long before the minute is up") {
                val trash = store.getFolder("Deleted Messages")
                trash.open(javax.mail.Folder.READ_ONLY)
                val count = trash.messageCount
                trash.close(false)
                count == 1
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun nonsenseIsRefused() = server {
        val inbox = inbox()
        val uid = inbox.messages[0].uid
        assertEquals(HttpStatusCode.BadRequest, remove(inbox, listOf(uid), "move", "INBOX").status, "same folder")
        assertEquals(HttpStatusCode.NotFound, remove(inbox, listOf(uid), "move", "Nope").status)
        assertEquals(HttpStatusCode.BadRequest, remove(inbox, listOf(uid), "move").status, "no target")
        assertEquals(HttpStatusCode.BadRequest, remove(inbox, listOf(uid), "shred").status)
        assertEquals(HttpStatusCode.BadRequest, remove(inbox, emptyList(), "delete").status)
        assertEquals(HttpStatusCode.NotFound, remove(inbox, listOf(99999), "delete").status)
        val archive = page("Archive")
        assertEquals(HttpStatusCode.BadRequest, remove(archive, listOf(1), "archive").status, "already archived")
        assertEquals(4, inbox().messages.size)
    }

    // Smart filing

    private suspend fun Browser.filing() = get("/api/filing").read(FilingReply.serializer())

    private suspend fun Browser.suggest(page: MessagePage, uids: List<Long>) =
        get("/api/filing/suggest?folder=${page.folder.encodeURLParameter()}&validity=${page.validity}&uids=${uids.joinToString(",")}")
            .read(SuggestReply.serializer())

    private suspend fun Browser.train(vararg folders: String) {
        post("/api/filing/train", """{"folders":[${folders.joinToString(",") { "\"$it\"" }}]}""").read(Done.serializer())
        eventually("training finishes") { filing().status.state == "finished" }
    }

    @Test
    fun smartFilingLearnsFromTrainedFoldersAndSuggests() = server {
        val inbox = inbox()
        val order = inbox.messages.first { it.subject == "Your order has shipped" }

        val untrained = filing()
        assertEquals(0, untrained.examples)
        assertEquals(listOf("Archive" to false, "Receipts" to false), untrained.folders.map { it.name to it.trained })
        assertEquals(SuggestReply(trained = false, suggestions = emptyList()), suggest(inbox, listOf(order.uid)))

        train("Receipts")
        val trained = filing()
        assertEquals(5, trained.examples)
        assertEquals(FilingFolder("Receipts", "Receipts", trained = true, examples = 5), trained.folders.last())

        val suggestion = suggest(inbox, listOf(order.uid)).suggestions.first()
        assertEquals("Receipts", suggestion.folder)
        assertTrue(suggestion.confident, suggestion.toString())
        assertEquals("You've filed 5 emails from Shop here", suggestion.reason)

        // Mom's email isn't like those.
        val mom = inbox.messages.first { it.subject == "Dinner Sunday?" }
        assertTrue(suggest(inbox, listOf(mom.uid)).suggestions.none { it.confident })

        // What it learned is sealed on disk, like the password.
        val sealed = folder.root.resolve("filing.sealed").readBytes()
        assertFalse("shop.example" in String(sealed, Charsets.ISO_8859_1))
        assertEquals(5, FilingStore(folder.root.toPath().resolve("filing.sealed")).load(accountKey).examples.size)
    }

    @Test
    fun movesAreLessonsUnlessUndone() = server(undoMs = 2_000) {
        train("Receipts")
        val inbox = inbox()
        val mom = inbox.messages.first { it.subject == "Dinner Sunday?" }
        val removal = remove(inbox, listOf(mom.uid), "move", "Receipts").read(Removal.serializer())
        assertEquals(6, filing().examples)
        post("/api/messages/undo", """{"id":"${removal.id}"}""").read(UndoReply.serializer())
        assertEquals(5, filing().examples, "an undone move isn't a lesson")

        // Deleting isn't filing.
        remove(inbox(), listOf(mom.uid), "delete").read(Removal.serializer())
        assertEquals(5, filing().examples)
    }

    @Test
    fun filingIsRememberedAndKeepsLearning() = server {
        train("Receipts")
        // Another order gets filed elsewhere, say from the phone.
        mail.file("Receipts", mail.pastOrder(6))
        post("/api/signout", "{}")

        // Signing in again opens what was learned, then picks up the new one.
        token = services.sessions.create(accountKey)
        eventually("the new order is learned") { filing().examples == 6 }
    }

    @Test
    fun trainingFewerFoldersForgetsTheRest() = server {
        train("Receipts")
        train()
        val after = filing()
        assertEquals(0, after.examples)
        assertTrue(after.folders.none { it.trained })
    }

    // Writing

    private suspend fun Browser.start(mode: String, page: MessagePage? = null, uid: Long? = null): Started {
        val message = if (page != null && uid != null) ""","message":{"folder":"${page.folder}","validity":${page.validity},"uid":$uid}""" else ""
        return post("/api/compose/start", """{"mode":"$mode","zone":"America/Los_Angeles"$message}""").read(Started.serializer())
    }

    private val wire = Json { encodeDefaults = true }

    private suspend fun Browser.send(draft: Draft): Sending =
        post("/api/compose/send", wire.encodeToString(Draft.serializer(), draft)).read(Sending.serializer())

    private suspend fun Browser.statusOf(id: String) =
        get("/api/compose/status").read(ListSerializer(SendStatus.serializer())).firstOrNull { it.id == id }

    @Test
    fun repliesGoOutAsPlainTextAndLandInSent() = server {
        val inbox = inbox()
        val mom = inbox.messages.first { it.subject == "Dinner Sunday?" }
        val started = start("reply", inbox, mom.uid)
        assertEquals("mom@example.org", started.draft.to)
        assertEquals("Re: Dinner Sunday?", started.draft.subject)
        assertTrue("wrote:\n> Are you coming for dinner on Sunday?" in started.draft.body, started.draft.body)
        assertTrue(started.draft.inReplyTo!!.startsWith("<"))

        val sending = send(started.draft.copy(body = "Yes! <b>This</b> stays text.\n" + started.draft.body))
        eventually("it's sent") { statusOf(sending.id)?.state == "sent" }

        val received = mail.receivedAt("example.org").single()
        val raw = mail.raw(received)
        assertTrue(received.isMimeType("text/plain"), received.contentType)
        assertFalse("text/html" in raw.lowercase(), raw)
        assertTrue("<b>This</b> stays text." in (received.content as String))
        assertEquals(started.draft.inReplyTo, received.getHeader("In-Reply-To").single())

        eventually("a copy is filed in Sent") { subjects("Sent Messages") == listOf("Re: Dinner Sunday?") }
        eventually("the original is marked answered") { inbox().messages.first { it.uid == mom.uid }.answered }
    }

    @Test
    fun undoSendKeepsItFromLeaving() = server(undoSendMs = 2_000) {
        val sending = send(Draft(to = "friend@example.org", subject = "Oops", body = "Wrong person"))
        assertEquals("waiting", statusOf(sending.id)?.state)
        assertEquals(UndoReply(true), post("/api/compose/undo", """{"id":"${sending.id}"}""").read(UndoReply.serializer()))
        kotlinx.coroutines.delay(2_500)
        assertEquals(emptyList(), mail.receivedAt("example.org"))
        assertEquals(null, statusOf(sending.id))
    }

    @Test
    fun bccStaysHidden() = server {
        val sending = send(Draft(to = "a@example.org", bcc = "secret@example.net", subject = "Party", body = "Shh"))
        eventually("it's sent") { statusOf(sending.id)?.state == "sent" }
        // One copy each for the To and Bcc recipients...
        val delivered = mail.received()
        assertEquals(2, delivered.size)
        // ...and none of them says who else got it secretly, not even the Bcc recipient's own.
        for (copy in delivered) assertFalse("secret@example.net" in mail.raw(copy), mail.raw(copy))
    }

    @Test
    fun forwardsCarryTheAttachmentsAndStillNoHtmlBody() = server {
        val inbox = inbox()
        val order = inbox.messages.first { it.subject == "Your order has shipped" }
        val started = start("forward", inbox, order.uid)
        assertEquals("Fwd: Your order has shipped", started.draft.subject)
        assertTrue("---------- Forwarded message ----------" in started.draft.body)
        assertTrue(started.attachments.map { it.filename }.containsAll(listOf("receipt.pdf", "invoice.html")), started.attachments.toString())

        val sending = send(started.draft.copy(to = "friend@example.org"))
        eventually("it's sent") { statusOf(sending.id)?.state == "sent" }
        val received = mail.receivedAt("example.org").single()
        val parts = received.content as javax.mail.Multipart
        assertTrue(parts.getBodyPart(0).isMimeType("text/plain"), "the message itself is plain text")
        val files = (1 until parts.count).map { parts.getBodyPart(it) }
        assertTrue(files.all { it.disposition == javax.mail.Part.ATTACHMENT }, "everything else is an attached file")
        assertTrue(files.map { it.fileName }.containsAll(listOf("receipt.pdf", "invoice.html")))
    }

    @Test
    fun attachingAFile() = server {
        val upload = app.client.post("/api/compose/upload") {
            header(HttpHeaders.Origin, config.origin)
            token?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
            header("X-Filename", "Pep%20rally%20plan.txt")
            contentType(ContentType.Text.Plain)
            setBody("Bring pom-poms".toByteArray())
        }.read(UploadInfo.serializer())
        assertEquals("Pep rally plan.txt", upload.filename)
        val sending = send(Draft(to = "squad@example.org", subject = "Plan", body = "Attached.", attachments = listOf(upload.id)))
        eventually("it's sent") { statusOf(sending.id)?.state == "sent" }
        val parts = mail.receivedAt("example.org").single().content as javax.mail.Multipart
        assertEquals("Pep rally plan.txt", parts.getBodyPart(1).fileName)

        val huge = app.client.post("/api/compose/upload") {
            header(HttpHeaders.Origin, config.origin)
            token?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
            setBody(ByteArray(15 * 1024 * 1024))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        assertEquals(HttpStatusCode.BadRequest, post("/api/compose/send", wire.encodeToString(Draft.serializer(), Draft(to = "x@example.org", attachments = listOf("gone")))).status)
    }

    @Test
    fun draftsAreSavedEditedAndReplaced() = server {
        post("/api/compose/save", wire.encodeToString(Draft.serializer(), Draft(to = "mom@example.org", subject = "Thanksgiving", body = "Half-written"))).read(Done.serializer())
        assertEquals(listOf("Thanksgiving"), subjects("Drafts"))

        val drafts = page("Drafts")
        val editing = start("draft", drafts, drafts.messages[0].uid).draft
        assertEquals("mom@example.org", editing.to)
        assertEquals("Half-written", editing.body.trim())
        assertEquals(drafts.messages[0].uid, editing.replaces!!.uid)

        // Saving again replaces it rather than adding another.
        post("/api/compose/save", wire.encodeToString(Draft.serializer(), editing.copy(body = "Almost done"))).read(Done.serializer())
        assertEquals(listOf("Thanksgiving"), subjects("Drafts"))

        // Sending it removes the draft.
        val latest = page("Drafts")
        val final = start("draft", latest, latest.messages[0].uid).draft
        val sending = send(final.copy(body = "Done!"))
        eventually("it's sent and the draft is gone") { statusOf(sending.id)?.state == "sent" && subjects("Drafts").isEmpty() }
    }

    @Test
    fun nameAndSignatureAreSealedAndUsed() = server {
        post("/api/compose/identity", """{"name":"Kylie","signature":"xoxo, K"}""").read(Done.serializer())
        assertEquals(Identity("Kylie", "xoxo, K"), get("/api/compose/identity").read(Identity.serializer()))
        assertFalse("xoxo" in folder.root.resolve("account.json").readText(), "sealed on disk")

        val fresh = start("new").draft
        assertEquals("\n\n-- \nxoxo, K", fresh.body)
        val sending = send(fresh.copy(to = "mom@example.org", subject = "Hi", body = "Hi Mom" + fresh.body))
        eventually("it's sent") { statusOf(sending.id)?.state == "sent" }
        val received = mail.receivedAt("example.org").single()
        assertEquals("Kylie <kylie@icloud.com>", received.getHeader("From").single())
    }

    @Test
    fun badAddressesAreExplained() = server {
        val reply = post("/api/compose/send", wire.encodeToString(Draft.serializer(), Draft(to = "mom@", subject = "x")))
        assertEquals(HttpStatusCode.BadRequest, reply.status)
        assertTrue("To field" in reply.bodyAsText(), reply.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, post("/api/compose/send", wire.encodeToString(Draft.serializer(), Draft(subject = "x"))).status)
    }

    @Test
    fun repliesCanNotSmuggleHeaders() = server {
        val sending = send(Draft(to = "mom@example.org", subject = "Hi", body = "x", inReplyTo = "<a@b>\r\nBcc: spy@example.net"))
        eventually("it's sent") { statusOf(sending.id)?.state == "sent" }
        assertEquals(emptyList(), mail.receivedAt("example.net"))
        assertEquals(1, mail.raw(mail.receivedAt("example.org").single()).lines().count { it.startsWith("In-Reply-To:") })
    }

    @Test
    fun signingOutStillSendsWhatWasSent() = server(undoSendMs = 60_000) {
        send(Draft(to = "mom@example.org", subject = "Before I go", body = "Bye"))
        post("/api/signout", "{}")
        eventually("it goes out right away") { mail.receivedAt("example.org").size == 1 }
    }

    @Test
    fun contactsComeFromRecentMail() = server {
        val contacts = get("/api/compose/contacts").read(ListSerializer(Contact.serializer()))
        assertTrue(Contact("Mom", "mom@example.org") in contacts, contacts.toString())
        assertTrue(Contact("Shop", "orders@shop.example") in contacts, contacts.toString())
    }

    // Search. GreenMail only matches whole addresses for FROM and TO, where iCloud, like the IMAP
    // standard, matches any part, so these tests search subjects and bodies; SharedCodeTest checks
    // that from: and to: become the right IMAP terms.

    private suspend fun Browser.search(q: String, folder: String? = null) =
        get("/api/search?q=${q.encodeURLParameter()}" + (folder?.let { "&folder=${it.encodeURLParameter()}" } ?: ""))

    private suspend fun Browser.found(q: String, folder: String? = null) =
        search(q, folder).read(SearchResults.serializer()).hits.map { it.folder to it.summary.subject }

    @Test
    fun searchFindsWordsAnywhereAcrossFolders() = server {
        // In the body, as the start of a word.
        assertEquals(listOf("INBOX" to "Pep rally!!"), found("pom"))
        // Orders: the one in the inbox and the five filed in Receipts, newest first.
        val orders = found("order")
        assertEquals(6, orders.size, orders.toString())
        assertEquals("INBOX" to "Your order has shipped", orders.first())
        assertEquals(5, orders.count { it.first == "Receipts" })
        // Every word must match; a quoted phrase is kept together.
        assertEquals(listOf("Receipts" to "Order #3 confirmed"), found("order 3 confirmed").filter { it.second.startsWith("Order #3") })
        assertEquals(listOf("INBOX" to "Your order has shipped"), found("subject:\"order has\""))
        assertEquals(emptyList(), found("narwhal"))
    }

    @Test
    fun searchCanStayInOneFolder() = server {
        assertEquals(List(5) { "Receipts" }, found("order", "Receipts").map { it.first })
        assertEquals(HttpStatusCode.NotFound, search("shop", "Nope").status)
        assertEquals(HttpStatusCode.BadRequest, search("   ").status)
        assertEquals(HttpStatusCode.BadRequest, search("!!!").status)
    }

    @Test
    fun searchLeavesOutMessagesOnTheirWayOut() = server(undoMs = 60_000) {
        val inbox = inbox()
        val pep = inbox.messages.first { it.subject == "Pep rally!!" }
        remove(inbox, listOf(pep.uid), "delete").read(Removal.serializer())
        assertEquals(emptyList(), found("pom"))
    }

    @Test
    fun searchResultsCarryTheirFolder() = server {
        val hit = search("dinner").read(SearchResults.serializer()).hits.single()
        assertEquals("INBOX", hit.folder)
        assertEquals(inbox().validity, hit.validity)
        // Enough to open it.
        val detail = get("/api/message?folder=INBOX&validity=${hit.validity}&uid=${hit.summary.uid}").read(MessageDetail.serializer())
        assertEquals("Dinner Sunday?", detail.summary.subject)
    }

    // New mail while the page is open

    @Test
    fun newMailShowsUpOnTheNextCheck() = server {
        val before = inbox()
        assertEquals(4, folders()[0].unread)
        // The inbox is now open on the server's connection; mail arrives meanwhile.
        mail.deliver(mail.message("Madison <madison@example.org>", "Practice moved to 4pm") { setText("Don't be late!") })
        val after = app.client.get("/api/messages?folder=INBOX") {
            token?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
            header(BACKGROUND_HEADER, "1")
        }.read(MessagePage.serializer())
        assertEquals("Practice moved to 4pm", after.messages.first().subject)
        assertEquals(before.total + 1, after.total)
        assertEquals(5, folders()[0].unread)
    }
}
