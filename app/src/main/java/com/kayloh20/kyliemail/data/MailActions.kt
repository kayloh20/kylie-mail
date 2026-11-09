package com.kayloh20.kyliemail.data

import androidx.room.withTransaction
import com.kayloh20.kyliemail.data.db.ActionType
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.PendingAction
import com.kayloh20.kyliemail.data.sync.ActionPlanner
import com.kayloh20.kyliemail.data.sync.SyncEngine
import com.kayloh20.kyliemail.data.sync.UserAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Applies a user's change to the local database right away and queues it for the server,
 * so it works offline and the UI never waits on the network.
 */
class MailActions(
    private val db: MailDatabase,
    private val syncEngine: SyncEngine,
    private val scope: CoroutineScope,
    private val onMessageHandled: (messageId: Long) -> Unit,
) {
    private val folderDao = db.folders()
    private val messageDao = db.messages()
    private val actionDao = db.actions()
    private var flushJob: Job? = null
    @Volatile private var flushAgain = false

    /** Returns false when the action doesn't apply, such as archiving with no Archive folder. */
    suspend fun perform(messageId: Long, action: UserAction): Boolean {
        val message = messageDao.getById(messageId) ?: return false
        val folder = folderDao.getById(message.folderId) ?: return false
        val roles = folderDao.getAll().filter { it.selectable }.map { it.role }.toSet()
        val plan = ActionPlanner.plan(action, folder.role, roles) ?: return false
        val target = plan.targetRole?.let { folderDao.getByRole(it) ?: return false }

        // Skip no-op flag changes so they don't queue server work.
        when (plan.type) {
            ActionType.MARK_READ -> if (message.isSeen) return true
            ActionType.MARK_UNREAD -> if (!message.isSeen) return true
            ActionType.FLAG -> if (message.isFlagged) return true
            ActionType.UNFLAG -> if (!message.isFlagged) return true
            else -> Unit
        }

        db.withTransaction {
            when (plan.type) {
                ActionType.MARK_READ -> {
                    messageDao.setSeen(messageId, true)
                    folderDao.adjustUnread(folder.id, -1)
                }
                ActionType.MARK_UNREAD -> {
                    messageDao.setSeen(messageId, false)
                    folderDao.adjustUnread(folder.id, 1)
                }
                ActionType.FLAG -> messageDao.setFlagged(messageId, true)
                ActionType.UNFLAG -> messageDao.setFlagged(messageId, false)
                ActionType.MOVE, ActionType.DELETE_PERMANENTLY -> {
                    messageDao.deleteById(messageId)
                    if (!message.isSeen) folderDao.adjustUnread(folder.id, -1)
                }
            }
            actionDao.insert(
                PendingAction(
                    type = plan.type,
                    folderName = folder.fullName,
                    uidValidity = folder.uidValidity,
                    uid = message.uid,
                    targetFolderName = target?.fullName,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        if (plan.type != ActionType.MARK_UNREAD && plan.type != ActionType.FLAG && plan.type != ActionType.UNFLAG) {
            onMessageHandled(messageId)
        }
        requestFlush()
        return true
    }

    /** Sends queued changes now. An action queued while a send is running gets another round right after. */
    fun requestFlush() {
        synchronized(this) {
            if (flushJob?.isActive == true) {
                flushAgain = true
                return
            }
            flushJob = scope.launch {
                do {
                    flushAgain = false
                    syncEngine.flushActions()
                } while (flushAgain)
            }
        }
    }
}
