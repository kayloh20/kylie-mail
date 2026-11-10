package com.kayloh20.kyliemail.data.push

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kayloh20.kyliemail.KylieMailApp
import com.kayloh20.kyliemail.MainActivity
import com.kayloh20.kyliemail.R
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.notify.MailNotifier
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.mail.AuthenticationFailedException
import javax.mail.Folder
import javax.mail.event.MessageChangedListener
import javax.mail.event.MessageCountEvent
import javax.mail.event.MessageCountListener

/**
 * Keeps an IMAP IDLE connection to the inbox open so iCloud can announce new mail the moment it
 * arrives. Reconnects with backoff, immediately when the network comes back, and re-issues IDLE
 * well before servers drop quiet connections. The periodic background sync remains as a safety net.
 */
class MailPushService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var idleStore: IMAPStore? = null

    private val container get() = (application as KylieMailApp).container

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wake.trySend(Unit)
        }

        override fun onLost(network: Network) {
            // The socket is dead; closing it ends the blocking IDLE so the loop can wait for a network.
            closeIdleStore()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showOngoing()
        if (container.credentials.account.value == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.cancel()
        closeIdleStore()
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun run() {
        var backoff = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            val account = container.credentials.account.value ?: break
            try {
                val store = container.connector.connect(account.username, account.password, readTimeoutMs = IDLE_READ_TIMEOUT_MS)
                idleStore = store
                if (!store.hasCapability("IDLE")) {
                    // Without IDLE there is nothing to keep open; the periodic sync covers it.
                    stopSelf()
                    return
                }
                val inbox = store.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                backoff = INITIAL_BACKOFF_MS
                idleUntilClosed(inbox)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationFailedException) {
                stopSelf()
                return
            } catch (e: Exception) {
                // Dropped connection, timeout, no network: back off and try again.
            } finally {
                closeIdleStore()
            }
            if (!currentCoroutineContext().isActive) break
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            if (backoff > INITIAL_BACKOFF_MS && isOnline()) backoff = backoff.coerceAtMost(ONLINE_MAX_BACKOFF_MS)
        }
        stopSelf()
    }

    private suspend fun idleUntilClosed(inbox: IMAPFolder) {
        val changes = Channel<Unit>(Channel.CONFLATED)
        inbox.addMessageCountListener(object : MessageCountListener {
            override fun messagesAdded(e: MessageCountEvent) { changes.trySend(Unit) }
            override fun messagesRemoved(e: MessageCountEvent) { changes.trySend(Unit) }
        })
        inbox.addMessageChangedListener(MessageChangedListener { changes.trySend(Unit) })

        // Catch up on anything that arrived while disconnected.
        changes.trySend(Unit)
        val syncer = scope.launch {
            for (change in changes) {
                delay(DEBOUNCE_MS)
                val inboxFolder = container.database.folders().getByRole(FolderRole.INBOX) ?: continue
                container.syncEngine.syncFolder(inboxFolder.id)
            }
        }
        // Servers end IDLE after about 30 minutes of silence, so restart it well before that.
        val keepAlive = scope.launch {
            while (isActive) {
                delay(REIDLE_INTERVAL_MS)
                runCatching { inbox.doCommand { protocol -> protocol.simpleCommand("NOOP", null); null } }
            }
        }

        try {
            while (currentCoroutineContext().isActive && inbox.isOpen) {
                inbox.idle(true)
            }
        } finally {
            syncer.cancel()
            keepAlive.cancel()
        }
    }

    private fun closeIdleStore() {
        val store = idleStore ?: return
        idleStore = null
        runCatching { store.close() }
    }

    private fun isOnline(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return true
        return manager.activeNetwork != null
    }

    /**
     * The notification Android requires while the connection is kept open. It's posted once and never
     * changes (status updates made it pop and flicker), and its Hide button goes straight to the
     * setting that hides it, which leaves the connection working.
     */
    private fun showOngoing() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, MailNotifier.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("Kylie Mail")
            .setContentText("Watching for new mail")
            .addAction(0, "Hide", hideIntent())
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, MailNotifier.ID_CONNECTION, notification, type)
        } catch (e: Exception) {
            // Not allowed to run in the foreground right now; the periodic sync takes over.
            stopSelf()
        }
    }

    private fun hideIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        1,
        connectionNotificationSettings(this),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** Android's settings page for just the "Connection status" notification. */
        fun connectionNotificationSettings(context: Context): Intent =
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, MailNotifier.CHANNEL_CONNECTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 15 * 60_000L
        private const val ONLINE_MAX_BACKOFF_MS = 5 * 60_000L
        private const val DEBOUNCE_MS = 1_500L
        private const val REIDLE_INTERVAL_MS = 20 * 60_000L
        // Longer than the re-IDLE interval, so a dead connection is noticed within about 25 minutes.
        private const val IDLE_READ_TIMEOUT_MS = 25 * 60_000L

        fun start(context: Context) {
            // Turned off on this device: the 15-minute check still delivers notifications.
            val settings = (context.applicationContext as KylieMailApp).container.settings
            if (!settings.instantNotifications.value) {
                stop(context)
                return
            }
            try {
                ContextCompat.startForegroundService(context, Intent(context, MailPushService::class.java))
            } catch (e: IllegalStateException) {
                // Apps in the background may not start foreground services; the periodic sync covers it.
            } catch (e: SecurityException) {
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MailPushService::class.java))
        }
    }
}
