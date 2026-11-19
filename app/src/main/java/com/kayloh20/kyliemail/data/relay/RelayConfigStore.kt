package com.kayloh20.kyliemail.data.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RelayConfig(
    val server: String,
    val setupCode: String,
    val credentials: RelayCredentials?,
    /** When the relay last confirmed it's watching. */
    val lastOkAt: Long = 0,
    val passwordRejected: Boolean = false,
)

interface RelayConfigStore {
    fun load(): RelayConfig?
    fun save(config: RelayConfig?)
}

/** The relay settings, with the setup code and key encrypted by the Android Keystore. */
class SecureRelayConfigStore(context: Context) : RelayConfigStore {
    private val prefs = context.getSharedPreferences("relay", Context.MODE_PRIVATE)

    override fun load(): RelayConfig? {
        val server = prefs.getString(SERVER, null) ?: return null
        val code = prefs.getString(CODE, null)?.let(::decrypt) ?: return null
        val id = prefs.getString(ACCOUNT, null)
        val key = prefs.getString(KEY, null)?.let(::decrypt)
        return RelayConfig(
            server = server,
            setupCode = code,
            credentials = if (id != null && key != null) RelayCredentials(id, key) else null,
            lastOkAt = prefs.getLong(LAST_OK, 0),
            passwordRejected = prefs.getBoolean(REJECTED, false),
        )
    }

    override fun save(config: RelayConfig?) = prefs.edit(commit = true) {
        clear()
        if (config != null) {
            putString(SERVER, config.server)
            putString(CODE, encrypt(config.setupCode))
            config.credentials?.let {
                putString(ACCOUNT, it.accountId)
                putString(KEY, encrypt(it.key))
            }
            putLong(LAST_OK, config.lastOkAt)
            putBoolean(REJECTED, config.passwordRejected)
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? = runCatching {
        val sealed = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, 12))
        }
        String(cipher.doFinal(sealed, 12, sealed.size - 12))
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "relay_secrets"
        const val SERVER = "server"
        const val CODE = "setup_code"
        const val ACCOUNT = "account_id"
        const val KEY = "key"
        const val LAST_OK = "last_ok"
        const val REJECTED = "password_rejected"
    }
}
