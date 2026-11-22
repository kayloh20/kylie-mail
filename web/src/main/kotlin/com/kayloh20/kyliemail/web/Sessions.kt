package com.kayloh20.kyliemail.web

import java.util.concurrent.ConcurrentHashMap

/** A signed-in browser. It holds the account key, in memory only, until it signs out or expires. */
class Session(val accountKey: ByteArray, val createdAt: Long, var lastSeenAt: Long, val viaRecoveryCode: Boolean) {
    /** This browser's connection to iCloud, opened on first use and closed when the session ends. */
    @Volatile var mail: MailClient? = null

    /** Smart filing's memory, opened on first use and forgotten when the session ends. */
    @Volatile var filing: Filing? = null

    /** Mail being sent, and files attached while writing. */
    @Volatile var outbox: Outbox? = null
}

/**
 * Signed-in browsers, kept only in memory: restarting the server signs everyone out, and nothing
 * on disk can open your mail. A session ends after [IDLE_MS] without use or [MAX_MS] in all.
 */
class Sessions(private val now: () -> Long = System::currentTimeMillis) {
    private val sessions = ConcurrentHashMap<String, Session>()

    fun create(accountKey: ByteArray, viaRecoveryCode: Boolean = false): String {
        prune()
        while (sessions.size >= MAX_SESSIONS) {
            sessions.entries.minByOrNull { it.value.lastSeenAt }?.key?.let(::drop)
        }
        val token = Crypto.b64url(Crypto.randomBytes(32))
        val time = now()
        sessions[key(token)] = Session(accountKey.copyOf(), time, time, viaRecoveryCode)
        return token
    }

    /** The session for [token], if it's still valid, marked as just used. */
    fun get(token: String?): Session? {
        if (token.isNullOrEmpty()) return null
        val k = key(token)
        val session = sessions[k] ?: return null
        val time = now()
        if (time - session.lastSeenAt > IDLE_MS || time - session.createdAt > MAX_MS) {
            drop(k)
            return null
        }
        session.lastSeenAt = time
        return session
    }

    fun end(token: String) = drop(key(token))

    /** Signs out every browser. */
    fun endAll() = sessions.keys.toList().forEach(::drop)

    /** Removes a session by its hashed key and wipes its copy of the account key. */
    private fun drop(k: String) {
        val session = sessions.remove(k) ?: return
        // Filing stops first: it must never save with the key wiped below.
        session.filing?.let { runCatching { it.close() } }
        session.filing = null
        // Mail you pressed Send on still goes out, right away, before the connection closes.
        val outbox = session.outbox
        session.outbox = null
        val mail = session.mail
        if (mail != null) {
            runCatching {
                mail.shutdown {
                    outbox?.flush()
                    outbox?.close()
                }
            }
        } else {
            outbox?.close()
        }
        session.mail = null
        session.accountKey.fill(0)
    }

    /** Ends expired sessions, closing their connections. */
    fun prune() {
        val time = now()
        sessions.entries.filter { time - it.value.lastSeenAt > IDLE_MS || time - it.value.createdAt > MAX_MS }
            .forEach { drop(it.key) }
    }

    /** Sessions are found by a hash of the token, so the map never holds a usable token. */
    private fun key(token: String) = Crypto.b64url(Crypto.sha256(token.toByteArray(Charsets.US_ASCII)))

    companion object {
        const val IDLE_MS = 2 * 60 * 60 * 1000L
        const val MAX_MS = 12 * 60 * 60 * 1000L
        const val MAX_SESSIONS = 10
    }
}

/** Short-lived, single-use server state for a sign-in or registration in progress. */
class Pending<T : Any>(private val now: () -> Long = System::currentTimeMillis, private val lifetimeMs: Long = 5 * 60 * 1000L) {
    private class Entry<T>(val value: T, val expiresAt: Long)
    private val entries = ConcurrentHashMap<String, Entry<T>>()

    fun put(value: T): String {
        val time = now()
        entries.entries.removeIf { it.value.expiresAt < time }
        while (entries.size >= 100) entries.keys.firstOrNull()?.let(entries::remove)
        val id = Crypto.b64url(Crypto.randomBytes(18))
        entries[id] = Entry(value, time + lifetimeMs)
        return id
    }

    /** Takes the value out; it can't be used again. Null if unknown or expired. */
    fun take(id: String): T? = entries.remove(id)?.takeIf { it.expiresAt >= now() }?.value
}

/** Allows at most [limit] attempts per key in any [windowMs]. */
class RateLimiter(private val limit: Int, private val windowMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val attempts = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun allow(key: String): Boolean {
        val time = now()
        if (attempts.size > 10_000) attempts.clear()
        val list = attempts.getOrPut(key) { ArrayDeque() }
        synchronized(list) {
            while (list.isNotEmpty() && time - list.first() >= windowMs) list.removeFirst()
            if (list.size >= limit) return false
            list.addLast(time)
            return true
        }
    }
}
