package com.kayloh20.kyliemail.relay

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Folder
import jakarta.mail.event.MessageCountAdapter
import jakarta.mail.event.MessageCountEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore

/** How long the watcher waits at each step. Tests shorten these. */
data class WatchTiming(
    val keepAliveMs: Long = 5 * 60_000L,
    val keepAliveAnswerMs: Long = 30_000L,
    val idleReadTimeoutMs: Long = 25 * 60_000L,
    val firstBackoffMs: Long = 5_000L,
    val maxBackoffMs: Long = 5 * 60_000L,
    val debounceMs: Long = 2_000L,
    /** Without IDLE, how often to look for new mail. */
    val pollMs: Long = 60_000L,
)

/**
 * Keeps one connection to an inbox and calls [onNewMail] when mail arrives, including mail that
 * arrived while it was disconnected. Never reads any message: only counts and UIDs.
 */
class Watcher(
    private val imap: Imap,
    private val username: String,
    private val password: String,
    private val timing: WatchTiming,
    /** The inbox's UIDVALIDITY and next UID last time, to spot what's new after a reconnect. */
    initiallyKnown: Pair<Long, Long>,
    private val onSeen: (uidValidity: Long, uidNext: Long) -> Unit,
    private val onNewMail: () -> Unit,
    private val onPasswordRejected: () -> Unit,
) {
    @Volatile private var known: Pair<Long, Long> = initiallyKnown
    @Volatile private var store: IMAPStore? = null
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        job?.cancel()
        store?.let(imap::kill)
    }

    private suspend fun CoroutineScope.run() {
        var backoff = timing.firstBackoffMs
        val changes = Channel<Unit>(Channel.CONFLATED)
        // One push per burst of new mail.
        launch {
            for (change in changes) {
                delay(timing.debounceMs)
                changes.tryReceive()
                runCatching(onNewMail)
            }
        }
        while (isActive) {
            try {
                val connected = imap.connect(username, password, timing.idleReadTimeoutMs)
                store = connected
                val inbox = connected.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                backoff = timing.firstBackoffMs
                catchUp(inbox, changes)
                inbox.addMessageCountListener(object : MessageCountAdapter() {
                    override fun messagesAdded(e: MessageCountEvent) {
                        changes.trySend(Unit)
                        // The folder's UIDNEXT isn't refreshed while open, so work it out from what arrived.
                        runCatching { e.messages.maxOf { inbox.getUID(it) } + 1 }.getOrNull()
                            ?.let { next -> if (next > known.second) remember(known.first, next) }
                    }
                })
                if (connected.hasCapability("IDLE")) idle(connected, inbox, changes) else poll(inbox)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationFailedException) {
                onPasswordRejected()
                return
            } catch (e: Exception) {
                // Dropped, timed out, or the server went away: try again shortly.
            } finally {
                store?.let(imap::kill)
                store = null
            }
            if (!isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(timing.maxBackoffMs)
        }
    }

    /** Tells about anything that arrived while not connected, then remembers where things stand. */
    private fun catchUp(inbox: IMAPFolder, changes: Channel<Unit>) {
        val validity = inbox.uidValidity
        val next = inbox.uidNext
        val (knownValidity, knownNext) = known
        if (knownNext > 0 && validity == knownValidity && next > knownNext) changes.trySend(Unit)
        remember(validity, next)
    }

    private fun remember(validity: Long, next: Long) {
        if (known != validity to next) {
            known = validity to next
            onSeen(validity, next)
        }
    }

    private suspend fun CoroutineScope.idle(connected: IMAPStore, inbox: IMAPFolder, changes: Channel<Unit>) {
        // Servers end a quiet IDLE after about 30 minutes, and a connection can die without a word.
        // Poking it regularly catches both; no answer in time means it's dead, so cut it.
        val keepAlive = launch {
            while (isActive) {
                delay(timing.keepAliveMs)
                val watchdog = launch {
                    delay(timing.keepAliveAnswerMs)
                    imap.kill(connected)
                }
                runCatching { inbox.doCommand { it.simpleCommand("NOOP", null); null } }
                watchdog.cancel()
            }
        }
        try {
            while (isActive && inbox.isOpen) inbox.idle(true)
        } finally {
            keepAlive.cancel()
        }
    }

    private suspend fun CoroutineScope.poll(inbox: IMAPFolder) {
        while (isActive && inbox.isOpen) {
            delay(timing.pollMs)
            // NOOP lets the server report new mail, which the listener above picks up.
            inbox.doCommand { it.simpleCommand("NOOP", null); null }
        }
    }
}
