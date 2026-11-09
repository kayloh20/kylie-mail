package com.kayloh20.kyliemail.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.kayloh20.kyliemail.MainActivity
import com.kayloh20.kyliemail.R
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.sync.UserAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MailNotifier(private val context: Context, private val db: MailDatabase) {
    private val manager = NotificationManagerCompat.from(context)
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIL, "New mail", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A notification for each new email in your inbox"
            },
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Connection status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Kylie Mail stays connected to iCloud for instant notifications. You can turn this off."
                setShowBadge(false)
            },
        )
    }

    /** Announces new unread inbox mail and clears notifications for mail read or removed elsewhere. */
    suspend fun onInboxSynced(inbox: FolderEntity) = mutex.withLock {
        val previous = loadBaseline()
        val unread = db.messages().unreadAfter(inbox.id, previous?.highestUid ?: inbox.highestUid)
        val plan = NotificationPlanner.plan(previous, inbox.uidValidity, inbox.highestUid, unread.map { it.uid })
        saveBaseline(plan.newBaseline)

        if (canPost() && plan.notifyUids.isNotEmpty()) {
            val byUid = unread.associateBy { it.uid }
            val canArchive = db.folders().getByRole(FolderRole.ARCHIVE) != null
            plan.notifyUids.take(MAX_INDIVIDUAL).mapNotNull(byUid::get).forEach { post(it, canArchive) }
        }
        clearHandled()
        updateSummary()
    }

    /** Removes a message's notification, for example once it has been read. */
    fun cancel(messageId: Long) {
        manager.cancel(tag(messageId), ID_MESSAGE)
        updateSummary()
    }

    fun clearAll() {
        manager.cancelAll()
        prefs.edit { clear() }
    }

    private fun canPost(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    @android.annotation.SuppressLint("MissingPermission") // Checked in canPost().
    private fun post(message: MessageEntity, canArchive: Boolean) {
        val sender = message.fromName.ifBlank { message.fromAddress.ifBlank { "Unknown sender" } }
        val subject = message.subject.ifBlank { "(no subject)" }
        val builder = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(sender)
            .setContentText(subject)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (message.preview.isBlank()) subject else "$subject\n${message.preview}",
                ),
            )
            .setWhen(message.receivedAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setColor(ACCENT)
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setContentIntent(openIntent(message.id))
            .addAction(0, "Mark as read", actionIntent(message.id, UserAction.MARK_READ))
        if (canArchive) builder.addAction(0, "Archive", actionIntent(message.id, UserAction.ARCHIVE))
        builder.addAction(0, "Delete", actionIntent(message.id, UserAction.DELETE))
        manager.notify(tag(message.id), ID_MESSAGE, builder.build())
    }

    private suspend fun clearHandled() {
        val shown = activeMessageIds()
        if (shown.isEmpty()) return
        val unread = db.messages().seenStates(shown).filter { !it.isSeen }.map { it.id }.toSet()
        shown.filter { it !in unread }.forEach { manager.cancel(tag(it), ID_MESSAGE) }
    }

    @android.annotation.SuppressLint("MissingPermission") // Checked in canPost().
    private fun updateSummary() {
        val count = activeMessageIds().size
        if (count < 2 || !canPost()) {
            manager.cancel(ID_SUMMARY)
            return
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("$count new messages")
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openIntent(null))
            .build()
        manager.notify(ID_SUMMARY, summary)
    }

    private fun activeMessageIds(): List<Long> {
        val system = context.getSystemService(NotificationManager::class.java) ?: return emptyList()
        return system.activeNotifications
            .filter { it.id == ID_MESSAGE && it.tag?.startsWith(TAG_PREFIX) == true }
            .mapNotNull { it.tag.removePrefix(TAG_PREFIX).toLongOrNull() }
    }

    private fun openIntent(messageId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_MESSAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (messageId != null) intent.putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId)
        return PendingIntent.getActivity(
            context,
            messageId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun actionIntent(messageId: Long, action: UserAction): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction("$ACTION_PREFIX${action.name}")
            .putExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID, messageId)
            .putExtra(NotificationActionReceiver.EXTRA_ACTION, action.name)
        return PendingIntent.getBroadcast(
            context,
            (messageId * 8 + action.ordinal).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun loadBaseline(): NotificationPlanner.Baseline? {
        if (!prefs.contains(KEY_VALIDITY)) return null
        return NotificationPlanner.Baseline(prefs.getLong(KEY_VALIDITY, 0), prefs.getLong(KEY_HIGHEST, 0))
    }

    private fun saveBaseline(baseline: NotificationPlanner.Baseline) {
        prefs.edit {
            putLong(KEY_VALIDITY, baseline.uidValidity)
            putLong(KEY_HIGHEST, baseline.highestUid)
        }
    }

    private fun tag(messageId: Long) = TAG_PREFIX + messageId

    companion object {
        const val CHANNEL_MAIL = "new_mail"
        private const val ACCENT = 0xFF9271DB.toInt()
        const val CHANNEL_CONNECTION = "connection"
        const val ID_CONNECTION = 100
        private const val ID_MESSAGE = 1
        private const val ID_SUMMARY = 2
        private const val TAG_PREFIX = "message:"
        private const val GROUP = "inbox"
        private const val MAX_INDIVIDUAL = 5
        private const val ACTION_PREFIX = "com.kayloh20.kyliemail.notification."
        private const val KEY_VALIDITY = "inbox_uid_validity"
        private const val KEY_HIGHEST = "inbox_highest_uid"
    }
}
