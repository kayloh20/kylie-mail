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
import kotlinx.coroutines.flow.flowOf
import com.kayloh20.kyliemail.data.search.SearchQuery
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

private const val SEARCH_LIMIT = 300
private const val SERVER_RESULTS_PER_FOLDER = 50

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
    private val attachmentFiles: com.kayloh20.kyliemail.data.mail.AttachmentFiles? = null,
    /** Reconnects the new mail watcher. */
    private val restartWatcher: () -> Unit = {},
) {
    @Volatile private var lastStartOver = 0L

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

    /** Messages on the device matching [query], in [folderId] or everywhere. */
    fun search(query: SearchQuery, folderId: Long?): Flow<List<MessageSummary>> =
        query.fts?.let { db.messages().search(it, folderId, SEARCH_LIMIT) } ?: flowOf(emptyList())

    fun summaries(ids: Collection<Long>): Flow<List<MessageSummary>> =
        if (ids.isEmpty()) flowOf(emptyList()) else db.messages().observeSummariesByIds(ids.toList())

    /** Asks iCloud to search [folders] for mail not on the device yet. */
    suspend fun searchServer(
        query: SearchQuery,
        folders: List<FolderEntity>,
        onProgress: (Int, Int) -> Unit,
    ): com.kayloh20.kyliemail.data.sync.ServerSearchResult =
        syncEngine.searchServer(query.serverTerms, folders, SERVER_RESULTS_PER_FOLDER, onProgress)

    fun attachments(messageId: Long): Flow<List<com.kayloh20.kyliemail.data.db.AttachmentEntity>> = db.attachments().observeFor(messageId)

    suspend fun refresh(focusFolderId: Long?): SyncOutcome = syncEngine.sync(focusFolderId)

    /**
     * Refresh pressed: start over with fresh connections, so a sync stuck on a dead one retries
     * straight away and the new mail watcher reconnects, then sync. A few quick presses in a row
     * count as one, so they don't keep cutting off the sync they started.
     */
    suspend fun refreshFromScratch(focusFolderId: Long?): SyncOutcome {
        val now = System.currentTimeMillis()
        if (now - lastStartOver > START_OVER_GAP_MS) {
            lastStartOver = now
            syncEngine.startOver()
            restartWatcher()
        }
        return syncEngine.sync(focusFolderId)
    }

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
            attachmentFiles?.clear()
        }
        syncEngine.disconnect()
    }

    private companion object {
        const val START_OVER_GAP_MS = 5_000L
    }
}
