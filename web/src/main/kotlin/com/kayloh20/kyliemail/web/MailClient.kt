package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.mail.AttachmentInfo
import com.kayloh20.kyliemail.data.mail.BodyParser
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.kayloh20.kyliemail.data.mail.ParsedBody
import com.kayloh20.kyliemail.data.mail.ServerConfig
import com.kayloh20.kyliemail.data.search.SearchQuery
import com.kayloh20.kyliemail.data.search.ServerSearch
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.protocol.IMAPProtocol
import com.sun.mail.imap.IMAPStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import javax.mail.Address
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.FolderClosedException
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.StoreClosedException
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.MessageIDTerm

@Serializable data class FolderInfo(val id: String, val name: String, val role: String, val depth: Int, val unread: Int, val total: Int)

@Serializable data class MessageSummary(
    val uid: Long,
    val fromName: String,
    val fromAddress: String,
    val subject: String,
    val date: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val answered: Boolean,
    val hasAttachments: Boolean,
)

@Serializable data class MessagePage(
    val folder: String,
    /** The folder's UIDVALIDITY: if it changes, every UID in it means something else. */
    val validity: Long,
    val total: Int,
    val messages: List<MessageSummary>,
    /** Pass as `before` for the next, older page; null at the oldest message. */
    val before: Int?,
)

@Serializable data class AttachmentSummary(val path: String, val filename: String, val mimeType: String, val size: Long)

@Serializable data class MessageDetail(
    val folder: String,
    val validity: Long,
    val summary: MessageSummary,
    val to: List<String>,
    val cc: List<String>,
    val replyTo: List<String>,
    val attachments: List<AttachmentSummary>,
    val remoteImages: Int,
    val imagesAllowed: Boolean,
)

@Serializable data class Removal(
    val id: String,
    val count: Int,
    /** Where the messages go, or null when they're deleted for good. */
    val target: String?,
    val targetName: String?,
    /** How long Undo works, in milliseconds. */
    val undoMs: Long,
)

/** Messages leaving a folder, held for a moment so the change can be undone. */
private class PendingRemoval(
    val id: String,
    val folder: String,
    val validity: Long,
    val uids: Set<Long>,
    val target: String?,
    val unseen: Int,
    var job: Job? = null,
)

@Serializable data class SearchHit(val folder: String, val validity: Long, val summary: MessageSummary)
@Serializable data class SkippedFolder(val name: String, val reason: String)
@Serializable data class SearchResults(
    val hits: List<SearchHit>,
    val skipped: List<SkippedFolder>,
    /** Folders with more matches than were returned (only the newest come back). */
    val limited: List<String>,
)

/** Where a session remembers its folder list between sign-ins. */
interface FolderStore {
    fun load(): List<FolderInfo>?
    fun save(folders: List<FolderInfo>)
}

/** A message read and parsed for display, kept briefly so loading its images doesn't fetch it again. */
class OpenedMessage(
    val summary: MessageSummary,
    val to: List<String>,
    val cc: List<String>,
    val replyTo: List<String>,
    val body: ParsedBody,
    /** The original's Message-ID header, which a reply refers to. */
    val messageIdHeader: String? = null,
)

/** Someone you've exchanged mail with, for suggestions while writing. */
@Serializable data class Contact(val name: String, val address: String)

class Download(val filename: String, val mimeType: String, val bytes: ByteArray)

/**
 * One signed-in browser's connection to iCloud. Calls take turns, since an IMAP connection does one
 * thing at a time, and a dropped connection is reopened once before giving up.
 */
class MailClient(
    private val username: String,
    private val password: () -> String?,
    server: ServerConfig = ServerConfig.ICLOUD,
    /** How long removals wait before going to iCloud. */
    private val undoMs: Long = UNDO_MS,
    /** Where the folder list is remembered between sign-ins (sealed), if anywhere. */
    private val folderStore: FolderStore? = null,
) : AutoCloseable {
    private val connector = ImapConnector(server)
    private val lock = Mutex()
    private var store: IMAPStore? = null
    private var folder: IMAPFolder? = null
    private var passwordOverride: String? = null
    @Volatile private var knownFolders: List<FolderInfo> = emptyList()
    @Volatile private var knownAt = 0L
    private var refreshing: Job? = null

    /** Tells pages the folder list changed by itself (a background refresh found something). */
    private val _folderChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val folderChanges: SharedFlow<Unit> = _folderChanges.asSharedFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = LinkedHashMap<String, PendingRemoval>()
    private val opened = object : LinkedHashMap<String, OpenedMessage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OpenedMessage>) = size > 20
    }

    suspend fun folders(): List<FolderInfo> = run { store ->
        store.defaultFolder.list("*").map { it as IMAPFolder }
            .filter { f -> (f.type and Folder.HOLDS_MESSAGES) != 0 && f.attributes.none { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) } }
            .map { f ->
                val role = FolderRole.detect(f.fullName, f.attributes.toList())
                val separator = runCatching { f.separator }.getOrDefault('/')
                FolderInfo(
                    id = f.fullName,
                    name = FolderRole.displayName(role, f.name),
                    role = role.name.lowercase(),
                    depth = if (role == FolderRole.CUSTOM) f.fullName.count { it == separator } else 0,
                    unread = runCatching { f.unreadMessageCount }.getOrDefault(0).coerceAtLeast(0),
                    total = runCatching { f.messageCount }.getOrDefault(0).coerceAtLeast(0),
                )
            }
            .sortedWith(compareBy({ FolderRole.valueOf(it.role.uppercase()).sortOrder }, { it.id.lowercase() }))
    }.also(::remember).let(::withPendingCounts)

    /**
     * The folder list without asking iCloud: as remembered from earlier, in this session or (sealed)
     * from the last. When it's older than [FULL_REFRESH_MS], a full refresh starts in the background
     * and pages hear about any change through [folderChanges]. Only the very first time ever does
     * this wait for iCloud.
     */
    suspend fun cachedFolders(): List<FolderInfo> {
        if (knownFolders.isEmpty()) folderStore?.load()?.takeIf { it.isNotEmpty() }?.let { knownFolders = it }
        if (knownFolders.isEmpty()) return folders()
        if (System.currentTimeMillis() - knownAt > FULL_REFRESH_MS) refreshInBackground()
        return withPendingCounts(knownFolders)
    }

    private fun refreshInBackground() = synchronized(this) {
        if (refreshing?.isActive == true) return
        refreshing = scope.launch { runCatching { folders() } }
    }

    private fun remember(fresh: List<FolderInfo>) {
        val changed = fresh != knownFolders
        knownFolders = fresh
        knownAt = System.currentTimeMillis()
        if (changed) {
            runCatching { folderStore?.save(fresh) }
            _folderChanges.tryEmit(Unit)
        }
    }

    /** Updates one remembered folder's counts, as just seen on iCloud. */
    private fun rememberCounts(folderId: String, unread: Int, total: Int, announce: Boolean) {
        val current = knownFolders
        val updated = current.map { if (it.id == folderId) it.copy(unread = unread.coerceAtLeast(0), total = total.coerceAtLeast(0)) else it }
        if (updated == current) return
        knownFolders = updated
        runCatching { folderStore?.save(updated) }
        if (announce) _folderChanges.tryEmit(Unit)
    }

    /** Freshens these folders' remembered counts, as when IDLE reports a change in one. */
    suspend fun refreshFolderCounts(folderIds: Collection<String>) = run { store -> refreshCounts(store, folderIds) }

    /** Asks iCloud for just these folders' counts, after something changed in them. */
    private fun refreshCounts(store: IMAPStore, folderIds: Collection<String>) {
        for (id in folderIds.toSet()) {
            runCatching {
                val open = folder?.takeIf { it.fullName == id && it.isOpen }
                val f = open ?: (store.getFolder(id) as IMAPFolder)
                rememberCounts(id, f.unreadMessageCount, f.messageCount, announce = true)
            }
        }
    }

    /** Counts as they'll be once the changes waiting to be sent have gone through. */
    private fun withPendingCounts(folders: List<FolderInfo>): List<FolderInfo> {
        val waiting = synchronized(pending) { pending.values.toList() }
        if (waiting.isEmpty()) return folders
        return folders.map { f ->
            var unread = f.unread
            var total = f.total
            for (p in waiting) {
                if (p.folder == f.id) { unread -= p.unseen; total -= p.uids.size }
                if (p.target == f.id) { unread += p.unseen; total += p.uids.size }
            }
            f.copy(unread = unread.coerceAtLeast(0), total = total.coerceAtLeast(0))
        }
    }

    /** Up to [size] messages, newest first, older than message number [before]. */
    suspend fun page(folderId: String, before: Int?, size: Int = 50): MessagePage = run { store ->
        val f = open(store, folderId)
        // A folder kept open since last time only hears about new mail when it asks; NOOP asks.
        f.doCommand { protocol -> (protocol as IMAPProtocol).noop(); null }
        val count = f.messageCount
        // Looking at a folder is a chance to keep its counts right, without asking for every folder.
        runCatching { rememberCounts(folderId, f.unreadMessageCount, count, announce = false) }
        val end = minOf(before?.minus(1) ?: count, count)
        val start = maxOf(1, end - size + 1)
        val messages = if (end < 1) emptyArray() else f.getMessages(start, end)
        f.fetch(messages, FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.CONTENT_INFO)
            add(UIDFolder.FetchProfileItem.UID)
        })
        val leaving = synchronized(pending) {
            pending.values.filter { it.folder == folderId && it.validity == f.uidValidity }.flatMap { it.uids }.toSet()
        }
        MessagePage(
            folder = folderId,
            validity = f.uidValidity,
            total = count - leaving.size,
            messages = messages.reversed().map { summary(f, it) }.filter { it.uid !in leaving },
            before = start.takeIf { it > 1 && end >= 1 },
        )
    }

    suspend fun message(folderId: String, validity: Long, uid: Long): OpenedMessage {
        val key = "$folderId\u0000$validity\u0000$uid"
        synchronized(opened) { opened[key] }?.let { return it }
        val message = run { store ->
            val f = open(store, folderId, validity)
            val m = f.getMessageByUID(uid) ?: throw ApiError(HttpStatusCode.NotFound, "That message is gone. It may have been moved or deleted.")
            OpenedMessage(
                summary(f, m),
                list(m.getRecipients(Message.RecipientType.TO)),
                list(m.getRecipients(Message.RecipientType.CC)),
                list(m.replyTo),
                BodyParser.parse(m),
                m.getHeader("Message-ID")?.firstOrNull()?.trim(),
            )
        }
        synchronized(opened) { opened[key] = message }
        return message
    }

    suspend fun setFlag(folderId: String, validity: Long, uids: List<Long>, flag: Flags.Flag, value: Boolean) = run { store ->
        val f = open(store, folderId, validity)
        val messages = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
        if (messages.isNotEmpty()) f.setFlags(messages, Flags(flag), value)
        if (flag == Flags.Flag.SEEN) runCatching { rememberCounts(folderId, f.unreadMessageCount, f.messageCount, announce = false) }
        synchronized(opened) { opened.keys.removeIf { it.startsWith("$folderId\u0000$validity\u0000") } }
    }

    /**
     * Takes messages out of a folder: into [target], or deleted for good when it's null. Nothing
     * changes on iCloud for [UNDO_MS], so [undo] can still take it back; until then the folder lists
     * and counts already look as they will afterwards.
     */
    suspend fun remove(folderId: String, validity: Long, uids: List<Long>, target: String?): Pair<Removal, List<MessageFeatures>> {
        if (uids.isEmpty()) throw ApiError(HttpStatusCode.BadRequest, "No messages chosen.")
        if (target == folderId) throw ApiError(HttpStatusCode.BadRequest, "They're already in that folder.")
        val (unseen, features) = run { store ->
            if (target != null && !store.getFolder(target).exists()) throw ApiError(HttpStatusCode.NotFound, "That folder doesn't exist any more.")
            val f = open(store, folderId, validity)
            val messages = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
            if (messages.isEmpty()) throw ApiError(HttpStatusCode.NotFound, "Those messages are gone.")
            messages.count { !it.isSet(Flags.Flag.SEEN) } to readFeatures(f, messages)
        }
        val removal = PendingRemoval(Crypto.b64url(Crypto.randomBytes(12)), folderId, validity, uids.toSet(), target, unseen)
        synchronized(pending) {
            pending[removal.id] = removal
            removal.job = scope.launch {
                delay(undoMs)
                send(removal.id)
            }
        }
        val name = target?.let { t -> knownFolders.find { it.id == t }?.name ?: t.substringAfterLast('/') }
        return Removal(removal.id, uids.size, target, name, undoMs) to features
    }

    /** Adds [message] to [folderId] with [flags], such as a copy in Sent or a saved draft. */
    suspend fun append(folderId: String, message: MimeMessage, flags: Flags) = run { store ->
        val f = store.getFolder(folderId)
        if (!f.exists()) throw ApiError(HttpStatusCode.NotFound, "There's no ${folderId.substringAfterLast('/')} folder.")
        message.setFlags(flags, true)
        f.appendMessages(arrayOf(message))
        refreshCounts(store, listOf(folderId))
        synchronized(opened) { opened.keys.removeIf { it.startsWith("$folderId\u0000") } }
    }

    /** Whether [folderId] holds a message with this Message-ID header. */
    suspend fun hasMessageId(folderId: String, messageId: String): Boolean = run { store ->
        if (!store.getFolder(folderId).exists()) return@run false
        open(store, folderId).search(MessageIDTerm(messageId)).isNotEmpty()
    }

    /** Removes one message for good, such as a draft that has been sent or saved again. */
    suspend fun deleteForGood(folderId: String, validity: Long, uid: Long) = run { store ->
        val f = open(store, folderId, validity)
        val message = f.getMessageByUID(uid) ?: return@run
        message.setFlag(Flags.Flag.DELETED, true)
        if (store.hasCapability("UIDPLUS")) f.expunge(arrayOf(message)) else f.expunge()
        refreshCounts(store, listOf(folderId))
        synchronized(opened) { opened.keys.removeIf { it.startsWith("$folderId\u0000") } }
    }

    /**
     * People you've exchanged mail with: who wrote to you lately and who you wrote to, most
     * frequent first. Read once per session, kept in memory only.
     */
    suspend fun contacts(): List<Contact> {
        contactCache?.let { return it }
        val folders = knownFolders()
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        fun add(address: InternetAddress) {
            val key = address.address?.lowercase()?.takeIf { it.contains('@') } ?: return
            val (name, n) = counts[key] ?: ("" to 0)
            counts[key] = (address.personal?.takeIf { it.isNotBlank() } ?: name) to n + 1
        }
        for ((role, types) in listOf(
            "inbox" to listOf<Message.RecipientType?>(null),
            "sent" to listOf(Message.RecipientType.TO, Message.RecipientType.CC),
        )) {
            val folder = folders.find { it.role == role } ?: continue
            runCatching {
                run { store ->
                    val f = open(store, folder.id)
                    val count = f.messageCount
                    if (count < 1) return@run
                    val messages = f.getMessages(maxOf(1, count - CONTACT_DEPTH + 1), count)
                    f.fetch(messages, FetchProfile().apply { add(FetchProfile.Item.ENVELOPE) })
                    for (m in messages) {
                        for (type in types) {
                            val addresses = if (type == null) m.from else m.getRecipients(type)
                            addresses?.forEach { (it as? InternetAddress)?.let(::add) }
                        }
                    }
                }
            }
        }
        return counts.entries.sortedByDescending { it.value.second }
            .map { Contact(it.value.first, it.key) }
            .also { contactCache = it }
    }

    @Volatile private var contactCache: List<Contact>? = null

    /** The password, for sending mail with the same account. */
    fun passwordForSending(): String? = passwordOverride ?: password()

    private val searchGeneration = java.util.concurrent.atomic.AtomicLong()

    /**
     * Asks iCloud which messages match [terms] in each of [folders], the newest [perFolder] of
     * them, as the app's server search does. One folder at a time, so the connection is never held
     * for long; a newer search makes an older one stop between folders.
     */
    suspend fun search(terms: List<SearchQuery.ServerTerm>, folders: List<FolderInfo>, perFolder: Int = SEARCH_PER_FOLDER): SearchResults {
        val term = ServerSearch.term(terms) ?: throw ApiError(HttpStatusCode.BadRequest, "Type something to search for.")
        val mine = searchGeneration.incrementAndGet()
        val hits = mutableListOf<SearchHit>()
        val skipped = mutableListOf<SkippedFolder>()
        val limited = mutableListOf<String>()
        for (folder in folders) {
            if (searchGeneration.get() != mine) throw ApiError(HttpStatusCode.Conflict, "A newer search replaced this one.")
            try {
                run { store ->
                    val f = open(store, folder.id)
                    val numbers = f.doCommand { protocol -> (protocol as IMAPProtocol).search(term) } as? IntArray
                        ?: throw ApiError(HttpStatusCode.BadGateway, "iCloud couldn't run this search there")
                    val newest = numbers.sortedDescending()
                    if (newest.size > perFolder) limited += folder.name
                    val messages = f.getMessages(newest.take(perFolder).toIntArray())
                    f.fetch(messages, FetchProfile().apply {
                        add(FetchProfile.Item.ENVELOPE)
                        add(FetchProfile.Item.FLAGS)
                        add(FetchProfile.Item.CONTENT_INFO)
                        add(UIDFolder.FetchProfileItem.UID)
                    })
                    val leaving = synchronized(pending) {
                        pending.values.filter { it.folder == folder.id && it.validity == f.uidValidity }.flatMap { it.uids }.toSet()
                    }
                    for (m in messages) {
                        val summary = runCatching { summary(f, m) }.getOrNull() ?: continue
                        if (summary.uid !in leaving) hits += SearchHit(folder.id, f.uidValidity, summary)
                    }
                }
            } catch (e: ApiError) {
                if (e.status == HttpStatusCode.Conflict) throw e
                skipped += SkippedFolder(folder.name, e.message ?: "it couldn't be searched")
            }
        }
        return SearchResults(hits.sortedByDescending { it.summary.date }, skipped, limited)
    }

    /** Sender, recipients and subject of [uids], for smart filing. */
    suspend fun features(folderId: String, validity: Long, uids: List<Long>): List<MessageFeatures> = run { store ->
        val f = open(store, folderId, validity)
        readFeatures(f, f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray())
    }

    /**
     * Up to [limit] of a folder's newest messages for training, or only those newer than [afterUid]
     * when the folder hasn't changed since [knownValidity]. Read in chunks, so the connection is
     * never held for long. Null if the folder is gone.
     */
    suspend fun collectExamples(folderName: String, afterUid: Long, knownValidity: Long, limit: Int): FolderSample? {
        val (validity, count) = run { store ->
            val f = store.getFolder(folderName) as IMAPFolder
            if (!f.exists()) return@run null
            val opened = open(store, folderName)
            opened.uidValidity to opened.messageCount
        } ?: return null
        val incremental = afterUid > 0 && validity == knownValidity
        val collected = mutableListOf<MessageFeatures>()
        if (incremental) {
            collected += run { store ->
                val f = open(store, folderName, validity)
                readFeatures(f, f.getMessagesByUID(afterUid + 1, UIDFolder.LASTUID)).filter { it.uid > afterUid }
            }
        } else if (count > 0) {
            var end = count
            val start = maxOf(1, count - limit + 1)
            while (end >= start) {
                val from = maxOf(start, end - CHUNK + 1)
                collected += run { store ->
                    val f = open(store, folderName, validity)
                    readFeatures(f, f.getMessages(from, end))
                }
                end = from - 1
            }
        }
        val highest = maxOf(if (incremental) afterUid else 0L, collected.maxOfOrNull { it.uid } ?: 0L)
        return FolderSample(validity, highest, collected)
    }

    private fun readFeatures(f: IMAPFolder, messages: Array<Message>): List<MessageFeatures> {
        f.fetch(messages, FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(UIDFolder.FetchProfileItem.UID)
        })
        return messages.mapNotNull { m ->
            runCatching {
                val from = m.from?.firstOrNull() as? InternetAddress
                val recipients = listOf(Message.RecipientType.TO, Message.RecipientType.CC)
                    .flatMap { m.getRecipients(it)?.toList().orEmpty() }
                    .mapNotNull { (it as? InternetAddress)?.address?.lowercase() }
                MessageFeatures(f.getUID(m), from?.address.orEmpty().lowercase(), from?.personal.orEmpty(), recipients, m.subject.orEmpty())
            }.getOrNull()
        }
    }

    /** Takes back a removal that hasn't been sent yet. False once it has. */
    fun undo(id: String): Boolean = synchronized(pending) {
        val removal = pending.remove(id) ?: return false
        removal.job?.cancel()
        true
    }

    /** The folders as last listed, without asking iCloud again unless there's no list yet. */
    suspend fun knownFolders(): List<FolderInfo> {
        if (knownFolders.isEmpty()) cachedFolders()
        return knownFolders
    }

    /** The folder Delete and Archive move to, from the folders seen so far. */
    suspend fun folderWithRole(role: FolderRole): FolderInfo? {
        return knownFolders().find { it.role == role.name.lowercase() }
    }

    suspend fun roleOf(folderId: String): FolderRole {
        return knownFolders().find { it.id == folderId }?.role?.let { FolderRole.valueOf(it.uppercase()) } ?: FolderRole.CUSTOM
    }

    /** Sends a waiting removal to iCloud now. */
    private suspend fun send(id: String) {
        val removal = synchronized(pending) { pending.remove(id) } ?: return
        try {
            run { store ->
                val f = open(store, removal.folder, removal.validity)
                val messages = f.getMessagesByUID(removal.uids.toLongArray()).filterNotNull().toTypedArray()
                if (messages.isEmpty()) return@run
                val target = removal.target
                if (target != null) {
                    val destination = store.getFolder(target)
                    if (store.hasCapability("MOVE")) {
                        f.moveMessages(messages, destination)
                        return@run
                    }
                    f.copyMessages(messages, destination)
                }
                f.setFlags(messages, Flags(Flags.Flag.DELETED), true)
                // Without UIDPLUS, expunging can only clear every deleted message in the folder.
                if (store.hasCapability("UIDPLUS")) f.expunge(messages) else f.expunge()
            }
            // Only the two folders involved need fresh counts.
            run { store -> refreshCounts(store, listOfNotNull(removal.folder, removal.target)) }
        } catch (e: Exception) {
            System.err.println("Couldn't send a change to iCloud: ${e::class.simpleName}")
        }
        synchronized(opened) { opened.keys.removeIf { it.startsWith("${removal.folder}\u0000") } }
    }

    suspend fun attachment(folderId: String, validity: Long, uid: Long, path: String): Download = run { store ->
        val f = open(store, folderId, validity)
        val m = f.getMessageByUID(uid) ?: throw ApiError(HttpStatusCode.NotFound, "That message is gone.")
        val info = BodyParser.parse(m).attachments.find { it.path == path }
            ?: throw ApiError(HttpStatusCode.NotFound, "That attachment isn't in the message.")
        val part = partAt(m, path) ?: throw ApiError(HttpStatusCode.NotFound, "That attachment isn't in the message.")
        Download(info.filename, info.mimeType, readAll(part))
    }

    override fun close() {
        val s = store
        store = null
        folder = null
        s?.let(connector::kill)
    }

    /**
     * Ends this connection after sending every change still waiting, so signing out right after
     * deleting something still deletes it. Reads the password first, since the session forgets
     * its key as soon as this returns.
     */
    fun shutdown(first: suspend () -> Unit = {}) {
        passwordOverride = runCatching { password() }.getOrNull()
        val waiting = synchronized(pending) { pending.values.toList() }
        waiting.forEach { it.job?.cancel() }
        scope.launch {
            try {
                runCatching { first() }
                for (removal in waiting) send(removal.id)
            } finally {
                passwordOverride = null
                close()
                scope.cancel()
            }
        }
    }

    /** Runs [block] on the IMAP connection, reconnecting once if it had dropped. */
    private suspend fun <T> run(block: (IMAPStore) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                block(connected())
            } catch (e: ApiError) {
                throw e
            } catch (e: Exception) {
                if (!droppedConnection(e)) throw translate(e)
                close()
                try {
                    block(connected())
                } catch (e: ApiError) {
                    throw e
                } catch (e: Exception) {
                    close()
                    throw translate(e)
                }
            }
        }
    }

    private fun connected(): IMAPStore {
        store?.takeIf { it.isConnected }?.let { return it }
        close()
        val pw = passwordOverride ?: password() ?: throw ApiError(HttpStatusCode.Unauthorized, "Sign in again.")
        return connector.connect(username, pw).also { store = it }
    }

    private fun droppedConnection(e: Exception) = e is FolderClosedException || e is StoreClosedException ||
        e is IllegalStateException || (e is MessagingException && e !is AuthenticationFailedException)

    private fun translate(e: Exception): Exception = when (e) {
        is AuthenticationFailedException -> ApiError(HttpStatusCode.BadGateway, "iCloud stopped accepting your app-specific password. If you revoked it, set up Kylie Mail again with a new one.")
        else -> ApiError(HttpStatusCode.BadGateway, "Couldn't reach iCloud. Try again in a moment.")
    }

    private fun open(store: IMAPStore, folderId: String, validity: Long? = null): IMAPFolder {
        val current = folder
        val f = if (current != null && current.fullName == folderId && current.isOpen) {
            current
        } else {
            runCatching { current?.close(false) }
            val next = store.getFolder(folderId) as IMAPFolder
            if (!next.exists()) throw ApiError(HttpStatusCode.NotFound, "That folder doesn't exist any more.")
            next.open(Folder.READ_WRITE)
            folder = next
            next
        }
        if (validity != null && f.uidValidity != validity) throw ApiError(HttpStatusCode.Conflict, "This folder changed on the server. Reload it.")
        return f
    }

    private fun summary(f: IMAPFolder, m: Message): MessageSummary {
        val from = m.from?.firstOrNull() as? InternetAddress
        return MessageSummary(
            uid = f.getUID(m),
            fromName = from?.personal?.takeIf { it.isNotBlank() } ?: from?.address ?: "",
            fromAddress = from?.address.orEmpty(),
            subject = m.subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
            date = (m.sentDate ?: m.receivedDate)?.time ?: 0,
            seen = m.isSet(Flags.Flag.SEEN),
            flagged = m.isSet(Flags.Flag.FLAGGED),
            answered = m.isSet(Flags.Flag.ANSWERED),
            hasAttachments = runCatching { m.isMimeType("multipart/mixed") }.getOrDefault(false),
        )
    }

    private fun list(addresses: Array<Address>?): List<String> =
        addresses.orEmpty().map { a -> (a as? InternetAddress)?.toUnicodeString() ?: a.toString() }

    /** The part at an IMAP section path such as "2" or "1.3". */
    private fun partAt(root: Part, path: String): Part? {
        var part: Part = root
        for (index in path.split('.').map { it.toIntOrNull() ?: return null }) {
            val multipart = runCatching { part.content as? Multipart }.getOrNull()
            if (multipart == null) return if (index == 1 && part === root) part else null
            if (index < 1 || index > multipart.count) return null
            part = multipart.getBodyPart(index - 1)
        }
        return part
    }

    private fun readAll(part: Part): ByteArray = part.inputStream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_DOWNLOAD) throw ApiError(HttpStatusCode.PayloadTooLarge, "That attachment is too big to download here.")
        }
        out.toByteArray()
    }

    companion object {
        const val MAX_DOWNLOAD = 60L * 1024 * 1024
        /** How long a removal waits before going to iCloud, as in the app. */
        const val UNDO_MS = 6_000L

        /** How old the remembered folder list may get before a full refresh in the background. */
        const val FULL_REFRESH_MS = 10 * 60 * 1000L

        /** As in the app: the newest matches in each folder. */
        const val SEARCH_PER_FOLDER = 50

        /** How many recent messages in Inbox and Sent contacts are gathered from. */
        private const val CONTACT_DEPTH = 500

        /** How many messages one training read fetches before letting other requests through. */
        private const val CHUNK = 250
    }
}

fun AttachmentInfo.summary() = AttachmentSummary(path, filename, mimeType, size)
