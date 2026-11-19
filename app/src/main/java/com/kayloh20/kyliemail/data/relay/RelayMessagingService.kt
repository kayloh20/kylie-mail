package com.kayloh20.kyliemail.data.relay

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.kayloh20.kyliemail.KylieMailApp
import com.kayloh20.kyliemail.R
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.notify.MailNotifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Firebase push addresses. Firebase only starts, and talks to Google, once the relay is set up. */
class FirebasePushTokens(private val context: Context) : PushTokens {
    override suspend fun token(): String {
        start(context)
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        return suspendCancellableCoroutine { continuation ->
            messaging.token
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }

    override suspend fun delete() {
        start(context)
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = false
        suspendCancellableCoroutine { continuation ->
            messaging.deleteToken()
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
    }
}

/** Starts Firebase if it isn't running yet. Never called unless the relay is in use. */
fun startFirebase(context: Context) = start(context)

private fun start(context: Context) {
    if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
}

/** Where the relay's "new mail" pushes land. The push carries nothing but that; the mail is fetched here. */
class RelayMessagingService : FirebaseMessagingService() {
    private val container get() = (application as KylieMailApp).container

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["t"] != "mail") return
        container.pushRelay.heardFrom()
        RelayWakeWorker.enqueue(this)
    }

    override fun onNewToken(token: String) {
        // Firebase changed this device's push address: tell the relay.
        if (container.pushRelay.isSetUp) container.appScope.launch { container.pushRelay.checkIn() }
    }
}

/** Syncs the inbox right after a push, which posts the notification as usual. */
class RelayWakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as KylieMailApp).container
        val inbox = container.database.folders().getByRole(FolderRole.INBOX) ?: return Result.success()
        container.syncEngine.syncFolder(inbox.id)
        return Result.success()
    }

    /** Only used on Android 11 and older, which shows expedited work as a brief notification. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, MailNotifier.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("Checking for new mail")
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 7301

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RelayWakeWorker>()
                // High-priority pushes may start expedited work; if that's used up, run it normally.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("relay-wake", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
