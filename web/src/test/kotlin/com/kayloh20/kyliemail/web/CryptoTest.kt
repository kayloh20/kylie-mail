package com.kayloh20.kyliemail.web

import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CryptoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun hkdfMatchesRfc5869() {
        // RFC 5869, test case 1.
        val okm = Crypto.hkdf(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun sealOpensOnlyWithTheRightKeyAndLabel() {
        val key = Crypto.randomBytes(32)
        val sealed = Crypto.seal(key, "secret".toByteArray(), "label")
        assertContentEquals("secret".toByteArray(), assertNotNull(Crypto.open(key, sealed, "label")))
        assertNull(Crypto.open(Crypto.randomBytes(32), sealed, "label"))
        assertNull(Crypto.open(key, sealed, "other"))
        assertNull(Crypto.open(key, sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }, "label"))
        assertNull(Crypto.open(key, ByteArray(3), "label"))
    }
}
