package com.kayloh20.kyliemail.data

import androidx.room.withTransaction
import com.kayloh20.kyliemail.data.db.ActionType
import com.kayloh20.kyliemail.data.db.AttachmentEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.db.PendingAction
import com.kayloh20.kyliemail.data.filing.FilingEngine
import com.kayloh20.kyliemail.data.sync.ActionPlanner
import com.kayloh20.kyliemail.data.sync.SyncEngine
import com.kayloh20.kyliemail.data.sync.UserAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What [MailActions.undo] needs to put things back. */
class UndoToken internal constructor(
    internal val actionIds: List<Long>,
    internal val removed: List<MessageEntity>,
    internal val filingExampleIds: List<Long> = emptyList(),
    internal val attachments: List<AttachmentEntity> = emptyList(),
)

data class ActionResult(
    /** How many messages the action applied to. */
    val applied: Int,
    /** Present when the action removed messages and can still be undone. */
    val undo: UndoToken?,
    /** Where the messages went, for moves and archives. */
    val targetName: String? = null,
)

/**
 * Applies a user's change to the local database right away and queues it for the server,
 * so it works offline and the UI never waits on the network. Moves and deletes made in the
 * app are held for [UNDO_WINDOW_MS] before they are sent, so they can be undone.
 */
class MailActions(
    private val db: MailDatabase,
    private val syncEngine: SyncEngine,
    private val scope: CoroutineScope,
    private val moveHistory: MoveHistory,
    private val filing: FilingEngine,
    private val onMessageHandled: (messageId: Long) -> Unit,
) {
    private val folderDao = db.folders()
    private val messageDao = db.messages()
    private val actionDao = db.actions()
    private val attachmentDao = db.attachments()
    private var flushJob: Job? = null
    @Volatile private var flushAgain = false

    suspend fun perform(messageId: Long, action: UserAction): Boolean =
        perform(listOf(messageId), action, undoable = false).applied > 0

    /**
     * Applies [action] to [messageIds]. For [UserAction.MOVE], [targetFolderId] names the destination.
     * With [undoable], removals are held back so the returned token can undo them.
     */
    suspend fun perform(
        messageIds: List<Long>,
        action: UserAction,
        targetFolderId: Long? = null,
        undoable: Boolean = true,
    ): ActionResult {
        val folders = folderDao.getAll()
        val roles = folders.filter { it.selectable }.map { it.role }.toSet()
        val chosenTarget = targetFolderId?.let { id -> folders.firstOrNull { it.id == id && it.selectable } }
        if (action == UserAction.MOVE && chosenTarget == null) return ActionResult(0, null)

        val actionIds = mutableListOf<Long>()
        val removed = mutableListOf<MessageEntity>()
        val removedAttachments = mutableListOf<AttachmentEntity>()
        val handled = mutableListOf<Long>()
        var targetName: String? = null
        var targetFullName: String? = null
        val now = System.currentTimeMillis()

        db.withTransaction {
            for (message in messageDao.getByIds(messageIds)) {
                val folder = folders.firstOrNull { it.id == message.folderId } ?: continue
                val plan = ActionPlanner.plan(action, folder.role, roles) ?: continue
                val target = chosenTarget ?: plan.targetRole?.let { role -> folders.firstOrNull { it.role == role } }
                if (plan.type == ActionType.MOVE && (target == null || target.id == folder.id)) continue

                // Skip flag changes that are already true.
                val noOp = when (plan.type) {
                    ActionType.MARK_READ -> message.isSeen
                    ActionType.MARK_UNREAD -> !message.isSeen
                    ActionType.FLAG -> message.isFlagged
                    ActionType.UNFLAG -> !message.isFlagged
                    else -> false
                }
                if (noOp) continue

                when (plan.type) {
                    ActionType.MARK_READ -> {
                        messageDao.setSeen(message.id, true)
                        folderDao.adjustUnread(folder.id, -1)
                        handled += message.id
                    }
                    ActionType.MARK_UNREAD -> {
                        messageDao.setSeen(message.id, false)
                        folderDao.adjustUnread(folder.id, 1)
                    }
                    ActionType.FLAG -> messageDao.setFlagged(message.id, true)
                    ActionType.UNFLAG -> messageDao.setFlagged(message.id, false)
                    ActionType.MOVE, ActionType.DELETE_PERMANENTLY -> {
                        // Kept for Undo: deleting the message also deletes its attachment list.
                        removedAttachments += attachmentDao.forMessage(message.id)
                        messageDao.deleteById(message.id)
                        if (!message.isSeen) folderDao.adjustUnread(folder.id, -1)
                        removed += message
                        handled += message.id
                    }
                }
                targetName = target?.displayName
                if (plan.type == ActionType.MOVE && action != UserAction.DELETE) targetFullName = target?.fullName
                actionIds += actionDao.insert(
                    PendingAction(
                        type = plan.type,
                        folderName = folder.fullName,
                        uidValidity = folder.uidValidity,
                        uid = message.uid,
                        targetFolderName = target?.fullName,
                        createdAt = now,
                        notBefore = if (undoable && plan.removesFromFolder) now + UNDO_WINDOW_MS else 0,
                    ),
                )
            }
        }

        if (actionIds.isEmpty()) return ActionResult(0, null)
        handled.forEach(onMessageHandled)
        if (action == UserAction.MOVE) chosenTarget?.let { moveHistory.record(it.fullName) }
        // Filing a message teaches smart filing where this kind of mail goes.
        val learned = targetFullName?.let { filing.recordMoves(removed, it) }.orEmpty()

        val held = undoable && removed.isNotEmpty()
        if (held) {
            // Send once the undo window has passed. If the app is closed first, the next sync sends it.
            scope.launch {
                delay(UNDO_WINDOW_MS + 250)
                requestFlush()
            }
        } else {
            requestFlush()
        }
        return ActionResult(actionIds.size, if (held) UndoToken(actionIds, removed, learned, removedAttachments) else null, targetName)
    }

    /** Puts removed messages back if their actions haven't been sent yet. Returns false if it was too late. */
    suspend fun undo(token: UndoToken): Boolean {
        val restored = db.withTransaction {
            val cancelled = actionDao.deleteAll(token.actionIds)
            if (cancelled != token.actionIds.size) return@withTransaction false
            messageDao.insertAll(token.removed)
            if (token.attachments.isNotEmpty()) attachmentDao.insertAll(token.attachments)
            token.removed.filter { !it.isSeen }.groupingBy { it.folderId }.eachCount().forEach { (folderId, count) ->
                folderDao.adjustUnread(folderId, count)
            }
            true
        }
        // An undone move isn't a lesson.
        if (restored) filing.forgetMoves(token.filingExampleIds)
        return restored
    }

    /** Sends ready changes now. An action queued while a send is running gets another round right after. */
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

    companion object {
        const val UNDO_WINDOW_MS = 6_000L
    }
}
