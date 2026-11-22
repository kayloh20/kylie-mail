package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.web.Crypto.b64url
import com.kayloh20.kyliemail.web.Crypto.fromB64url

/** A passkey that has just been registered, before it holds a copy of the account key. */
data class NewPasskey(
    val id: String,
    val name: String,
    val credentialData: String,
    val signCount: Long,
    val backupEligible: Boolean,
    val backupState: Boolean,
    val prfSalt: String,
)

/**
 * Holds the account key. One random key seals the app-specific password. Each passkey keeps its
 * own sealed copy of that key, opened only by the secret the passkey's PRF produces, and so does
 * each recovery code. The server never stores the account key or any PRF secret, so it can't open
 * the password unless you've just signed in.
 */
class Vault(private val store: AccountStore, private val now: () -> Long = System::currentTimeMillis) {

    fun account(): Account? = store.get()

    fun create(email: String, username: String, password: String, userHandle: String, passkey: NewPasskey, prfOutput: ByteArray): Created {
        check(store.get() == null) { "An account already exists" }
        val accountKey = Crypto.randomBytes(32)
        val (codes, storedCodes) = recoveryCodes(accountKey)
        store.put(
            Account(
                email = email,
                username = username,
                userHandle = userHandle,
                sealedPassword = b64url(Crypto.seal(accountKey, password.toByteArray(Charsets.UTF_8), PASSWORD_AD)),
                passkeys = listOf(stored(passkey, accountKey, prfOutput)),
                recoveryCodes = storedCodes,
                createdAt = now(),
            ),
        )
        return Created(accountKey, codes)
    }

    class Created(val accountKey: ByteArray, val recoveryCodes: List<String>)

    /** The account key, if [prfOutput] is the secret passkey [passkeyId] gives. */
    fun unlockWithPasskey(passkeyId: String, prfOutput: ByteArray): ByteArray? {
        val passkey = store.get()?.passkeys?.find { it.id == passkeyId } ?: return null
        return Crypto.open(passkeyKey(prfOutput, passkey.prfSalt), fromB64url(passkey.wrappedKey), passkeyAd(passkey.id))
    }

    fun recordUse(passkeyId: String, signCount: Long) {
        store.update { account ->
            account.copy(passkeys = account.passkeys.map {
                if (it.id == passkeyId) it.copy(signCount = signCount, lastUsedAt = now()) else it
            })
        }
    }

    /** The account key and how many codes are left, if [code] is an unused recovery code. Each code works once. */
    @Synchronized
    fun unlockWithRecoveryCode(code: String): Pair<ByteArray, Int>? {
        val normalized = RecoveryCodes.normalize(code) ?: return null
        val account = store.get() ?: return null
        for (stored in account.recoveryCodes) {
            val key = Crypto.open(recoveryKey(normalized, stored.salt), fromB64url(stored.wrappedKey), recoveryAd(stored.salt))
                ?: continue
            val left = account.recoveryCodes - stored
            store.put(account.copy(recoveryCodes = left))
            return key to left.size
        }
        return null
    }

    fun addPasskey(accountKey: ByteArray, passkey: NewPasskey, prfOutput: ByteArray) {
        requireKey(accountKey)
        store.update { it.copy(passkeys = it.passkeys + stored(passkey, accountKey, prfOutput)) }
    }

    /** Removes a passkey, unless it's the last one. */
    @Synchronized
    fun removePasskey(passkeyId: String): Boolean {
        val account = store.get() ?: return false
        if (account.passkeys.size <= 1 || account.passkeys.none { it.id == passkeyId }) return false
        store.put(account.copy(passkeys = account.passkeys.filter { it.id != passkeyId }))
        return true
    }

    /** Replaces every recovery code with a fresh set. */
    fun newRecoveryCodes(accountKey: ByteArray): List<String> {
        requireKey(accountKey)
        val (codes, stored) = recoveryCodes(accountKey)
        store.update { it.copy(recoveryCodes = stored) }
        return codes
    }

    /** The app-specific password, opened with the account key. */
    fun password(accountKey: ByteArray): String? {
        val account = store.get() ?: return null
        return Crypto.open(accountKey, fromB64url(account.sealedPassword), PASSWORD_AD)?.toString(Charsets.UTF_8)
    }

    private fun requireKey(accountKey: ByteArray) {
        require(password(accountKey) != null) { "Wrong account key" }
    }

    private fun stored(passkey: NewPasskey, accountKey: ByteArray, prfOutput: ByteArray): StoredPasskey {
        require(prfOutput.size >= 32) { "The passkey's PRF secret is too short" }
        return StoredPasskey(
            id = passkey.id,
            name = passkey.name,
            credentialData = passkey.credentialData,
            signCount = passkey.signCount,
            backupEligible = passkey.backupEligible,
            backupState = passkey.backupState,
            prfSalt = passkey.prfSalt,
            wrappedKey = b64url(Crypto.seal(passkeyKey(prfOutput, passkey.prfSalt), accountKey, passkeyAd(passkey.id))),
            createdAt = now(),
        )
    }

    private fun recoveryCodes(accountKey: ByteArray): Pair<List<String>, List<StoredRecoveryCode>> {
        val codes = List(RecoveryCodes.COUNT) { RecoveryCodes.generate() }
        val stored = codes.map { code ->
            val salt = b64url(Crypto.randomBytes(16))
            val key = recoveryKey(RecoveryCodes.normalize(code)!!, salt)
            StoredRecoveryCode(salt, b64url(Crypto.seal(key, accountKey, recoveryAd(salt))))
        }
        return codes to stored
    }

    private fun passkeyKey(prfOutput: ByteArray, salt: String) = Crypto.hkdf(prfOutput, fromB64url(salt), "kylie-mail passkey v1")
    private fun recoveryKey(normalizedCode: String, salt: String) =
        Crypto.hkdf(normalizedCode.toByteArray(Charsets.US_ASCII), fromB64url(salt), "kylie-mail recovery v1")

    private fun passkeyAd(id: String) = "passkey:$id"
    private fun recoveryAd(salt: String) = "recovery:$salt"

    private companion object {
        const val PASSWORD_AD = "icloud-password"
    }
}

/**
 * Recovery codes: 20 characters (100 random bits) in groups of five, using Crockford's base32 so
 * that O and 0, and I, L and 1, can't be mixed up.
 */
object RecoveryCodes {
    const val COUNT = 10
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 20

    fun generate(): String {
        val bytes = Crypto.randomBytes(LENGTH)
        val chars = bytes.map { ALPHABET[it.toInt() and 31] }
        return chars.chunked(5).joinToString("-") { it.joinToString("") }
    }

    /** The code in canonical form, or null if it can't be one. */
    fun normalize(code: String): String? {
        val cleaned = code.uppercase().filter { it.isLetterOrDigit() }
            .replace('O', '0').replace('I', '1').replace('L', '1')
        return cleaned.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }
}
