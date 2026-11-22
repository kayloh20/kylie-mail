package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger
import javax.mail.Folder
import javax.mail.event.MessageChangedEvent
import javax.mail.event.MessageChangedListener
import javax.mail.event.MessageCountEvent
import javax.mail.event.MessageCountListener

/**
 * Hears about new mail the moment it arrives, as the app does: a connection of its own sits in
 * IMAP IDLE on the inbox, and anything that changes there (mail arriving, being removed, read or
 * flagged elsewhere) is passed on to [changes]. It reconnects when the connection drops, renews
 * IDLE every [RENEW_MS] as the IMAP standard asks, and stops by itself once no page has listened
 * for [UNUSED_MS].
 */
class InboxWatcher(
    private val connector: ImapConnector,
    private val username: String,
    private val password: () -> String?,
    private val folderName: String = "INBOX",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    /** Pages listening right now; see [listen]. */
    private val listeners = AtomicInteger()
    @Volatile private var lastListened = now()
    @Volatile private var lastRenewed = now()
    @Volatile private var stopped = false
    @Volatile private var store: IMAPStore? = null
    @Volatile private var folder: IMAPFolder? = null
    @Volatile var supported = true
        private set

    private val thread = Thread(::run, "inbox-watcher").apply { isDaemon = true }
    private val renewer = Thread(::renew, "inbox-watcher-renew").apply { isDaemon = true }

    val running: Boolean get() = !stopped

    fun start() {
        thread.start()
        renewer.start()
    }

    /** A page starts listening; call the returned function when it stops. */
    fun listen(): () -> Unit {
        listeners.incrementAndGet()
        lastListened = now()
        return {
            listeners.decrementAndGet()
            lastListened = now()
        }
    }

    fun stop() {
        stopped = true
        store?.let(connector::kill)
        thread.interrupt()
        renewer.interrupt()
    }

    private fun run() {
        var failures = 0
        var connectedBefore = false
        while (!stopped) {
            try {
                val pw = password() ?: return stop()
                val s = connector.connect(username, pw, readTimeoutMs = READ_TIMEOUT_MS)
                store = s
                if (!s.hasCapability("IDLE")) {
                    // Without IDLE, the page's regular checks are all there is.
                    supported = false
                    return stop()
                }
                val f = s.getFolder(folderName) as IMAPFolder
                f.open(Folder.READ_ONLY)
                folder = f
                f.addMessageCountListener(object : MessageCountListener {
                    override fun messagesAdded(e: MessageCountEvent) = changed()
                    override fun messagesRemoved(e: MessageCountEvent) = changed()
                })
                f.addMessageChangedListener(MessageChangedListener { _: MessageChangedEvent -> changed() })
                failures = 0
                lastRenewed = now()
                // A reconnect may have missed something; let the page look again.
                if (connectedBefore) changed()
                connectedBefore = true
                while (!stopped && f.isOpen) f.idle()
            } catch (e: Exception) {
                if (stopped) break
                failures++
            } finally {
                folder = null
                store?.let(connector::kill)
                store = null
            }
            if (!stopped) {
                try {
                    Thread.sleep(BACKOFF_MS.getOrElse(failures) { BACKOFF_MS.last() })
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    /** Renews IDLE now and then, and stops watching once nobody has listened for a while. */
    private fun renew() {
        while (!stopped) {
            try {
                Thread.sleep(TICK_MS)
            } catch (e: InterruptedException) {
                return
            }
            if (listeners.get() == 0 && now() - lastListened > UNUSED_MS) return stop()
            val f = folder ?: continue
            if (now() - lastRenewed >= RENEW_MS) {
                lastRenewed = now()
                // Any other command ends the current IDLE; the watcher then starts a fresh one.
                runCatching { f.messageCount }
            }
        }
    }

    private fun changed() {
        _changes.tryEmit(folderName)
    }

    companion object {
        /** iCloud may close an IDLE left alone for 30 minutes; renew well before. */
        const val RENEW_MS = 20 * 60 * 1000L
        /** How long the connection may stay quiet: longer than IDLE is ever left running. */
        private const val READ_TIMEOUT_MS = 25 * 60 * 1000L
        private const val UNUSED_MS = 5 * 60 * 1000L
        private const val TICK_MS = 30 * 1000L
        private val BACKOFF_MS = listOf(2_000L, 5_000L, 15_000L, 60_000L, 300_000L)
    }
}
