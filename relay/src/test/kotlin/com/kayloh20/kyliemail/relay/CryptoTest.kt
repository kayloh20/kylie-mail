package com.kayloh20.kyliemail.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test
    fun sealsAndOpensWithTheRightKeyOnly() {
        val key = Crypto.newKey()
        val sealed = Crypto.seal(key, "abcd-efgh-ijkl-mnop", "account-1")
        assertEquals("abcd-efgh-ijkl-mnop", Crypto.open(key, sealed, "account-1"))
        assertNull(Crypto.open(Crypto.newKey(), sealed, "account-1"))
        // A sealed password copied onto another account doesn't open.
        assertNull(Crypto.open(key, sealed, "account-2"))
    }

    @Test
    fun theHashRecognisesTheKeyWithoutRevealingIt() {
        val key = Crypto.newKey()
        val hash = Crypto.keyHash(key)
        assertTrue(Crypto.matches(key, hash))
        assertFalse(Crypto.matches(Crypto.newKey(), hash))
        assertFalse(hash.contains(Crypto.encode(key)))
    }
}
