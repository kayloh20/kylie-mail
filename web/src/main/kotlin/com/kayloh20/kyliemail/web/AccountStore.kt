package com.kayloh20.kyliemail.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Everything the server keeps about you between restarts. Nothing in it opens your mail by
 * itself: the app-specific password is sealed with a key that only your passkeys and recovery
 * codes can unlock.
 */
@Serializable
data class Account(
    val email: String,
    /** The username iCloud accepted, which may be the address without its domain. */
    val username: String,
    /** WebAuthn user handle, base64url. */
    val userHandle: String,
    /** The app-specific password, sealed with the account key. Base64url. */
    val sealedPassword: String,
    val passkeys: List<StoredPasskey>,
    val recoveryCodes: List<StoredRecoveryCode>,
    val createdAt: Long,
    /** Senders whose images from the internet load without asking, sealed with the account key. */
    val sealedImageSenders: String? = null,
    /** The name mail is sent with and the signature, sealed with the account key. */
    val sealedIdentity: String? = null,
)

@Serializable
data class StoredPasskey(
    /** Credential ID, base64url. */
    val id: String,
    val name: String,
    /** WebAuthn attested credential data (includes the public key), base64url. */
    val credentialData: String,
    val signCount: Long,
    val backupEligible: Boolean,
    val backupState: Boolean,
    /** Input the passkey's PRF turns into this passkey's unlocking secret, base64url. */
    val prfSalt: String,
    /** The account key, sealed with the key derived from this passkey's PRF output. Base64url. */
    val wrappedKey: String,
    val createdAt: Long,
    val lastUsedAt: Long? = null,
)

@Serializable
data class StoredRecoveryCode(
    val salt: String,
    /** The account key, sealed with a key derived from the code. Base64url. */
    val wrappedKey: String,
)

/** Keeps the single account in one JSON file, written atomically and readable only by the server. */
class AccountStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private var cached: Account? = null
    private var loaded = false

    @Synchronized
    fun get(): Account? {
        if (!loaded) {
            cached = if (Files.exists(file)) json.decodeFromString(Account.serializer(), Files.readString(file)) else null
            loaded = true
        }
        return cached
    }

    @Synchronized
    fun put(account: Account) {
        Files.createDirectories(file.parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(temp, json.encodeToString(Account.serializer(), account))
        ownerOnly(temp)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cached = account
        loaded = true
    }

    /** Applies [change] to the stored account, if there is one, and saves the result. */
    @Synchronized
    fun update(change: (Account) -> Account): Account? = get()?.let(change)?.also(::put)

    companion object {
        fun ownerOnly(path: Path) {
            runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        }
    }
}
