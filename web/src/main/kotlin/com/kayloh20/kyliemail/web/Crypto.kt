package com.kayloh20.kyliemail.web

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The few cryptographic building blocks Kylie Mail uses, all from the JDK. */
object Crypto {
    private val random = SecureRandom()
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Decodes base64url, with or without padding. Throws [IllegalArgumentException] on bad input. */
    fun fromB64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** HKDF with SHA-256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray =
        hkdf(ikm, salt, info.toByteArray(Charsets.UTF_8), length)

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(salt.takeIf { it.isNotEmpty() } ?: ByteArray(32), ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, n)
            offset += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** AES-256-GCM. The result is the random nonce followed by the ciphertext and tag. */
    fun seal(key: ByteArray, plaintext: ByteArray, associatedData: String): ByteArray {
        require(key.size == 32)
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(plaintext)
    }

    /** Opens what [seal] made, or returns null if the key or associated data is wrong or it was changed. */
    fun open(key: ByteArray, sealed: ByteArray, associatedData: String): ByteArray? {
        if (key.size != 32 || sealed.size < NONCE_BYTES + TAG_BITS / 8) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return try {
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            null
        }
    }
}
