package com.kayloh20.kyliemail.data

import com.kayloh20.kyliemail.data.account.Account
import com.kayloh20.kyliemail.data.account.CredentialStore
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.db.MessageSummary
import com.kayloh20.kyliemail.data.mail.InlineImageStore
import com.kayloh20.kyliemail.data.notify.MailNotifier
import com.kayloh20.kyliemail.data.sync.SyncEngine
import com.kayloh20.kyliemail.data.sync.SyncOutcome
import com.kayloh20.kyliemail.data.sync.SyncScheduler
import com.kayloh20.kyliemail.data.sync.UserAction
import com.kayloh20.kyliemail.data.sync.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class MailRepository(
    private val db: MailDatabase,
    private val credentials: CredentialStore,
    private val syncEngine: SyncEngine,
    private val scheduler: SyncScheduler,
    val inlineImages: InlineImageStore,
    private val imageTrust: ImageTrust,
    private val actions: MailActions,
    private val notifier: MailNotifier,
    private val moveHistory: MoveHistory,
) {
    suspend fun perform(messageId: Long, action: UserAction): Boolean = actions.perform(messageId, action)

    suspend fun performAll(
        messageIds: List<Long>,
        action: UserAction,
        targetFolderId: Long? = null,
        undoable: Boolean = true,
    ): ActionResult = actions.perform(messageIds, action, targetFolderId, undoable)

    suspend fun undo(token: UndoToken): Boolean = actions.undo(token)

    /** Folders recently moved to, newest first, by full name. */
    val recentMoveTargets: StateFlow<List<String>> = moveHistory.recent

    suspend fun messageById(id: Long): MessageEntity? = db.messages().getById(id)

    /** Senders whose remote images load automatically. */
    val trustedImageSenders: StateFlow<Set<String>> = imageTrust.senders

    fun trustImageSender(address: String) = imageTrust.trust(address)

    val folders: Flow<List<FolderEntity>> = db.folders().observeAll()
    val syncState: StateFlow<SyncState> = syncEngine.state

    fun messages(folderId: Long): Flow<List<MessageSummary>> = db.messages().observeSummaries(folderId)

    fun message(id: Long): Flow<MessageEntity?> = db.messages().observe(id)

    suspend fun refresh(focusFolderId: Long?): SyncOutcome = syncEngine.sync(focusFolderId)

    suspend fun refreshFolder(folderId: Long): SyncOutcome = syncEngine.syncFolder(folderId)

    suspend fun loadBody(messageId: Long): SyncOutcome = syncEngine.loadBody(messageId)

    fun signIn(account: Account) {
        credentials.save(account)
        scheduler.schedulePeriodic()
    }

    suspend fun signOut() {
        scheduler.cancel()
        syncEngine.exclusive {
            withContext(Dispatchers.IO) { db.clearAllTables() }
            credentials.clear()
            inlineImages.clear()
            imageTrust.clear()
            notifier.clearAll()
            moveHistory.clear()
        }
        syncEngine.disconnect()
    }
}
