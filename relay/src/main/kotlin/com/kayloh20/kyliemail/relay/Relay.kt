package com.kayloh20.kyliemail.relay

import jakarta.mail.AuthenticationFailedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ConcurrentHashMap

sealed interface RegisterResult {
    data class Registered(val accountId: String, val key: String) : RegisterResult
    data object BadSetupCode : RegisterResult
    data object PasswordRejected : RegisterResult
    data object Unreachable : RegisterResult
}

enum class CheckInResult { WATCHING, PASSWORD_REJECTED, UNKNOWN_KEY }

/**
 * Accounts, their devices, and one inbox watcher per account. An account's password can only be
 * read with its key, which only its devices have: after a restart nothing is watched until a
 * device checks in.
 */
class Relay(
    private val store: Store,
    private val imap: Imap,
    private val pusher: Pusher,
    private val setupCode: String,
    private val timing: WatchTiming = WatchTiming(),
    private val log: (String) -> Unit = ::println,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Unlocked accounts: their key, held in memory only, and their watcher. */
    private class Live(val key: ByteArray, val watcher: Watcher)
    private val live = ConcurrentHashMap<String, Live>()
    private val rejected = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun register(setupCode: String, username: String, password: String, device: DeviceInfo): RegisterResult {
        if (!Crypto.sameSecret(setupCode, this.setupCode)) return RegisterResult.BadSetupCode
        // Only keep a password iCloud accepts.
        try {
            imap.connect(username, password, 30_000).also(imap::kill)
        } catch (e: AuthenticationFailedException) {
            return RegisterResult.PasswordRejected
        } catch (e: Exception) {
            return RegisterResult.Unreachable
        }
        val existing = store.byUsername(username)
        val running = existing?.let { live[it.id] }
        val now = clock()
        val account: StoredAccount
        val key: ByteArray
        if (existing != null && running != null && Crypto.open(running.key, existing.sealedPassword, existing.id) == password) {
            // Another device for an account already being watched: it gets the same key.
            key = running.key
            account = existing.copy(devices = withDevice(existing.devices, device, now))
        } else {
            // New, or the password changed, or nobody has unlocked it since a restart: a fresh key.
            // Devices holding the old key are turned away at check-in and simply register again.
            existing?.let { stopWatching(it.id) }
            key = Crypto.newKey()
            val id = existing?.id ?: Crypto.newSecret(12)
            account = StoredAccount(
                id = id,
                username = username,
                keyHash = Crypto.keyHash(key),
                sealedPassword = Crypto.seal(key, password, id),
                devices = withDevice(existing?.devices.orEmpty(), device, now),
                lastUidNext = existing?.lastUidNext ?: 0,
                uidValidity = existing?.uidValidity ?: 0,
            )
        }
        store.put(account)
        rejected.remove(account.id)
        startWatching(account, key)
        log("registered a device for account ${account.id}")
        return RegisterResult.Registered(account.id, Crypto.encode(key))
    }

    /** A device saying hello: unlocks its account if needed and keeps its push token current. */
    @Synchronized
    fun checkIn(accountId: String, keyText: String, device: DeviceInfo): CheckInResult {
        val account = store.get(accountId) ?: return CheckInResult.UNKNOWN_KEY
        val key = Crypto.decode(keyText) ?: return CheckInResult.UNKNOWN_KEY
        if (!Crypto.matches(key, account.keyHash)) return CheckInResult.UNKNOWN_KEY
        store.update(accountId) { it.copy(devices = withDevice(it.devices, device, clock())) }
        if (accountId in rejected) return CheckInResult.PASSWORD_REJECTED
        if (!live.containsKey(accountId)) {
            log("account $accountId unlocked")
            startWatching(store.get(accountId)!!, key)
        }
        return CheckInResult.WATCHING
    }

    /** A device leaving. With none left, the account and its password are deleted. */
    @Synchronized
    fun unregister(accountId: String, keyText: String, token: String): Boolean {
        val account = store.get(accountId) ?: return false
        val key = Crypto.decode(keyText) ?: return false
        if (!Crypto.matches(key, account.keyHash)) return false
        val remaining = account.devices.filter { it.token != token }
        if (remaining.isEmpty()) {
            forget(accountId)
            log("account $accountId deleted")
        } else {
            store.update(accountId) { it.copy(devices = remaining) }
        }
        return true
    }

    fun isWatching(accountId: String): Boolean = live.containsKey(accountId)

    fun shutdown() {
        live.values.forEach { it.watcher.stop() }
        live.clear()
        scope.cancel()
    }

    private fun forget(accountId: String) {
        stopWatching(accountId)
        rejected.remove(accountId)
        store.remove(accountId)
    }

    private fun startWatching(account: StoredAccount, key: ByteArray) {
        stopWatching(account.id)
        val password = Crypto.open(key, account.sealedPassword, account.id) ?: return
        val id = account.id
        val watcher = Watcher(
            imap = imap,
            username = account.username,
            password = password,
            timing = timing,
            initiallyKnown = account.uidValidity to account.lastUidNext,
            onSeen = { validity, next -> store.update(id) { it.copy(uidValidity = validity, lastUidNext = next) } },
            onNewMail = { pushAll(id) },
            onPasswordRejected = {
                log("iCloud rejected the password for account $id")
                rejected += id
                live.remove(id)
            },
        )
        live[id] = Live(key, watcher)
        watcher.start(scope)
    }

    private fun stopWatching(accountId: String) {
        live.remove(accountId)?.watcher?.stop()
    }

    private fun pushAll(accountId: String) {
        val account = store.get(accountId) ?: return
        val gone = account.devices.filter { device ->
            val result = runCatching { pusher.push(device.token) }.getOrDefault(PushResult.FAILED)
            if (result == PushResult.FAILED) log("push to a device of account $accountId failed")
            result == PushResult.TOKEN_GONE
        }.map { it.token }.toSet()
        if (gone.isNotEmpty()) store.update(accountId) { a -> a.copy(devices = a.devices.filter { it.token !in gone }) }
    }

    private fun withDevice(devices: List<Device>, info: DeviceInfo, now: Long): List<Device> {
        val existing = devices.firstOrNull { it.token == info.token }
        val others = devices.filter { it.token != info.token }
        return others + Device(info.token, info.name.take(60), existing?.addedAt ?: now, now)
    }
}

data class DeviceInfo(val token: String, val name: String)
