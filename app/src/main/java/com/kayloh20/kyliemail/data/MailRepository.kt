package com.kayloh20.kyliemail.data

import com.kayloh20.kyliemail.data.account.Account
import com.kayloh20.kyliemail.data.account.CredentialStore
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.db.MessageSummary
import com.kayloh20.kyliemail.data.sync.SyncEngine
import com.kayloh20.kyliemail.data.sync.SyncOutcome
import com.kayloh20.kyliemail.data.sync.SyncScheduler
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
) {
    val folders: Flow<List<FolderEntity>> = db.folders().observeAll()
    val syncState: StateFlow<SyncState> = syncEngine.state

    fun messages(folderId: Long): Flow<List<MessageSummary>> = db.messages().observeSummaries(folderId)

    fun message(id: Long): Flow<MessageEntity?> = db.messages().observe(id)

    suspend fun refresh(focusFolderId: Long?): SyncOutcome = syncEngine.sync(focusFolderId)

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
        }
    }
}
