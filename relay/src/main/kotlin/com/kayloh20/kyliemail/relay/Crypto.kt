package com.kayloh20.kyliemail.relay

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The account key belongs to the user's devices. The relay keeps only a hash of it (to recognise
 * a device) and the password sealed with it, so what's on disk is useless without a device
 * checking in. While running, the key is held in memory only.
 */
object Crypto {
    private val random = SecureRandom()
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun newKey(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun newSecret(bytes: Int = 24): String = encode(ByteArray(bytes).also(random::nextBytes))

    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()

    /** Separate keys for separate jobs, all from the one account key. */
    private fun derive(key: ByteArray, purpose: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(purpose.toByteArray())
        }

    /** What's stored to recognise the key later. */
    fun keyHash(key: ByteArray): String =
        encode(MessageDigest.getInstance("SHA-256").digest(derive(key, "kylie-relay-auth-v1")))

    fun matches(key: ByteArray, hash: String): Boolean =
        MessageDigest.isEqual(keyHash(key).toByteArray(), hash.toByteArray())

    /** Constant-time comparison for secrets typed in, like the setup code. */
    fun sameSecret(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun seal(key: ByteArray, plaintext: String, context: String): String {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(key, "kylie-relay-seal-v1"), "AES"), GCMParameterSpec(TAG_BITS, nonce))
            // Ties the sealed password to its account, so it can't be swapped onto another one.
            updateAAD(context.toByteArray())
        }
        return encode(nonce + cipher.doFinal(plaintext.toByteArray()))
    }

    /** Null when the key is wrong or the data was tampered with. */
    fun open(key: ByteArray, sealed: String, context: String): String? = runCatching {
        val bytes = decode(sealed) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derive(key, "kylie-relay-seal-v1"), "AES"),
                GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES),
            )
            updateAAD(context.toByteArray())
        }
        String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES))
    }.getOrNull()
}
