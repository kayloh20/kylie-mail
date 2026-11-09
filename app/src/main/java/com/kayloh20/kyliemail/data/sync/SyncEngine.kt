package com.kayloh20.kyliemail.data.sync

import androidx.room.withTransaction
import com.kayloh20.kyliemail.data.account.Account
import com.kayloh20.kyliemail.data.account.CredentialStore
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.db.ActionType
import com.kayloh20.kyliemail.data.db.PendingAction
import com.kayloh20.kyliemail.data.db.ExampleSource
import com.kayloh20.kyliemail.data.db.FilingExampleEntity
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.kayloh20.kyliemail.data.mail.InlineImageStore
import com.kayloh20.kyliemail.data.mail.extractBody
import com.kayloh20.kyliemail.data.mail.makePreview
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.FolderClosedException
import javax.mail.StoreClosedException
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress

sealed interface SyncOutcome {
    data object Success : SyncOutcome
    data object NotSignedIn : SyncOutcome
    data object AuthFailed : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

data class SyncState(
    val running: Boolean = false,
    val lastOutcome: SyncOutcome? = null,
    val lastSuccessAt: Long = 0,
)

class SyncEngine(
    private val db: MailDatabase,
    private val credentials: CredentialStore,
    private val connector: ImapConnector,
    private val inlineImages: InlineImageStore,
) {
    private val folderDao = db.folders()
    private val messageDao = db.messages()
    private val actionDao = db.actions()

    /** Called after the inbox has been synced, so notifications can be updated. */
    var inboxListener: (suspend (FolderEntity) -> Unit)? = null
    /** Only one header sync runs at a time. */
    private val mutex = Mutex()

    /** Guards the shared connection below. */
    private val connectionLock = Mutex()
    private var store: IMAPStore? = null
    private var storeAccount: Account? = null

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Full refresh: the folder list and unread counts, the inbox, and [focusFolderId] if given. */
    suspend fun sync(focusFolderId: Long? = null): SyncOutcome {
        val outcome = syncHeaders { store ->
            syncFolderList(store)
            listOfNotNull(
                folderDao.getByRole(FolderRole.INBOX),
                focusFolderId?.let { folderDao.getById(it) },
            ).distinctBy { it.id }
        }
        if (outcome == SyncOutcome.Success) {
            // Remove images saved for messages that have since been deleted.
            withContext(Dispatchers.IO) { runCatching { inlineImages.retainOnly(messageDao.allIds().toSet()) } }
        }
        return outcome
    }

    /** Quick refresh of a single folder, used when it is opened. */
    suspend fun syncFolder(folderId: Long): SyncOutcome = syncHeaders {
        listOfNotNull(folderDao.getById(folderId))
    }

    /** Sends queued changes (read, flag, move, delete) to the server now. */
    suspend fun flushActions(): SyncOutcome = mutex.withLock {
        if (credentials.account.value == null) return SyncOutcome.NotSignedIn
        withContext(Dispatchers.IO) { runCatchingMail { withConnection { store -> processActions(store) } } }
    }

    /** What [collectFilingExamples] read from one folder. */
    class FolderSample(val uidValidity: Long, val highestUid: Long, val examples: List<FilingExampleEntity>)

    /**
     * Reads sender, recipients and subject (never bodies) for smart filing. With [afterUid] it reads
     * only newer mail; otherwise the newest [limit] emails. Returns null if the folder is gone.
     */
    suspend fun collectFilingExamples(folderName: String, afterUid: Long, knownValidity: Long, limit: Int): FolderSample? =
        withContext(Dispatchers.IO) {
            withConnection { store ->
                val remote = store.getFolder(folderName) as IMAPFolder
                if (!remote.exists()) return@withConnection null
                remote.open(Folder.READ_ONLY)
                try {
                    val validity = remote.uidValidity
                    val count = remote.messageCount
                    val incremental = afterUid > 0 && validity == knownValidity
                    val messages: Array<Message> = when {
                        count <= 0 -> emptyArray()
                        incremental -> remote.getMessagesByUID(afterUid + 1, UIDFolder.LASTUID)
                        else -> SyncPlanner.initialSequenceRange(count, limit)?.let { remote.getMessages(it.first, it.last) } ?: emptyArray()
                    }
                    remote.fetch(
                        messages,
                        FetchProfile().apply {
                            add(FetchProfile.Item.ENVELOPE)
                            add(UIDFolder.FetchProfileItem.UID)
                        },
                    )
                    val now = System.currentTimeMillis()
                    val examples = messages.mapNotNull { message ->
                        runCatching {
                            val uid = remote.getUID(message)
                            if (incremental && uid <= afterUid) return@runCatching null
                            val sender = message.from?.firstOrNull() as? InternetAddress
                            val recipients = listOf(Message.RecipientType.TO, Message.RecipientType.CC)
                                .flatMap { type -> message.getRecipients(type)?.toList().orEmpty() }
                                .mapNotNull { (it as? InternetAddress)?.address?.lowercase() }
                            FilingExampleEntity(
                                folderName = folderName,
                                uid = uid,
                                fromAddress = sender?.address.orEmpty().lowercase(),
                                fromName = sender?.personal.orEmpty(),
                                recipients = recipients.joinToString(","),
                                subject = message.subject.orEmpty(),
                                source = ExampleSource.TRAINING,
                                createdAt = now,
                            )
                        }.getOrNull()
                    }
                    val highest = maxOf(if (incremental) afterUid else 0L, examples.maxOfOrNull { it.uid ?: 0L } ?: 0L)
                    FolderSample(validity, highest, examples)
                } finally {
                    runCatching { remote.close(false) }
                }
            }
        }

    /** Runs [block] on the shared IMAP connection, reconnecting once if it has gone stale. */
    suspend fun <T> useConnection(block: suspend (IMAPStore) -> T): T = withContext(Dispatchers.IO) { withConnection(block) }

    /** Runs [block] while no sync is in progress, for changes that must not interleave with one. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** Downloads one message's body if it is not cached yet. */
    suspend fun loadBody(messageId: Long): SyncOutcome = withContext(Dispatchers.IO) {
        val message = messageDao.getById(messageId) ?: return@withContext SyncOutcome.Success
        if (message.bodyLoaded) return@withContext SyncOutcome.Success
        if (credentials.account.value == null) return@withContext SyncOutcome.NotSignedIn
        val folder = folderDao.getById(message.folderId) ?: return@withContext SyncOutcome.Success
        runCatchingMail {
            withConnection { store ->
                withOpenFolder(store, folder) { remote -> downloadBodies(remote, listOf(message)) }
            }
        }
    }

    /** Closes the shared connection, for example on sign-out. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        connectionLock.withLock {
            runCatching { store?.close() }
            store = null
            storeAccount = null
        }
    }

    /**
     * Syncs message headers for the folders [pickFolders] returns, then downloads recent bodies.
     * The list is up to date as soon as the headers are in; bodies follow without blocking other syncs.
     */
    private suspend fun syncHeaders(pickFolders: suspend (IMAPStore) -> List<FolderEntity>): SyncOutcome {
        var synced: List<FolderEntity> = emptyList()
        val outcome = mutex.withLock {
            if (credentials.account.value == null) return SyncOutcome.NotSignedIn
            _state.update { it.copy(running = true) }
            val result = withContext(Dispatchers.IO) {
                runCatchingMail {
                    withConnection { store ->
                        // Local changes go first, so the sync doesn't undo them.
                        processActions(store)
                        val targets = pickFolders(store)
                        for (folder in targets) syncMessages(store, folder)
                        synced = targets
                    }
                }
            }
            _state.update {
                SyncState(
                    running = false,
                    lastOutcome = result,
                    lastSuccessAt = if (result == SyncOutcome.Success) System.currentTimeMillis() else it.lastSuccessAt,
                )
            }
            result
        }
        if (outcome == SyncOutcome.Success) {
            synced.firstOrNull { it.role == FolderRole.INBOX }?.let { inbox ->
                folderDao.getById(inbox.id)?.let { runCatching { inboxListener?.invoke(it) } }
            }
        }
        if (outcome == SyncOutcome.Success && synced.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                runCatchingMail { withConnection { store -> for (folder in synced) prefetchBodies(store, folder) } }
            }
        }
        return outcome
    }

    /**
     * Runs [block] on the shared connection, opening one if needed. If the connection turns out
     * to be dead (after sleep or a network change, say), it reconnects and tries once more.
     */
    private suspend fun <T> withConnection(block: suspend (IMAPStore) -> T): T {
        val account = credentials.account.value ?: throw AuthenticationFailedException("Not signed in")
        return try {
            block(connection(account))
        } catch (e: AuthenticationFailedException) {
            throw e
        } catch (e: MessagingException) {
            dropConnection()
            block(connection(account))
        } catch (e: IOException) {
            dropConnection()
            block(connection(account))
        }
    }

    private suspend fun connection(account: Account): IMAPStore = connectionLock.withLock {
        val current = store
        // isConnected sends a NOOP when the connection has been idle, so a dead socket is caught here.
        if (current != null && storeAccount == account && current.isConnected) return current
        runCatching { current?.close() }
        connector.connect(account.username, account.password).also {
            store = it
            storeAccount = account
        }
    }

    private suspend fun dropConnection() = connectionLock.withLock {
        runCatching { store?.close() }
        store = null
        storeAccount = null
    }

    private suspend fun syncFolderList(store: IMAPStore) {
        val remote = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>().map { folder ->
            val attributes = folder.attributes.toList()
            val selectable = attributes.none { it.equals("\\Noselect", ignoreCase = true) } &&
                (folder.type and Folder.HOLDS_MESSAGES) != 0
            // STATUS on a closed folder, so this stays cheap.
            val unread = if (selectable) runCatching { folder.unreadMessageCount }.getOrDefault(0) else 0
            val total = if (selectable) runCatching { folder.messageCount }.getOrDefault(0) else 0
            val separator = runCatching { folder.separator }.getOrDefault('/')
            val role = FolderRole.detect(folder.fullName, attributes)
            FolderEntity(
                fullName = folder.fullName,
                displayName = FolderRole.displayName(role, folder.name),
                role = role,
                depth = folder.fullName.count { it == separator },
                selectable = selectable,
                sortOrder = role.sortOrder,
                unreadCount = maxOf(unread, 0),
                totalCount = maxOf(total, 0),
            )
        }

        db.withTransaction {
            val local = folderDao.getAll().associateBy { it.fullName }
            for (folder in remote) {
                val existing = local[folder.fullName]
                if (existing == null) {
                    folderDao.insert(folder)
                } else {
                    folderDao.update(
                        existing.copy(
                            displayName = folder.displayName,
                            role = folder.role,
                            depth = folder.depth,
                            selectable = folder.selectable,
                            sortOrder = folder.sortOrder,
                            unreadCount = folder.unreadCount,
                            totalCount = folder.totalCount,
                        ),
                    )
                }
            }
            val remoteNames = remote.map { it.fullName }.toSet()
            val gone = local.values.filter { it.fullName !in remoteNames }.map { it.id }
            if (gone.isNotEmpty()) folderDao.deleteByIds(gone)
        }
    }

    private suspend fun syncMessages(store: IMAPStore, folder: FolderEntity) = withOpenFolder(store, folder) { remote ->
        var local = folderDao.getById(folder.id) ?: return@withOpenFolder
        val serverValidity = remote.uidValidity

        // A new UIDVALIDITY means every UID we have is meaningless, so start the folder over.
        if (local.uidValidity != serverValidity) {
            messageDao.deleteAllInFolder(local.id)
            local = local.copy(uidValidity = serverValidity, highestUid = 0)
        }

        val count = remote.messageCount
        if (count <= 0) {
            messageDao.deleteAllInFolder(local.id)
            folderDao.update(local.copy(lastSyncedAt = System.currentTimeMillis()))
            return@withOpenFolder
        }

        // 1. New mail.
        val candidates: Array<Message> = if (local.highestUid == 0L) {
            val range = SyncPlanner.initialSequenceRange(count, INITIAL_WINDOW) ?: IntRange.EMPTY
            if (range.isEmpty()) emptyArray() else remote.getMessages(range.first, range.last)
        } else {
            remote.getMessagesByUID(local.highestUid + 1, UIDFolder.LASTUID)
        }
        remote.fetch(candidates, headerProfile())
        val byUid = candidates.mapNotNull { message ->
            runCatching { remote.getUID(message) to message }.getOrNull()
        }.toMap()
        val newUids = SyncPlanner.newUids(byUid.keys, local.highestUid)
        val newEntities = newUids.mapNotNull { uid ->
            runCatching { byUid.getValue(uid).toEntity(local.id, uid) }.getOrNull()
        }
        messageDao.insertAll(newEntities)
        val highest = maxOf(local.highestUid, newUids.maxOrNull() ?: 0L)

        // 2. Flag changes and deletions for everything we hold.
        val localFlags = messageDao.localFlags(local.id)
        if (localFlags.isNotEmpty()) {
            val oldest = localFlags.minOf { it.uid }
            val serverMessages = remote.getMessagesByUID(oldest, UIDFolder.LASTUID)
            remote.fetch(
                serverMessages,
                FetchProfile().apply {
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                },
            )
            val serverFlags = serverMessages.mapNotNull { message ->
                runCatching {
                    remote.getUID(message) to ServerFlags(
                        isSeen = message.isSet(Flags.Flag.SEEN),
                        isFlagged = message.isSet(Flags.Flag.FLAGGED),
                        isAnswered = message.isSet(Flags.Flag.ANSWERED),
                    )
                }.getOrNull()
            }.toMap()

            val vanished = SyncPlanner.vanished(localFlags.map { it.uid }, serverFlags.keys)
            // Messages with changes still queued keep their local flags until the queue catches up.
            val pending = actionDao.pendingUids(local.fullName).toSet()
            val changed = SyncPlanner.changedFlags(localFlags.filter { it.uid !in pending }, serverFlags)
            db.withTransaction {
                vanished.chunked(500).forEach { messageDao.deleteUids(local.id, it) }
                for ((uid, flags) in changed) {
                    messageDao.updateFlags(local.id, uid, flags.isSeen, flags.isFlagged, flags.isAnswered)
                }
            }
        }

        val unread = runCatching { remote.unreadMessageCount }.getOrDefault(local.unreadCount)
        folderDao.update(
            local.copy(
                highestUid = highest,
                lastSyncedAt = System.currentTimeMillis(),
                unreadCount = maxOf(unread, 0),
                totalCount = count,
            ),
        )
    }

    /** Replays queued actions. Actions that keep failing are dropped and the local copy is repaired. */
    private suspend fun processActions(store: IMAPStore) {
        val actions = actionDao.ready(System.currentTimeMillis())
        if (actions.isEmpty()) return
        for ((folderName, group) in actions.groupBy { it.folderName }) {
            val remote = store.getFolder(folderName) as IMAPFolder
            if (!remote.exists()) {
                group.forEach { giveUp(it) }
                continue
            }
            remote.open(Folder.READ_WRITE)
            try {
                for (action in group) {
                    try {
                        val message = if (action.uidValidity == remote.uidValidity) remote.getMessageByUID(action.uid) else null
                        // Already gone, or the folder was rebuilt: nothing left to do.
                        if (message != null && !message.isExpunged) apply(store, remote, message, action)
                        actionDao.delete(action.id)
                    } catch (e: FolderClosedException) {
                        throw e
                    } catch (e: StoreClosedException) {
                        throw e
                    } catch (e: MessagingException) {
                        if (action.attempts + 1 >= MAX_ATTEMPTS) giveUp(action) else actionDao.countAttempt(action.id)
                    }
                }
            } finally {
                runCatching { remote.close(false) }
            }
        }
    }

    private fun apply(store: IMAPStore, folder: IMAPFolder, message: Message, action: PendingAction) {
        when (action.type) {
            ActionType.MARK_READ -> message.setFlag(Flags.Flag.SEEN, true)
            ActionType.MARK_UNREAD -> message.setFlag(Flags.Flag.SEEN, false)
            ActionType.FLAG -> message.setFlag(Flags.Flag.FLAGGED, true)
            ActionType.UNFLAG -> message.setFlag(Flags.Flag.FLAGGED, false)
            ActionType.MOVE -> {
                val target = store.getFolder(action.targetFolderName ?: throw MessagingException("No target folder"))
                if (!target.exists()) throw MessagingException("Folder ${action.targetFolderName} no longer exists")
                if (store.hasCapability("MOVE")) {
                    folder.moveMessages(arrayOf(message), target)
                } else {
                    folder.copyMessages(arrayOf(message), target)
                    removeForGood(store, folder, message)
                }
            }
            ActionType.DELETE_PERMANENTLY -> removeForGood(store, folder, message)
        }
    }

    private fun removeForGood(store: IMAPStore, folder: IMAPFolder, message: Message) {
        message.setFlag(Flags.Flag.DELETED, true)
        // UID EXPUNGE removes just this message; plain EXPUNGE would also purge others marked deleted.
        if (store.hasCapability("UIDPLUS")) folder.expunge(arrayOf(message)) else folder.expunge()
    }

    private suspend fun giveUp(action: PendingAction) {
        actionDao.delete(action.id)
        if (action.type == ActionType.MOVE || action.type == ActionType.DELETE_PERMANENTLY) {
            // The message was removed locally but is still on the server: make the next sync fetch it again.
            folderDao.lowerHighestUid(action.folderName, action.uid - 1)
        }
        // Flag changes need no repair; the next sync takes the server's flags.
    }

    private suspend fun prefetchBodies(store: IMAPStore, folder: FolderEntity) {
        val pending = messageDao.withoutBody(folder.id, PREFETCH_BODIES)
        if (pending.isEmpty()) return
        withOpenFolder(store, folder) { remote -> downloadBodies(remote, pending) }
    }

    private suspend fun downloadBodies(remote: IMAPFolder, messages: List<MessageEntity>) {
        for (message in messages) {
            val serverMessage = runCatching { remote.getMessageByUID(message.uid) }.getOrNull() ?: continue
            val body = runCatching { extractBody(serverMessage) }.getOrNull() ?: continue
            runCatching { inlineImages.save(message.id, body.inlineParts) }
            messageDao.setBody(message.id, body.text, body.html, makePreview(body.text, body.html))
        }
    }

    private inline fun withOpenFolder(store: IMAPStore, folder: FolderEntity, block: (IMAPFolder) -> Unit) {
        val remote = store.getFolder(folder.fullName) as IMAPFolder
        if (!remote.exists()) return
        remote.open(Folder.READ_ONLY)
        try {
            block(remote)
        } finally {
            runCatching { remote.close(false) }
        }
    }

    private inline fun runCatchingMail(block: () -> Unit): SyncOutcome = try {
        block()
        SyncOutcome.Success
    } catch (e: AuthenticationFailedException) {
        SyncOutcome.AuthFailed
    } catch (e: MessagingException) {
        SyncOutcome.Failed(describe(e))
    } catch (e: IOException) {
        SyncOutcome.Failed(describe(e))
    }

    private fun describe(e: Exception): String {
        val root = generateSequence<Throwable>(e) { it.cause }.last()
        return when (root) {
            is java.net.UnknownHostException -> "No internet connection."
            is java.net.SocketTimeoutException -> "iCloud took too long to respond."
            else -> root.message?.takeIf { it.isNotBlank() } ?: "Couldn't sync with iCloud."
        }
    }

    private fun headerProfile() = FetchProfile().apply {
        add(FetchProfile.Item.ENVELOPE)
        add(FetchProfile.Item.FLAGS)
        add(FetchProfile.Item.CONTENT_INFO)
        add(UIDFolder.FetchProfileItem.UID)
    }

    private companion object {
        const val INITIAL_WINDOW = 200
        const val PREFETCH_BODIES = 25
        const val MAX_ATTEMPTS = 5
    }
}

private fun Message.toEntity(folderId: Long, uid: Long): MessageEntity {
    val sender = runCatching { from?.firstOrNull() as? InternetAddress }.getOrNull()
    val sent = runCatching { sentDate?.time }.getOrNull()
    val received = runCatching { receivedDate?.time }.getOrNull()
    return MessageEntity(
        folderId = folderId,
        uid = uid,
        messageIdHeader = runCatching { (this as? javax.mail.internet.MimeMessage)?.messageID }.getOrNull(),
        subject = runCatching { subject }.getOrNull().orEmpty(),
        fromName = sender?.personal?.takeIf { it.isNotBlank() } ?: sender?.address.orEmpty(),
        fromAddress = sender?.address.orEmpty(),
        toList = formatAddresses(runCatching { getRecipients(Message.RecipientType.TO) }.getOrNull()),
        ccList = formatAddresses(runCatching { getRecipients(Message.RecipientType.CC) }.getOrNull()),
        sentAt = sent ?: received ?: 0L,
        receivedAt = received ?: sent ?: 0L,
        isSeen = isSet(Flags.Flag.SEEN),
        isFlagged = isSet(Flags.Flag.FLAGGED),
        isAnswered = isSet(Flags.Flag.ANSWERED),
        hasAttachments = runCatching { isMimeType("multipart/mixed") }.getOrDefault(false),
    )
}

private fun formatAddresses(addresses: Array<javax.mail.Address>?): String =
    addresses.orEmpty().joinToString(", ") { address ->
        val internet = address as? InternetAddress
        when {
            internet == null -> address.toString()
            internet.personal.isNullOrBlank() -> internet.address.orEmpty()
            else -> "${internet.personal} <${internet.address}>"
        }
    }
