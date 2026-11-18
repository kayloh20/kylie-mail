package com.kayloh20.kyliemail.relay

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/** The relay end to end: its HTTP API, a real IMAP server, and a stand-in for Firebase. */
class RelayTest {
    private lateinit var mail: GreenMail
    private lateinit var dataDir: Path
    private val user = "kylie@icloud.com"
    private val password = "abcd-efgh-ijkl-mnop"
    private val setupCode = "pom-poms-and-glitter-2026"
    private val session = Session.getInstance(Properties())
    private val http = HttpClient.newHttpClient()
    private val json = Json

    /** Every push "sent", by token. Tokens in [goneTokens] behave like uninstalled apps. */
    private val pushes = CopyOnWriteArrayList<String>()
    private val goneTokens = mutableSetOf<String>()
    private val pusher = Pusher { token ->
        pushes += token
        if (token in goneTokens) PushResult.TOKEN_GONE else PushResult.SENT
    }

    private val fast = WatchTiming(
        keepAliveMs = 60_000, keepAliveAnswerMs = 5_000, idleReadTimeoutMs = 60_000,
        firstBackoffMs = 200, maxBackoffMs = 1_000, debounceMs = 200, pollMs = 300,
    )
    private val running = mutableListOf<Pair<Relay, Http>>()

    @Before fun setUp() {
        mail = GreenMail(ServerSetupTest.SMTP_IMAP).apply { start() }
        mail.setUser(user, user, password)
        dataDir = Files.createTempDirectory("relay")
    }

    @After fun tearDown() {
        running.forEach { (relay, server) -> server.stop(); relay.shutdown() }
        mail.stop()
    }

    /** A relay as it would run, sharing [dataDir] across "restarts". */
    private fun startRelay(): Http {
        val relay = Relay(
            Store(dataDir.resolve("accounts.json")),
            Imap(ImapServer("127.0.0.1", ServerSetupTest.IMAP.port, tls = false)),
            pusher, setupCode, fast, log = {},
        )
        val server = Http(relay, 0, "127.0.0.1").also { it.start() }
        running += relay to server
        return server
    }

    private fun restart(): Http {
        running.forEach { (relay, server) -> server.stop(); relay.shutdown() }
        running.clear()
        return startRelay()
    }

    private fun post(server: Http, path: String, body: String, auth: String? = null): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
            .header("Content-Type", "application/json")
            .apply { auth?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun register(server: Http, token: String, code: String = setupCode, pw: String = password) = post(
        server, "/v1/register",
        """{"setupCode":"$code","username":"$user","password":"$pw","device":{"token":"$token","name":"Pixel"}}""",
    )

    /** Registers and returns "accountId.key", the device's credentials. */
    private fun registered(server: Http, token: String): String {
        val (status, body) = register(server, token)
        assertEquals(body, 200, status)
        val reply = json.parseToJsonElement(body).jsonObject
        return reply["accountId"]!!.jsonPrimitive.content + "." + reply["key"]!!.jsonPrimitive.content
    }

    private fun checkIn(server: Http, auth: String, token: String) =
        post(server, "/v1/checkin", """{"device":{"token":"$token","name":"Pixel"}}""", auth)

    private fun deliver(subject: String) {
        mail.managers.userManager.getUser(user).deliver(
            MimeMessage(session).apply {
                setFrom(InternetAddress("madison@example.com"))
                setRecipients(Message.RecipientType.TO, user)
                setSubject(subject)
                setText("Hi!")
            },
        )
    }

    private fun waitFor(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun newMailWakesEveryDevice() {
        val server = startRelay()
        val auth = registered(server, "phone")
        checkIn(server, auth, "laptop")
        Thread.sleep(500)
        pushes.clear()

        deliver("Practice moved to 4pm")

        assertTrue("both devices pushed: $pushes", waitFor { pushes.toSet() == setOf("phone", "laptop") })
    }

    @Test
    fun aBurstOfMailIsOnePushPerDevice() {
        val server = startRelay()
        registered(server, "phone")
        Thread.sleep(500)
        pushes.clear()

        repeat(5) { deliver("Newsletter $it") }

        assertTrue(waitFor { pushes.isNotEmpty() })
        Thread.sleep(800)
        assertEquals(listOf("phone"), pushes.toList())
    }

    @Test
    fun theStoredFileNeverHoldsThePasswordOrKey() {
        val server = startRelay()
        val auth = registered(server, "phone")
        val stored = Files.readString(dataDir.resolve("accounts.json"))
        assertFalse(stored.contains(password))
        assertFalse(stored.contains(auth.substringAfter('.')))
    }

    @Test
    fun theSetupCodeAndPasswordAreChecked() {
        val server = startRelay()
        assertEquals(403, register(server, "phone", code = "not-the-code").first)
        assertEquals(401, register(server, "phone", pw = "wrong-password").first)
        assertFalse(Files.exists(dataDir.resolve("accounts.json")))
    }

    @Test
    fun afterARestartNothingIsWatchedUntilADeviceChecksInThenMissedMailIsPushed() {
        var server = startRelay()
        val auth = registered(server, "phone")
        Thread.sleep(500)

        server = restart()
        deliver("Arrived while the relay was locked")
        Thread.sleep(1_000)
        pushes.clear()
        Thread.sleep(500)
        assertTrue("locked relay can't push", pushes.isEmpty())

        assertEquals(200, checkIn(server, auth, "phone").first)
        assertTrue("caught up after unlocking", waitFor { "phone" in pushes })
    }

    @Test
    fun aWrongKeyIsTurnedAwayAndADeviceCanSimplyRegisterAgain() {
        var server = startRelay()
        val oldAuth = registered(server, "phone")
        assertEquals(401, checkIn(server, oldAuth.substringBefore('.') + ".AAAA", "phone").first)

        // After a restart, a second device registering starts a fresh key; the first gets turned
        // away and registers again, ending up with the same key as the second.
        server = restart()
        val laptopAuth = registered(server, "laptop")
        assertEquals(401, checkIn(server, oldAuth, "phone").first)
        assertEquals(laptopAuth, registered(server, "phone"))
    }

    @Test
    fun uninstalledAppsStopGettingPushes() {
        val server = startRelay()
        val auth = registered(server, "phone")
        checkIn(server, auth, "old-tablet")
        goneTokens += "old-tablet"
        Thread.sleep(500)

        deliver("Hello")
        assertTrue(waitFor { "old-tablet" in pushes })
        assertTrue(waitFor { !Files.readString(dataDir.resolve("accounts.json")).contains("old-tablet") })
    }

    @Test
    fun removingTheLastDeviceDeletesTheAccount() {
        val server = startRelay()
        val auth = registered(server, "phone")
        assertEquals(200, post(server, "/v1/unregister", """{"token":"phone"}""", auth).first)
        assertFalse(Files.readString(dataDir.resolve("accounts.json")).contains(user))
        assertEquals(401, checkIn(server, auth, "phone").first)
    }

    @Test
    fun junkRequestsGetPlainErrors() {
        val server = startRelay()
        assertEquals(400, post(server, "/v1/register", "{not json").first)
        assertEquals(401, checkIn(server, "nonsense", "phone").first)
        assertEquals(404, post(server, "/v1/nope", "{}").first)
    }
}
