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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * A connection that goes quiet without closing (a network change, a router dropping it) must not
 * leave sync stuck: the next sync gets new mail in seconds, not minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StuckConnectionTest {
    private lateinit var server: GreenMail
    private lateinit var proxy: SilentProxy
    private lateinit var db: MailDatabase
    private lateinit var engine: SyncEngine
    private val user = "kylie@icloud.com"
    private val session = Session.getInstance(Properties())
    /** The engine's clock, so a test can pretend the phone sat in a pocket for a while. */
    private var now = 1_000_000L

    @Before fun setUp() {
        server = GreenMail(ServerSetupTest.SMTP_IMAP).apply { start() }
        server.setUser(user, user, "pw")
        deliver("Before the network changed")
        proxy = SilentProxy(ServerSetupTest.IMAP.port)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MailDatabase::class.java)
            .addCallback(MailDatabase.searchTriggers)
            .allowMainThreadQueries()
            .build()
        val accounts = object : AccountSource {
            override val account: StateFlow<Account?> = MutableStateFlow(Account(user, user, "pw"))
        }
        val connector = ImapConnector(ServerConfig("127.0.0.1", proxy.port, "127.0.0.1", ServerSetupTest.SMTP.port, tls = false))
        engine = SyncEngine(db, accounts, connector, InlineImageStore(Files.createTempDirectory("inline").toFile())) { now }
    }

    @After fun tearDown() {
        db.close()
        proxy.close()
        server.stop()
    }

    private fun deliver(subject: String) {
        server.getManagers().userManager.getUser(user).deliver(
            MimeMessage(session).apply {
                setFrom(InternetAddress("madison@example.com"))
                setRecipients(Message.RecipientType.TO, user)
                setSubject(subject)
                setText("Hi!")
            },
        )
    }

    private fun inboxSubjects(): List<String> = runBlocking {
        val inbox = db.folders().getByRole(FolderRole.INBOX)!!
        db.messages().observeSummaries(inbox.id).first().map { it.subject }
    }

    private fun timedSync(): Pair<SyncOutcome, Long> {
        val start = System.currentTimeMillis()
        val outcome = runBlocking { engine.sync() }
        return outcome to System.currentTimeMillis() - start
    }

    @Test
    fun aNetworkChangeGetsAFreshConnectionRightAway() {
        assertEquals(SyncOutcome.Success, timedSync().first)
        proxy.goSilent()
        deliver("After the network changed")

        engine.startOver()
        val (outcome, took) = timedSync()

        assertEquals(SyncOutcome.Success, outcome)
        assertTrue("took ${took}ms", took < 5_000)
        assertTrue(inboxSubjects().contains("After the network changed"))
    }

    @Test
    fun startingOverUnsticksASyncAlreadyWaitingOnAQuietConnection() {
        assertEquals(SyncOutcome.Success, timedSync().first)
        proxy.goSilent()
        deliver("While it was stuck")

        // A sync is already stuck waiting on the quiet connection, the way it is when refresh is pressed.
        val stuck = java.util.concurrent.CompletableFuture.supplyAsync { timedSync() }
        Thread.sleep(2_000)
        assertTrue("should still be waiting", !stuck.isDone)

        val pressed = System.currentTimeMillis()
        engine.startOver()
        val (outcome, _) = stuck.get(10, java.util.concurrent.TimeUnit.SECONDS)
        val freedAfter = System.currentTimeMillis() - pressed

        assertEquals(SyncOutcome.Success, outcome)
        assertTrue("freed after ${freedAfter}ms", freedAfter < 5_000)
        assertTrue(inboxSubjects().contains("While it was stuck"))
    }

    @Test
    fun afterSittingUnusedItStartsAFreshConnectionInsteadOfWaitingOnTheOldOne() {
        assertEquals(SyncOutcome.Success, timedSync().first)
        proxy.goSilent()
        deliver("After five minutes in a pocket")
        now += 5 * 60_000L

        val (outcome, took) = timedSync()

        assertEquals(SyncOutcome.Success, outcome)
        assertTrue("took ${took}ms", took < 5_000)
        assertTrue(inboxSubjects().contains("After five minutes in a pocket"))
    }

    @Test
    fun evenUnnoticedAQuietConnectionIsGivenUpOnWithinTheReadTimeout() {
        assertEquals(SyncOutcome.Success, timedSync().first)
        proxy.goSilent()
        deliver("After the router forgot us")

        val (outcome, took) = timedSync()

        assertEquals(SyncOutcome.Success, outcome)
        assertTrue("took ${took}ms", took < ImapConnector.READ_TIMEOUT_MS + 5_000)
        assertTrue(inboxSubjects().contains("After the router forgot us"))
    }
}
