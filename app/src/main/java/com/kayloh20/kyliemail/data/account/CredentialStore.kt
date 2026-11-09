package com.kayloh20.kyliemail.data.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Account(
    val email: String,
    /** The login name the server accepted, which can differ from [email]. */
    val username: String,
    val password: String,
)

/** Keeps the signed-in account, with the password encrypted by a key that never leaves the Android Keystore. */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val _account = MutableStateFlow(load())
    val account: StateFlow<Account?> = _account.asStateFlow()

    fun save(account: Account) {
        // Written synchronously so the account is on disk before the UI moves on.
        prefs.edit(commit = true) {
            putString(KEY_EMAIL, account.email)
            putString(KEY_USERNAME, account.username)
            putString(KEY_PASSWORD, encrypt(account.password))
        }
        _account.value = account
    }

    fun clear() {
        prefs.edit(commit = true) { clear() }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        _account.value = null
    }

    private fun load(): Account? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: email
        val encrypted = prefs.getString(KEY_PASSWORD, null) ?: return null
        // If the key was lost (for example after a restore), the user has to sign in again.
        val password = runCatching { decrypt(encrypted) }.getOrNull() ?: return null
        return Account(email, username, password)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = sealed.copyOfRange(0, IV_LENGTH)
        val cipherText = sealed.copyOfRange(IV_LENGTH, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "account_password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val KEY_EMAIL = "email"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
