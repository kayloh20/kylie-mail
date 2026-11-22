package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.mail.AttachmentInfo
import com.kayloh20.kyliemail.data.mail.BodyParser
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.kayloh20.kyliemail.data.mail.ParsedBody
import com.kayloh20.kyliemail.data.mail.ServerConfig
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
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

/** A message read and parsed for display, kept briefly so loading its images doesn't fetch it again. */
class OpenedMessage(val summary: MessageSummary, val to: List<String>, val cc: List<String>, val replyTo: List<String>, val body: ParsedBody)

class Download(val filename: String, val mimeType: String, val bytes: ByteArray)

/**
 * One signed-in browser's connection to iCloud. Calls take turns, since an IMAP connection does one
 * thing at a time, and a dropped connection is reopened once before giving up.
 */
class MailClient(
    private val username: String,
    private val password: () -> String?,
    server: ServerConfig = ServerConfig.ICLOUD,
) : AutoCloseable {
    private val connector = ImapConnector(server)
    private val lock = Mutex()
    private var store: IMAPStore? = null
    private var folder: IMAPFolder? = null
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
    }

    /** Up to [size] messages, newest first, older than message number [before]. */
    suspend fun page(folderId: String, before: Int?, size: Int = 50): MessagePage = run { store ->
        val f = open(store, folderId)
        val count = f.messageCount
        val end = minOf(before?.minus(1) ?: count, count)
        val start = maxOf(1, end - size + 1)
        val messages = if (end < 1) emptyArray() else f.getMessages(start, end)
        f.fetch(messages, FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.CONTENT_INFO)
            add(UIDFolder.FetchProfileItem.UID)
        })
        MessagePage(
            folder = folderId,
            validity = f.uidValidity,
            total = count,
            messages = messages.reversed().map { summary(f, it) },
            before = start.takeIf { it > 1 && end >= 1 },
        )
    }

    suspend fun message(folderId: String, validity: Long, uid: Long): OpenedMessage {
        val key = "$folderId\u0000$validity\u0000$uid"
        synchronized(opened) { opened[key] }?.let { return it }
        val message = run { store ->
            val f = open(store, folderId, validity)
            val m = f.getMessageByUID(uid) ?: throw ApiError(HttpStatusCode.NotFound, "That message is gone. It may have been moved or deleted.")
            OpenedMessage(summary(f, m), list(m.getRecipients(Message.RecipientType.TO)), list(m.getRecipients(Message.RecipientType.CC)), list(m.replyTo), BodyParser.parse(m))
        }
        synchronized(opened) { opened[key] = message }
        return message
    }

    suspend fun setSeen(folderId: String, validity: Long, uids: List<Long>, seen: Boolean) = run { store ->
        val f = open(store, folderId, validity)
        val messages = f.getMessagesByUID(uids.toLongArray()).filterNotNull().toTypedArray()
        if (messages.isNotEmpty()) f.setFlags(messages, Flags(Flags.Flag.SEEN), seen)
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
        val pw = password() ?: throw ApiError(HttpStatusCode.Unauthorized, "Sign in again.")
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
    }
}

fun AttachmentInfo.summary() = AttachmentSummary(path, filename, mimeType, size)
