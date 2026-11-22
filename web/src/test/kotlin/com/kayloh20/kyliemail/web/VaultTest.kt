package com.kayloh20.kyliemail.web

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultTest {
    @get:Rule val folder = TemporaryFolder()

    private fun passkey(id: String) = NewPasskey(id, "Mac", "data", 0, true, true, Crypto.b64url(Crypto.randomBytes(32)))

    @Test
    fun passkeysAndRecoveryCodesUnlockThePassword() {
        val file = folder.root.toPath().resolve("account.json")
        val vault = Vault(AccountStore(file))
        val prf = Crypto.randomBytes(32)
        val created = vault.create("me@icloud.com", "me", "abcd-efgh-ijkl-mnop", "handle", passkey("pk1"), prf)

        assertEquals(RecoveryCodes.COUNT, created.recoveryCodes.toSet().size)
        assertEquals("abcd-efgh-ijkl-mnop", vault.password(assertNotNull(vault.unlockWithPasskey("pk1", prf))))
        assertNull(vault.unlockWithPasskey("pk1", Crypto.randomBytes(32)))
        assertNull(vault.unlockWithPasskey("nope", prf))

        // The file on disk never holds the password, the account key or the PRF secret.
        val onDisk = Files.readString(file)
        assertFalse("abcd-efgh-ijkl-mnop" in onDisk)
        assertFalse(Crypto.b64url(created.accountKey) in onDisk)
        assertFalse(Crypto.b64url(prf) in onDisk)

        // A fresh vault reading the same file still works.
        val reloaded = Vault(AccountStore(file))
        val code = created.recoveryCodes.first()
        val (key, left) = assertNotNull(reloaded.unlockWithRecoveryCode(code.lowercase().replace("-", " ")))
        assertContentEquals(created.accountKey, key)
        assertEquals(RecoveryCodes.COUNT - 1, left)
        assertNull(reloaded.unlockWithRecoveryCode(code), "each code works once")
        assertNull(reloaded.unlockWithRecoveryCode("0000-0000-0000-0000-0000"))
    }

    @Test
    fun managingPasskeysAndCodes() {
        val vault = Vault(AccountStore(folder.root.toPath().resolve("account.json")))
        val prf1 = Crypto.randomBytes(32)
        val created = vault.create("me@icloud.com", "me", "pw", "handle", passkey("pk1"), prf1)
        assertFalse(vault.removePasskey("pk1"), "the only passkey stays")

        val prf2 = Crypto.randomBytes(32)
        vault.addPasskey(created.accountKey, passkey("pk2"), prf2)
        assertEquals("pw", vault.password(assertNotNull(vault.unlockWithPasskey("pk2", prf2))))
        assertTrue(vault.removePasskey("pk1"))
        assertNull(vault.unlockWithPasskey("pk1", prf1))

        val fresh = vault.newRecoveryCodes(created.accountKey)
        assertNull(vault.unlockWithRecoveryCode(created.recoveryCodes.first()), "old codes stop working")
        assertNotNull(vault.unlockWithRecoveryCode(fresh.first()))
    }

    @Test
    fun recoveryCodeFormat() {
        val code = RecoveryCodes.generate()
        assertTrue(Regex("[0-9A-Z]{5}(-[0-9A-Z]{5}){3}").matches(code), code)
        assertEquals(RecoveryCodes.normalize("0O1IL-AAAAA-BBBBB-CCCCC"), "00111AAAAABBBBBCCCCC")
        assertNull(RecoveryCodes.normalize("short"))
        assertNull(RecoveryCodes.normalize("UUUUU-UUUUU-UUUUU-UUUUU"), "U isn't in the alphabet")
    }
}
