package com.kayloh20.kyliemail.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import com.kayloh20.kyliemail.data.account.Account
import com.kayloh20.kyliemail.data.account.AccountSource
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.kayloh20.kyliemail.data.mail.InlineImageStore
import com.kayloh20.kyliemail.data.mail.ServerConfig
import com.kayloh20.kyliemail.data.search.SearchQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.Date
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/** The real sync engine against a real (local) IMAP server holding hundreds of emails. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class KeepAllMailTest {
    private lateinit var server: GreenMail
    private lateinit var db: MailDatabase
    private lateinit var engine: SyncEngine
    private val user = "kylie@icloud.com"
    private val session = Session.getInstance(Properties())
    private val inboxCount = 650

    @Before fun setUp() {
        server = GreenMail(ServerSetupTest.SMTP_IMAP).apply { start() }
        val account = server.setUser(user, user, "pw")
        for (i in 1..inboxCount) {
            account.deliver(plain("Newsletter $i", if (i == 1) "The very first email mentions a narwhal." else "Issue number $i.", i))
        }
        // A folder with an attachment, to prove only text is downloaded.
        imap { store ->
            val receipts = store.getFolder("Receipts").apply { create(Folder.HOLDS_MESSAGES) }
            receipts.appendMessages(arrayOf(withAttachment()))
        }

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MailDatabase::class.java)
            .addCallback(MailDatabase.searchTriggers)
            .build()
        val accounts = object : AccountSource {
            override val account: StateFlow<Account?> = MutableStateFlow(Account(user, user, "pw"))
        }
        val connector = ImapConnector(ServerConfig("127.0.0.1", ServerSetupTest.IMAP.port, "127.0.0.1", ServerSetupTest.SMTP.port, tls = false))
        engine = SyncEngine(db, accounts, connector, InlineImageStore(Files.createTempDirectory("inline").toFile()))
    }

    @After fun tearDown() {
        db.close()
        server.stop()
    }

    private fun plain(subject: String, body: String, day: Int) = MimeMessage(session).apply {
        setFrom(InternetAddress("news@example.com"))
        setRecipients(Message.RecipientType.TO, user)
        setSubject(subject)
        sentDate = Date(1_700_000_000_000L + day * 86_400_000L)
        setText(body)
    }

    private val attachmentBytes = ByteArray(300_000) { (it % 251).toByte() }

    private fun withAttachment() = MimeMessage(session).apply {
        setFrom(InternetAddress("orders@shop.example"))
        setRecipients(Message.RecipientType.TO, user)
        setSubject("Your invoice")
        setContent(
            MimeMultipart("mixed").apply {
                addBodyPart(MimeBodyPart().apply { setText("Invoice attached. Thanks for buying the flamingo lamp.") })
                addBodyPart(MimeBodyPart().apply {
                    setContent(attachmentBytes, "application/octet-stream")
                    fileName = "invoice.bin"
                })
            },
        )
    }

    private fun <T> imap(block: (javax.mail.Store) -> T): T {
        val store = session.getStore("imap")
        store.connect("127.0.0.1", ServerSetupTest.IMAP.port, user, "pw")
        return try { block(store) } finally { store.close() }
    }

    private fun searchable() = runBlocking { db.folders().getAll().filter { it.selectable && it.role != FolderRole.JUNK } }

    private fun runToCompletion(): Int = runBlocking {
        var passes = 0
        while (engine.keepAllPass(searchable(), Long.MAX_VALUE, reconcileDue = { true }, onReconciled = {})) passes++
        passes + 1
    }

    private fun search(text: String) = runBlocking {
        db.messages().search(SearchQuery.parse(text).fts!!, null, 1000).first().map { it.subject }
    }

    @Test fun normalSyncKeepsOnlyTheNewestWhileKeepAllGetsEverything() = runBlocking {
        assertEquals(SyncOutcome.Success, engine.sync())
        val inbox = db.folders().getByRole(FolderRole.INBOX)!!
        assertEquals("a normal sync caches the newest 200", 200, db.messages().allUids(inbox.id).size)
        assertTrue("the first email isn't searchable yet", search("narwhal").isEmpty())

        runToCompletion()

        assertEquals("every header, across several batches", inboxCount, db.messages().allUids(inbox.id).size)
        assertEquals(0, db.messages().pendingTextCount(listOf(inbox.id)))
        assertEquals(listOf("Newsletter 1"), search("narwhal"))
        assertEquals(listOf("Your invoice"), search("flamingo"))
    }

    @Test fun nothingIsMarkedReadAndAttachmentsAreNeverDownloaded() = runBlocking {
        engine.sync()
        runToCompletion()
        val unreadOnServer = imap { store ->
            listOf("INBOX", "Receipts").sumOf { name ->
                store.getFolder(name).run { open(Folder.READ_ONLY); unreadMessageCount.also { close(false) } }
            }
        }
        assertEquals("downloading for search must not mark anything read", inboxCount + 1, unreadOnServer)

        val receipts = db.folders().getAll().single { it.fullName == "Receipts" }
        val invoice = db.messages().needingText(receipts.id, 10).let { assertTrue(it.isEmpty()); db.messages().allUids(receipts.id) }
        assertEquals(1, invoice.size)
        val stored = db.messages().search("flamingo*", null, 10).first().single()
        val text = db.messages().getById(stored.id)!!.bodyText!!
        assertTrue(text.contains("flamingo lamp"))
        assertTrue("only the file's name, never its contents", text.length < 1_000)
        assertFalse("the full body still downloads when opened", db.messages().getById(stored.id)!!.bodyLoaded)
    }

    @Test fun mailDeletedElsewhereLeavesTheDevice() = runBlocking {
        engine.sync()
        runToCompletion()
        imap { store ->
            store.getFolder("INBOX").run {
                open(Folder.READ_WRITE)
                getMessage(1).setFlag(Flags.Flag.DELETED, true)
                close(true)
            }
        }
        runToCompletion()
        assertTrue(search("narwhal").isEmpty())
        assertEquals(inboxCount - 1, db.messages().allUids(db.folders().getByRole(FolderRole.INBOX)!!.id).size)
    }

    @Test fun aPassStopsWhenTimeIsUpAndResumesLater() = runBlocking {
        engine.sync()
        val moreToDo = engine.keepAllPass(searchable(), deadline = 0, reconcileDue = { false }, onReconciled = {})
        assertTrue("out of time means more to do", moreToDo)
        runToCompletion()
        assertEquals(inboxCount, db.messages().allUids(db.folders().getByRole(FolderRole.INBOX)!!.id).size)
    }
}
