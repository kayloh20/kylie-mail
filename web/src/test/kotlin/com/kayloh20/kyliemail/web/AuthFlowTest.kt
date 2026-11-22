package com.kayloh20.kyliemail.web

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthFlowTest {
    @get:Rule val folder = TemporaryFolder()

    private val config = WebConfig(origin = "https://mail.example.org", dataDir = "unused")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var clock = 1_000_000L

    private val mailLogin = MailLogin { email, password ->
        when {
            email == "me@icloud.com" && password == "good-app-password" -> MailLoginResult.Accepted("me")
            password == "offline" -> MailLoginResult.Unreachable
            else -> MailLoginResult.Rejected
        }
    }

    private lateinit var services: Services

    private fun server(block: suspend Api.() -> Unit) = testApplication {
        val dataDir = folder.root.toPath()
        services = Services(
            config = config,
            vault = Vault(AccountStore(dataDir.resolve("account.json")), now = { clock }),
            passkeys = Passkeys(config),
            sessions = Sessions(now = { clock }),
            setupCode = SetupCode(dataDir.resolve("setup-code")),
            mailLogin = mailLogin,
            now = { clock },
        )
        application { webModule(config, services) }
        Api(this, client).block()
    }

    /** A browser on the site: sends the site's Origin and remembers its session cookie. */
    inner class Api(val app: ApplicationTestBuilder, val client: HttpClient) {
        var cookie: String? = null
        val passkey = SoftwarePasskey(config.origin, config.host)

        suspend fun post(path: String, body: String = "{}"): HttpResponse = client.post(path) {
            header(HttpHeaders.Origin, config.origin)
            cookie?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.also(::remember)

        suspend fun get(path: String, background: Boolean = false): HttpResponse = client.get(path) {
            cookie?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
            if (background) header(BACKGROUND_HEADER, "1")
        }

        suspend fun delete(path: String): HttpResponse = client.delete(path) {
            header(HttpHeaders.Origin, config.origin)
            cookie?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") }
        }

        private fun remember(response: HttpResponse) {
            val set = response.headers.getAll(HttpHeaders.SetCookie)?.firstOrNull { it.startsWith("$SESSION_COOKIE=") } ?: return
            cookie = set.substringAfter("=").substringBefore(";").ifEmpty { null }
        }

        suspend fun <T> HttpResponse.read(serializer: KSerializer<T>): T {
            assertEquals(HttpStatusCode.OK, status, bodyAsText())
            return json.decodeFromString(serializer, bodyAsText())
        }

        fun <T> encode(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)

        suspend fun setUp(code: String = services.setupCode.current(), with: SoftwarePasskey = passkey): CodesReply {
            val start = post("/api/setup/start", """{"setupCode":"$code","email":"me@icloud.com","password":"good-app-password","passkeyName":"Laptop"}""")
                .read(CreateReply.serializer())
            val prf = with.prf(start.options.extensions.prf.eval.first)
            val finish = CreateFinishBody(start.pendingId, with.create(start.options), prf)
            return post("/api/setup/finish", encode(CreateFinishBody.serializer(), finish)).read(CodesReply.serializer())
        }

        suspend fun signIn(with: SoftwarePasskey = passkey, prfOverride: String? = null): HttpResponse {
            val start = post("/api/signin/start").read(GetReply.serializer())
            // A passkey the server doesn't know gets no salt, so it can only guess.
            val salt = start.options.extensions.prf.evalByCredential[Crypto.b64url(with.id)]?.first ?: Crypto.b64url(Crypto.randomBytes(32))
            val finish = GetFinishBody(start.pendingId, with.get(start.options), prfOverride ?: with.prf(salt))
            return post("/api/signin/finish", encode(GetFinishBody.serializer(), finish))
        }

        suspend fun me(): HttpResponse = get("/api/me")
    }

    @Test
    fun setUpSignOutAndSignInWithAPasskey() = server {
        assertEquals(StateReply(hasAccount = false, signedIn = false), get("/api/state").read(StateReply.serializer()))

        val codes = setUp()
        assertEquals(RecoveryCodes.COUNT, codes.recoveryCodes.size)
        assertFalse(Files.exists(folder.root.toPath().resolve("setup-code")), "the setup code is gone once used")
        val me = me().read(MeReply.serializer())
        assertEquals("me@icloud.com", me.email)
        assertEquals(listOf("Laptop"), me.passkeys.map { it.name })
        assertFalse(me.signedInWithRecoveryCode)
        assertEquals("good-app-password", services.vault.password(services.sessions.get(cookie)!!.accountKey))

        assertEquals(HttpStatusCode.OK, post("/api/signout").status)
        assertEquals(null, cookie)
        assertEquals(HttpStatusCode.Unauthorized, me().status)

        assertEquals(HttpStatusCode.OK, signIn().status)
        assertEquals(HttpStatusCode.OK, me().status)
        assertEquals(StateReply(hasAccount = true, signedIn = true), get("/api/state").read(StateReply.serializer()))
    }

    @Test
    fun sessionCookieIsLockedDown() = server {
        val start = post("/api/setup/start", """{"setupCode":"${services.setupCode.current()}","email":"me@icloud.com","password":"good-app-password"}""")
            .read(CreateReply.serializer())
        val finish = CreateFinishBody(start.pendingId, passkey.create(start.options), passkey.prf(start.options.extensions.prf.eval.first))
        val response = post("/api/setup/finish", encode(CreateFinishBody.serializer(), finish))
        val set = response.headers[HttpHeaders.SetCookie]!!
        for (part in listOf("__Host-kylie-session=", "Path=/", "Secure", "HttpOnly", "SameSite=Strict")) assertTrue(part in set, set)
    }

    @Test
    fun setupNeedsTheCodeAndAWorkingICloudLogin() = server {
        val code = services.setupCode.current()
        fun body(code: String, password: String) = """{"setupCode":"$code","email":"me@icloud.com","password":"$password"}"""
        assertEquals(HttpStatusCode.Forbidden, post("/api/setup/start", body("wrong-code", "good-app-password")).status)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/setup/start", body(code, "nope")).status)
        assertEquals(HttpStatusCode.BadGateway, post("/api/setup/start", body(code, "offline")).status)

        setUp()
        cookie = null
        assertEquals(HttpStatusCode.Conflict, post("/api/setup/start", body(code, "good-app-password")).status, "only once")
    }

    @Test
    fun setupCodeGuessingIsSlowedDown() = server {
        repeat(10) { post("/api/setup/start", """{"setupCode":"guess$it","email":"a","password":"b"}""") }
        assertEquals(HttpStatusCode.TooManyRequests, post("/api/setup/start", """{"setupCode":"x","email":"a","password":"b"}""").status)
    }

    @Test
    fun aPasskeyMustGiveTheRightSecret() = server {
        setUp()
        cookie = null
        assertEquals(HttpStatusCode.Forbidden, signIn(prfOverride = Crypto.b64url(Crypto.randomBytes(32))).status)
        assertEquals(HttpStatusCode.BadRequest, signIn(prfOverride = Crypto.b64url(ByteArray(8))).status, "too short")
        assertEquals(null, cookie)
    }

    @Test
    fun strangersAndReplaysAreTurnedAway() = server {
        setUp()
        cookie = null

        // A passkey that was never registered here.
        assertEquals(HttpStatusCode.Forbidden, signIn(with = SoftwarePasskey(config.origin, config.host)).status)

        // A sign-in made for a different site.
        val phished = SoftwarePasskey("https://evil.example", config.host)
        val start = post("/api/signin/start").read(GetReply.serializer())
        val salt = start.options.extensions.prf.evalByCredential.values.first().first
        val real = passkey.get(start.options)
        val forged = phished.get(start.options).let { real.copy(response = it.response) }
        assertEquals(HttpStatusCode.BadRequest, post("/api/signin/finish", encode(GetFinishBody.serializer(), GetFinishBody(start.pendingId, forged, passkey.prf(salt)))).status)

        // Answering the wrong challenge.
        val again = post("/api/signin/start").read(GetReply.serializer())
        val wrongChallenge = passkey.get(again.options, challenge = Crypto.b64url(Crypto.randomBytes(32)))
        assertEquals(HttpStatusCode.BadRequest, post("/api/signin/finish", encode(GetFinishBody.serializer(), GetFinishBody(again.pendingId, wrongChallenge, passkey.prf(salt)))).status)

        // Reusing a challenge that was already answered.
        val once = post("/api/signin/start").read(GetReply.serializer())
        val body = encode(GetFinishBody.serializer(), GetFinishBody(once.pendingId, passkey.get(once.options), passkey.prf(salt)))
        assertEquals(HttpStatusCode.OK, post("/api/signin/finish", body).status)
        cookie = null
        assertEquals(HttpStatusCode.BadRequest, post("/api/signin/finish", body).status)
        assertEquals(null, cookie)
    }

    @Test
    fun recoveryCodeSignsInOnceAndANewPasskeyCanBeAdded() = server {
        val codes = setUp().recoveryCodes
        post("/api/signout")

        assertEquals(RecoverReply(RecoveryCodes.COUNT - 1), post("/api/recover", """{"code":"${codes[0]}"}""").read(RecoverReply.serializer()))
        assertTrue(me().read(MeReply.serializer()).signedInWithRecoveryCode)
        post("/api/signout")
        assertEquals(HttpStatusCode.Forbidden, post("/api/recover", """{"code":"${codes[0]}"}""").status, "used up")
        assertEquals(HttpStatusCode.OK, post("/api/recover", """{"code":"${codes[1]}"}""").status)

        // Add a second passkey, then sign in with it.
        val phone = SoftwarePasskey(config.origin, config.host)
        val start = post("/api/passkeys/start", """{"name":"Phone"}""").read(CreateReply.serializer())
        assertEquals(listOf(Crypto.b64url(passkey.id)), start.options.excludeCredentials.map { it.id })
        val finish = CreateFinishBody(start.pendingId, phone.create(start.options), phone.prf(start.options.extensions.prf.eval.first))
        assertEquals(HttpStatusCode.OK, post("/api/passkeys/finish", encode(CreateFinishBody.serializer(), finish)).status)
        post("/api/signout")
        assertEquals(HttpStatusCode.OK, signIn(with = phone).status)
        assertEquals(listOf("Laptop", "Phone"), me().read(MeReply.serializer()).passkeys.map { it.name })

        // Remove the first; the last one can't go.
        assertEquals(HttpStatusCode.OK, delete("/api/passkeys/${Crypto.b64url(passkey.id)}").status)
        assertEquals(HttpStatusCode.Conflict, delete("/api/passkeys/${Crypto.b64url(phone.id)}").status)

        // New recovery codes replace the old ones.
        val fresh = post("/api/recovery-codes").read(CodesReply.serializer()).recoveryCodes
        post("/api/signout")
        assertEquals(HttpStatusCode.Forbidden, post("/api/recover", """{"code":"${codes[2]}"}""").status)
        assertEquals(HttpStatusCode.OK, post("/api/recover", """{"code":"${fresh[0]}"}""").status)
    }

    @Test
    fun recoveryCodeGuessingIsSlowedDown() = server {
        setUp()
        cookie = null
        repeat(5) { assertEquals(HttpStatusCode.Forbidden, post("/api/recover", """{"code":"00000-00000-00000-0000$it"}""").status) }
        assertEquals(HttpStatusCode.TooManyRequests, post("/api/recover", """{"code":"00000-00000-00000-00009"}""").status)
    }

    @Test
    fun sessionsEndWhenIdle() = server {
        setUp()
        clock += Sessions.IDLE_MS - 1000
        assertEquals(HttpStatusCode.OK, me().status)
        clock += Sessions.IDLE_MS + 1
        assertEquals(HttpStatusCode.Unauthorized, me().status)
    }

    @Test
    fun checkingForMailInTheBackgroundDoesNotKeepYouSignedIn() = server {
        setUp()
        // A tab left open checks in every minute...
        repeat(3) {
            clock += Sessions.IDLE_MS / 3
            assertEquals(HttpStatusCode.OK, get("/api/me", background = true).status)
        }
        // ...but only you using it counts, so after 2 idle hours it's signed out.
        clock += 1_000
        assertEquals(HttpStatusCode.Unauthorized, get("/api/me", background = true).status)
        assertEquals(HttpStatusCode.Unauthorized, me().status)
    }

    @Test
    fun usingThePageKeepsYouSignedIn() = server {
        setUp()
        repeat(3) {
            clock += Sessions.IDLE_MS - 1_000
            assertEquals(HttpStatusCode.OK, me().status)
        }
    }

    @Test
    fun signedInPagesNeedASession() = server {
        setUp()
        cookie = "made-up"
        assertEquals(HttpStatusCode.Unauthorized, me().status)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/recovery-codes").status)
        assertEquals(HttpStatusCode.Unauthorized, post("/api/passkeys/start").status)
        assertEquals(HttpStatusCode.Unauthorized, delete("/api/passkeys/x").status)
    }
}
