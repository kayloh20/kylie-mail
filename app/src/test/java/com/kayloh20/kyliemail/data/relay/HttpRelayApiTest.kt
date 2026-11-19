package com.kayloh20.kyliemail.data.relay

import android.app.Application
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/** The requests match what the relay expects (relay/src/.../Http.kt), and its answers are read right. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HttpRelayApiTest {
    private lateinit var server: HttpServer
    private val requests = mutableListOf<Triple<String, String?, JSONObject>>()
    private var status = 200
    private var reply = "{}"

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = JSONObject(exchange.requestBody.readBytes().decodeToString())
                requests += Triple(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), body)
                val bytes = reply.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @After fun tearDown() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val api = HttpRelayApi(timeoutMs = 5_000)

    @Test
    fun registerSendsWhatTheRelayExpects() = runBlocking {
        reply = """{"accountId":"acct","key":"secret"}"""
        val result = api.register(base, "code", "kylie@icloud.com", "pw", "tok", "Pixel")
        assertEquals(RelayReply.Ok(RelayCredentials("acct", "secret")), result)
        val (path, auth, body) = requests.single()
        assertEquals("/v1/register", path)
        assertNull(auth)
        assertEquals("code", body.getString("setupCode"))
        assertEquals("kylie@icloud.com", body.getString("username"))
        assertEquals("pw", body.getString("password"))
        assertEquals("tok", body.getJSONObject("device").getString("token"))
        assertEquals("Pixel", body.getJSONObject("device").getString("name"))
    }

    @Test
    fun checkInAndUnregisterUseTheDevicesKey() = runBlocking {
        reply = """{"status":"watching"}"""
        assertEquals(RelayReply.Ok("watching"), api.checkIn(base, RelayCredentials("acct", "secret"), "tok", "Pixel"))
        assertEquals("/v1/checkin" to "Bearer acct.secret", requests.last().first to requests.last().second)

        reply = """{"status":"removed"}"""
        assertEquals(RelayReply.Ok(Unit), api.unregister(base, RelayCredentials("acct", "secret"), "tok"))
        assertEquals("tok", requests.last().third.getString("token"))
    }

    @Test
    fun errorsMeanWhatTheRelayMeans() = runBlocking {
        reply = """{"error":"x"}"""
        status = 403
        assertEquals(RelayReply.BadSetupCode, api.register(base, "c", "u", "p", "t", "n"))
        status = 401
        assertEquals(RelayReply.PasswordRejected, api.register(base, "c", "u", "p", "t", "n"))
        assertEquals(RelayReply.UnknownKey, api.checkIn(base, RelayCredentials("a", "k"), "t", "n"))
        server.stop(0)
        assertTrue(api.checkIn(base, RelayCredentials("a", "k"), "t", "n") is RelayReply.Failed)
    }

    @Test
    fun addressesAreHttpsOnly() {
        assertEquals("https://mail-push.example.org", RelayApi.normalize(" mail-push.example.org/ "))
        assertEquals("https://mail-push.example.org", RelayApi.normalize("https://mail-push.example.org"))
        assertNull(RelayApi.normalize("http://mail-push.example.org"))
        assertNull(RelayApi.normalize("not a server"))
        assertNull(RelayApi.normalize("localhostish"))
        assertEquals("http://127.0.0.1:8080", RelayApi.normalize("http://127.0.0.1:8080"))
    }
}
