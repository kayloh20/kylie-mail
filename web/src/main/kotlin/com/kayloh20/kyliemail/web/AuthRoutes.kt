package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.web.Crypto.b64url
import com.kayloh20.kyliemail.web.Crypto.fromB64url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable data class StateReply(val hasAccount: Boolean, val signedIn: Boolean)
@Serializable data class SetupStartBody(val setupCode: String, val email: String, val password: String, val passkeyName: String = "")
@Serializable data class CreateReply(val pendingId: String, val options: CreationOptions)
@Serializable data class CreateFinishBody(val pendingId: String, val credential: RegistrationCredential, val prf: String)
@Serializable data class GetReply(val pendingId: String, val options: RequestOptions)
@Serializable data class GetFinishBody(val pendingId: String, val credential: AssertionCredential, val prf: String)
@Serializable data class CodesReply(val recoveryCodes: List<String>)
@Serializable data class RecoverBody(val code: String)
@Serializable data class RecoverReply(val codesLeft: Int)
@Serializable data class AddPasskeyBody(val name: String = "")
@Serializable data class PasskeyInfo(val id: String, val name: String, val createdAt: Long, val lastUsedAt: Long?, val synced: Boolean)
@Serializable data class MeReply(
    val email: String,
    val passkeys: List<PasskeyInfo>,
    val recoveryCodesLeft: Int,
    val signedInWithRecoveryCode: Boolean,
)
@Serializable data class ErrorReply(val error: String)

/** Everything the server's routes share. Tests build one with fakes. */
class Services(
    val config: WebConfig,
    val vault: Vault,
    val passkeys: Passkeys,
    val sessions: Sessions,
    val setupCode: SetupCode,
    val mailLogin: MailLogin,
    val now: () -> Long = System::currentTimeMillis,
) {
    class PendingSetup(
        val email: String,
        val username: String,
        val password: String,
        val userHandle: String,
        val challenge: ByteArray,
        val prfSalt: String,
        val name: String,
    )
    class PendingAdd(val challenge: ByteArray, val prfSalt: String, val name: String, val sessionToken: String)

    val pendingSetups = Pending<PendingSetup>(now, lifetimeMs = 10 * 60 * 1000L)
    val pendingSignIns = Pending<ByteArray>(now)
    val pendingAdds = Pending<PendingAdd>(now)

    val setupAttempts = RateLimiter(10, 15 * 60 * 1000L, now)
    val signInAttempts = RateLimiter(30, 15 * 60 * 1000L, now)
    val recoveryAttempts = RateLimiter(5, 15 * 60 * 1000L, now)
    val recoveryAttemptsEverywhere = RateLimiter(20, 24 * 60 * 60 * 1000L, now)

    companion object
}

const val SESSION_COOKIE = "__Host-kylie-session"

class ApiError(val status: HttpStatusCode, message: String) : Exception(message)

private fun fail(status: HttpStatusCode, message: String): Nothing = throw ApiError(status, message)

/** The visitor's address. Only Caddy can reach the server, and it sets X-Forwarded-For itself. */
private fun ApplicationCall.clientAddress(): String =
    request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: request.origin.remoteAddress

private fun ApplicationCall.sessionToken(): String? = request.cookies[SESSION_COOKIE]

private fun ApplicationCall.setSessionCookie(token: String) =
    response.headers.append(
        HttpHeaders.SetCookie,
        "$SESSION_COOKIE=$token; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${Sessions.MAX_MS / 1000}",
    )

private fun ApplicationCall.clearSessionCookie() =
    response.headers.append(HttpHeaders.SetCookie, "$SESSION_COOKIE=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0")

private fun prfSecret(value: String): ByteArray {
    val bytes = runCatching { fromB64url(value) }.getOrNull()
    if (bytes == null || bytes.size < 32) {
        fail(HttpStatusCode.BadRequest, "This passkey can't lock your mail. Use one saved in iCloud Keychain, Google Password Manager or a recent security key.")
    }
    return bytes
}

/** The signed-in session, or a 401. */
fun ApplicationCall.requireSession(services: Services): Session =
    services.sessions.get(sessionToken()) ?: fail(HttpStatusCode.Unauthorized, "Sign in first.")

fun Route.authRoutes(services: Services) = route("/api") {
    val vault = services.vault
    val passkeys = services.passkeys
    val sessions = services.sessions

    get("/state") {
        call.respond(StateReply(vault.account() != null, sessions.get(call.sessionToken()) != null))
    }

    // First-time setup: setup code and iCloud login, then a passkey.

    post("/setup/start") {
        if (vault.account() != null) fail(HttpStatusCode.Conflict, "Kylie Mail is already set up.")
        if (!services.setupAttempts.allow(call.clientAddress())) fail(HttpStatusCode.TooManyRequests, "Too many tries. Wait 15 minutes.")
        val body = call.receive<SetupStartBody>()
        if (!services.setupCode.matches(body.setupCode)) fail(HttpStatusCode.Forbidden, "That setup code isn't right.")
        val email = body.email.trim()
        if (email.isEmpty() || body.password.isBlank()) fail(HttpStatusCode.BadRequest, "Enter your iCloud address and app-specific password.")
        val username = when (val result = services.mailLogin.check(email, body.password.trim())) {
            is MailLoginResult.Accepted -> result.username
            MailLoginResult.Rejected -> fail(HttpStatusCode.Unauthorized, "iCloud didn't accept that. Check the address and app-specific password.")
            MailLoginResult.Unreachable -> fail(HttpStatusCode.BadGateway, "Couldn't reach iCloud. Try again in a moment.")
        }
        val challenge = Crypto.randomBytes(32)
        val prfSalt = b64url(Crypto.randomBytes(32))
        val userHandle = b64url(Crypto.randomBytes(16))
        val pending = Services.PendingSetup(email, username, body.password.trim(), userHandle, challenge, prfSalt, passkeyName(body.passkeyName, 1))
        val options = passkeys.creationOptions(challenge, userHandle, email, emptyList(), prfSalt)
        call.respond(CreateReply(services.pendingSetups.put(pending), options))
    }

    post("/setup/finish") {
        val body = call.receive<CreateFinishBody>()
        val pending = services.pendingSetups.take(body.pendingId) ?: fail(HttpStatusCode.BadRequest, "Setup took too long. Start again.")
        if (vault.account() != null) fail(HttpStatusCode.Conflict, "Kylie Mail is already set up.")
        val prf = prfSecret(body.prf)
        val passkey = passkeys.verifyRegistration(body.credential, pending.challenge, pending.name, pending.prfSalt)
        val created = vault.create(pending.email, pending.username, pending.password, pending.userHandle, passkey, prf)
        services.setupCode.remove()
        call.setSessionCookie(sessions.create(created.accountKey))
        created.accountKey.fill(0)
        call.respond(CodesReply(created.recoveryCodes))
    }

    // Signing in.

    post("/signin/start") {
        val account = vault.account() ?: fail(HttpStatusCode.Conflict, "Kylie Mail isn't set up yet.")
        val challenge = Crypto.randomBytes(32)
        call.respond(GetReply(services.pendingSignIns.put(challenge), passkeys.requestOptions(challenge, account.passkeys)))
    }

    post("/signin/finish") {
        if (!services.signInAttempts.allow(call.clientAddress())) fail(HttpStatusCode.TooManyRequests, "Too many tries. Wait 15 minutes.")
        val body = call.receive<GetFinishBody>()
        val challenge = services.pendingSignIns.take(body.pendingId) ?: fail(HttpStatusCode.BadRequest, "Signing in took too long. Try again.")
        val stored = vault.account()?.passkeys?.find { it.id == body.credential.id.trimEnd('=') }
            ?: fail(HttpStatusCode.Forbidden, "That passkey isn't one of yours here.")
        val signCount = passkeys.verifyAssertion(body.credential, challenge, stored)
        val accountKey = vault.unlockWithPasskey(stored.id, prfSecret(body.prf))
            ?: fail(HttpStatusCode.Forbidden, "That passkey couldn't unlock your mail.")
        vault.recordUse(stored.id, signCount)
        call.setSessionCookie(sessions.create(accountKey))
        accountKey.fill(0)
        call.respond(StateReply(hasAccount = true, signedIn = true))
    }

    post("/recover") {
        if (!services.recoveryAttempts.allow(call.clientAddress()) || !services.recoveryAttemptsEverywhere.allow("all")) {
            fail(HttpStatusCode.TooManyRequests, "Too many tries. Wait a while before trying another code.")
        }
        val body = call.receive<RecoverBody>()
        val (accountKey, left) = vault.unlockWithRecoveryCode(body.code)
            ?: fail(HttpStatusCode.Forbidden, "That recovery code isn't right or was already used.")
        call.setSessionCookie(sessions.create(accountKey, viaRecoveryCode = true))
        accountKey.fill(0)
        call.respond(RecoverReply(left))
    }

    post("/signout") {
        call.sessionToken()?.let(sessions::end)
        call.clearSessionCookie()
        call.respond(StateReply(hasAccount = vault.account() != null, signedIn = false))
    }

    // Signed in: your passkeys and recovery codes.

    get("/me") {
        val session = call.requireSession(services)
        val account = vault.account() ?: fail(HttpStatusCode.Conflict, "Kylie Mail isn't set up yet.")
        call.respond(
            MeReply(
                email = account.email,
                passkeys = account.passkeys.map { PasskeyInfo(it.id, it.name, it.createdAt, it.lastUsedAt, it.backupEligible) },
                recoveryCodesLeft = account.recoveryCodes.size,
                signedInWithRecoveryCode = session.viaRecoveryCode,
            ),
        )
    }

    post("/passkeys/start") {
        call.requireSession(services)
        val account = vault.account() ?: fail(HttpStatusCode.Conflict, "Kylie Mail isn't set up yet.")
        val body = call.receive<AddPasskeyBody>()
        val challenge = Crypto.randomBytes(32)
        val prfSalt = b64url(Crypto.randomBytes(32))
        val pending = Services.PendingAdd(challenge, prfSalt, passkeyName(body.name, account.passkeys.size + 1), call.sessionToken()!!)
        val options = passkeys.creationOptions(challenge, account.userHandle, account.email, account.passkeys.map { it.id }, prfSalt)
        call.respond(CreateReply(services.pendingAdds.put(pending), options))
    }

    post("/passkeys/finish") {
        val session = call.requireSession(services)
        val body = call.receive<CreateFinishBody>()
        val pending = services.pendingAdds.take(body.pendingId)
            ?.takeIf { it.sessionToken == call.sessionToken() }
            ?: fail(HttpStatusCode.BadRequest, "Adding the passkey took too long. Try again.")
        val prf = prfSecret(body.prf)
        val passkey = passkeys.verifyRegistration(body.credential, pending.challenge, pending.name, pending.prfSalt)
        vault.addPasskey(session.accountKey, passkey, prf)
        call.respond(StateReply(hasAccount = true, signedIn = true))
    }

    delete("/passkeys/{id}") {
        call.requireSession(services)
        val id = call.parameters["id"].orEmpty()
        if (!vault.removePasskey(id)) fail(HttpStatusCode.Conflict, "You can't remove your only passkey.")
        call.respond(StateReply(hasAccount = true, signedIn = true))
    }

    post("/recovery-codes") {
        val session = call.requireSession(services)
        call.respond(CodesReply(vault.newRecoveryCodes(session.accountKey)))
    }
}

private fun passkeyName(requested: String, number: Int): String =
    requested.trim().take(60).filter { !it.isISOControl() }.ifEmpty { "Passkey $number" }
