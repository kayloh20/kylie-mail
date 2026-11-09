package com.kayloh20.kyliemail.data.sync

import androidx.room.withTransaction
import com.kayloh20.kyliemail.data.account.Account
import com.kayloh20.kyliemail.data.account.CredentialStore
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.mail.ImapConnector
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
) {
    private val folderDao = db.folders()
    private val messageDao = db.messages()
    /** Only one header sync runs at a time. */
    private val mutex = Mutex()

    /** Guards the shared connection below. */
    private val connectionLock = Mutex()
    private var store: IMAPStore? = null
    private var storeAccount: Account? = null

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Full refresh: the folder list and unread counts, the inbox, and [focusFolderId] if given. */
    suspend fun sync(focusFolderId: Long? = null): SyncOutcome = syncHeaders { store ->
        syncFolderList(store)
        listOfNotNull(
            folderDao.getByRole(FolderRole.INBOX),
            focusFolderId?.let { folderDao.getById(it) },
        ).distinctBy { it.id }
    }

    /** Quick refresh of a single folder, used when it is opened. */
    suspend fun syncFolder(folderId: Long): SyncOutcome = syncHeaders {
        listOfNotNull(folderDao.getById(folderId))
    }

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
            val changed = SyncPlanner.changedFlags(localFlags, serverFlags)
            db.withTransaction {
                vanished.chunked(500).forEach { messageDao.deleteUids(local.id, it) }
                for ((uid, flags) in changed) {
                    messageDao.updateFlags(local.id, uid, flags.isSeen, flags.isFlagged, flags.isAnswered)
                }
            }
        }

        folderDao.update(local.copy(highestUid = highest, lastSyncedAt = System.currentTimeMillis()))
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
