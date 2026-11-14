package com.kayloh20.kyliemail.data.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import com.kayloh20.kyliemail.data.account.Account
import com.kayloh20.kyliemail.data.account.AccountSource
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.kayloh20.kyliemail.data.mail.InlineImageStore
import com.kayloh20.kyliemail.data.mail.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.Properties
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Refresh against a server with real-world lag and plenty of folders: the spinner should stop once
 * the open folder and inbox are up to date, not after counting every folder's mail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RefreshSpeedTest {
    private lateinit var server: GreenMail
    private lateinit var proxy: SilentProxy
    private lateinit var db: MailDatabase
    private lateinit var engine: SyncEngine
    private val user = "kylie@icloud.com"
    private val session = Session.getInstance(Properties())
    private val folderCount = 40

    @Before fun setUp() {
        server = GreenMail(ServerSetupTest.SMTP_IMAP).apply { start() }
        server.setUser(user, user, "pw")
        val store = session.getStore("imap").apply { connect("127.0.0.1", ServerSetupTest.IMAP.port, user, "pw") }
        for (i in 1..folderCount) {
            val folder = store.getFolder("Project $i").apply { create(Folder.HOLDS_MESSAGES) }
            folder.appendMessages(arrayOf(message("Note $i"), message("Unread $i")))
            folder.open(Folder.READ_WRITE)
            folder.getMessage(1).setFlag(Flags.Flag.SEEN, true)
            folder.close(false)
        }
        store.close()
        proxy = SilentProxy(ServerSetupTest.IMAP.port)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MailDatabase::class.java)
            .addCallback(MailDatabase.searchTriggers)
            .allowMainThreadQueries()
            .build()
        val accounts = object : AccountSource {
            override val account: StateFlow<Account?> = MutableStateFlow(Account(user, user, "pw"))
        }
        val connector = ImapConnector(ServerConfig("127.0.0.1", proxy.port, "127.0.0.1", ServerSetupTest.SMTP.port, tls = false))
        engine = SyncEngine(db, accounts, connector, InlineImageStore(Files.createTempDirectory("inline").toFile()))
    }

    @After fun tearDown() {
        db.close()
        proxy.close()
        server.stop()
    }

    private fun message(subject: String) = MimeMessage(session).apply {
        setFrom(InternetAddress("madison@example.com"))
        setRecipients(Message.RecipientType.TO, user)
        setSubject(subject)
        setText("Hi!")
    }

    @Test
    fun theSpinnerStopsWhenTheMailIsInNotAfterEveryFolderIsCounted() = runBlocking {
        assertEquals(SyncOutcome.Success, engine.sync())
        // About 100ms there and back, like iCloud from a phone.
        proxy.latencyMs = 50

        engine.startOver()
        var spinnerStoppedAfter = -1L
        val start = System.currentTimeMillis()
        val watch = launch(Dispatchers.IO) {
            engine.state.first { it.running }
            engine.state.first { !it.running }
            spinnerStoppedAfter = System.currentTimeMillis() - start
        }
        assertEquals(SyncOutcome.Success, engine.sync())
        watch.join()
        val total = System.currentTimeMillis() - start

        println("spinner stopped after ${spinnerStoppedAfter}ms, sync finished after ${total}ms")
        assertTrue("spinner stopped after ${spinnerStoppedAfter}ms", spinnerStoppedAfter < 3_000)
        // Folder counts still arrive, just after the spinner, one question per folder.
        val counted = db.folders().getAll().filter { it.fullName.startsWith("Project") }
        assertEquals(folderCount, counted.size)
        assertTrue(counted.all { it.totalCount == 2 && it.unreadCount == 1 })
    }
}
