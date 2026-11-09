package com.kayloh20.kyliemail.data.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kayloh20.kyliemail.KylieMailApp
import com.kayloh20.kyliemail.data.sync.UserAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Handles Mark as read, Archive and Delete tapped on a notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
        val action = intent.getStringExtra(EXTRA_ACTION)?.let { name -> UserAction.entries.firstOrNull { it.name == name } }
        val target = intent.getLongExtra(EXTRA_TARGET_FOLDER, -1).takeIf { it >= 0 }
        if (messageId < 0 || action == null || (action == UserAction.MOVE && target == null)) return

        val container = (context.applicationContext as KylieMailApp).container
        container.notifier.cancel(messageId)
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (action == UserAction.MOVE) {
                    // No undo bar on a notification, so the move goes straight to the server.
                    container.repository.performAll(listOf(messageId), action, target, undoable = false)
                } else {
                    container.repository.perform(messageId, action)
                }
                // Send it now: the app may not be running to send it later.
                withTimeoutOrNull(8_000) { container.syncEngine.flushActions() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_ACTION = "action"
        const val EXTRA_TARGET_FOLDER = "target_folder"
    }
}
