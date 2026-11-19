package com.kayloh20.kyliemail.data.relay

import com.kayloh20.kyliemail.data.account.AccountSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** This device's push address with Firebase. */
interface PushTokens {
    suspend fun token(): String
    suspend fun delete()
}

sealed interface RelayStatus {
    data object Off : RelayStatus
    data class Connected(val server: String, val lastOkAt: Long) : RelayStatus
    /** iCloud turned down the password the relay has, so it has stopped. */
    data class PasswordRejected(val server: String) : RelayStatus
    /** Couldn't check in lately; the phone watches for mail itself meanwhile. */
    data class Unreachable(val server: String, val lastOkAt: Long) : RelayStatus
}

sealed interface ConnectResult {
    data object Connected : ConnectResult
    data object BadAddress : ConnectResult
    data object BadSetupCode : ConnectResult
    data object PasswordRejected : ConnectResult
    data object NotSignedIn : ConnectResult
    data class Failed(val message: String) : ConnectResult
}

/**
 * The phone's side of the push relay: signing up, checking in, and deciding whether the relay can
 * be trusted to announce new mail, which lets the phone drop its own always-on connection.
 */
class PushRelay(
    private val store: RelayConfigStore,
    private val api: RelayApi,
    private val accounts: AccountSource,
    private val tokens: PushTokens,
    private val deviceName: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called when [handlesInstantMail] may have changed, to start or stop the phone's own watcher. */
    private val onModeChanged: () -> Unit = {},
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(status(store.load()))
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    val isSetUp: Boolean get() = store.load() != null

    /**
     * True when the relay has confirmed recently that it's watching. Check-ins happen at least every
     * 15 minutes, so a relay that has gone quiet for longer than [TRUST_MS] is no longer relied on.
     */
    fun handlesInstantMail(): Boolean {
        val config = store.load() ?: return false
        return config.credentials != null && !config.passwordRejected && clock() - config.lastOkAt < TRUST_MS
    }

    suspend fun connect(serverInput: String, setupCode: String): ConnectResult = lock.withLock {
        val server = RelayApi.normalize(serverInput) ?: return ConnectResult.BadAddress
        val account = accounts.account.value ?: return ConnectResult.NotSignedIn
        val token = runCatching { tokens.token() }.getOrElse {
            return ConnectResult.Failed("Couldn't get a push address from Google Play services.")
        }
        val result = when (val reply = api.register(server, setupCode.trim(), account.username, account.password, token, deviceName)) {
            is RelayReply.Ok -> {
                store.save(RelayConfig(server, setupCode.trim(), reply.value, lastOkAt = clock()))
                ConnectResult.Connected
            }
            RelayReply.BadSetupCode -> ConnectResult.BadSetupCode
            RelayReply.PasswordRejected -> ConnectResult.PasswordRejected
            RelayReply.UnknownKey -> ConnectResult.Failed("The relay turned this device away.")
            is RelayReply.Failed -> ConnectResult.Failed(reply.message)
        }
        changed()
        result
    }

    /**
     * Tells the relay this device is still here (and its current push address). A restarted relay
     * needs this to unlock; one that has forgotten this device's key gets a fresh sign-up.
     */
    suspend fun checkIn(): Unit = lock.withLock {
        val config = store.load() ?: return
        val account = accounts.account.value ?: return
        val token = runCatching { tokens.token() }.getOrNull() ?: return changed()
        val credentials = config.credentials
        val reply = if (credentials == null) null else api.checkIn(config.server, credentials, token, deviceName)
        when {
            reply is RelayReply.Ok -> store.save(
                config.copy(lastOkAt = if (reply.value == "watching") clock() else config.lastOkAt, passwordRejected = reply.value == "password_rejected"),
            )
            reply == null || reply == RelayReply.UnknownKey -> {
                when (val again = api.register(config.server, config.setupCode, account.username, account.password, token, deviceName)) {
                    is RelayReply.Ok -> store.save(config.copy(credentials = again.value, lastOkAt = clock(), passwordRejected = false))
                    RelayReply.PasswordRejected -> store.save(config.copy(passwordRejected = true))
                    // A wrong setup code (it was changed on the server) or an error: keep trying at the next check-in.
                    else -> Unit
                }
            }
            // Unreachable: leave lastOkAt alone, so trust runs out and the phone takes over.
            else -> Unit
        }
        changed()
    }

    /** A push arrived, which shows the relay is working. */
    fun heardFrom() {
        val config = store.load() ?: return
        store.save(config.copy(lastOkAt = clock(), passwordRejected = false))
        changed()
    }

    /** Signs this device off the relay. The relay deletes the account once no devices are left. */
    suspend fun disconnect(): Unit = lock.withLock {
        val config = store.load() ?: return
        val credentials = config.credentials
        if (credentials != null) {
            val token = runCatching { tokens.token() }.getOrNull()
            if (token != null) runCatching { api.unregister(config.server, credentials, token) }
        }
        runCatching { tokens.delete() }
        store.save(null)
        changed()
    }

    private fun changed() {
        _status.value = status(store.load())
        onModeChanged()
    }

    private fun status(config: RelayConfig?): RelayStatus = when {
        config == null -> RelayStatus.Off
        config.passwordRejected -> RelayStatus.PasswordRejected(config.server)
        config.credentials != null && clock() - config.lastOkAt < TRUST_MS -> RelayStatus.Connected(config.server, config.lastOkAt)
        else -> RelayStatus.Unreachable(config.server, config.lastOkAt)
    }

    companion object {
        /** Two missed 15-minute check-ins, and a bit. */
        const val TRUST_MS = 40 * 60_000L
    }
}
