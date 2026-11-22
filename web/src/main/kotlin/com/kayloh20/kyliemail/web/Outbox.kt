package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.compose.MessageComposer
import com.kayloh20.kyliemail.data.compose.Outgoing
import com.kayloh20.kyliemail.data.compose.OutgoingAttachment
import com.kayloh20.kyliemail.data.compose.SmtpSender
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import javax.mail.AuthenticationFailedException
import javax.mail.Flags
import javax.mail.SendFailedException

/** A message being written: what the compose window sends to Send or Save. */
@Serializable
data class Draft(
    val to: String = "",
    val cc: String = "",
    val bcc: String = "",
    val subject: String = "",
    val body: String = "",
    val inReplyTo: String? = null,
    val references: String? = null,
    /** The message being replied to, marked answered once sent. */
    val replyTo: MessageRef? = null,
    /** A draft in iCloud's Drafts folder this one replaces once sent or saved. */
    val replaces: MessageRef? = null,
    /** Files uploaded with [Outbox.upload]. */
    val attachments: List<String> = emptyList(),
)

@Serializable data class MessageRef(val folder: String, val validity: Long, val uid: Long)

@Serializable data class UploadInfo(val id: String, val filename: String, val mimeType: String, val size: Long)

@Serializable data class Sending(val id: String, val undoMs: Long)

@Serializable data class SendStatus(
    val id: String,
    /** "waiting" (Undo still works), "sending", "sent" or "failed". */
    val state: String,
    val subject: String,
    val error: String? = null,
)

/**
 * One signed-in browser's outgoing mail. Messages are built by the app's MessageComposer: a single
 * plain-text part, files as standard attachments, never HTML. Send waits [undoMs] so it can be
 * undone, as in the app; then SMTP, then a copy in Sent unless iCloud already filed one, then the
 * original is marked answered and a replaced draft removed.
 *
 * Uploaded files wait in the temporary folder, which in the container is memory (tmpfs), never the
 * disk, and are deleted once sent or discarded, or when the session ends.
 */
class Outbox(
    private val mail: MailClient,
    private val smtp: SmtpSender,
    private val username: String,
    private val fromAddress: String,
    private val identity: () -> Pair<String, String>,
    private val undoMs: Long = UNDO_SEND_MS,
    private val sentCopyDelayMs: Long = SENT_COPY_DELAY_MS,
) {
    private class Upload(val info: UploadInfo, val file: Path)
    private class Queued(val id: String, val draft: Draft, var job: Job? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploads = LinkedHashMap<String, Upload>()
    private val queued = LinkedHashMap<String, Queued>()
    private val statuses = LinkedHashMap<String, SendStatus>()

    // Files

    fun upload(filename: String, mimeType: String, bytes: ByteArray): UploadInfo = synchronized(uploads) {
        if (bytes.size > MessageComposer.MAX_ATTACHMENT_BYTES) {
            throw ApiError(HttpStatusCode.PayloadTooLarge, "Files can be up to 14 MB in all; iCloud refuses bigger messages.")
        }
        if (uploads.values.sumOf { it.info.size } + bytes.size > MAX_HELD_BYTES) {
            throw ApiError(HttpStatusCode.PayloadTooLarge, "Too many files waiting. Send or discard some messages first.")
        }
        val file = Files.createTempFile("kylie-upload-", ".bin")
        AccountStore.ownerOnly(file)
        Files.write(file, bytes)
        val info = UploadInfo(Crypto.b64url(Crypto.randomBytes(12)), cleanName(filename), mimeType.ifBlank { "application/octet-stream" }, bytes.size.toLong())
        uploads[info.id] = Upload(info, file)
        info
    }

    fun removeUpload(id: String) = synchronized(uploads) {
        uploads.remove(id)?.let { Files.deleteIfExists(it.file) }
    }

    /** Copies a received message's attachments, for forwarding it or editing a draft. */
    suspend fun copyAttachments(ref: MessageRef, opened: OpenedMessage): List<UploadInfo> =
        opened.body.attachments.map { a ->
            val download = mail.attachment(ref.folder, ref.validity, ref.uid, a.path)
            upload(download.filename, download.mimeType, download.bytes)
        }

    // Sending

    /** Checks [draft] and sends it after the undo window. */
    fun send(draft: Draft): Sending {
        MessageComposer.validateRecipients(draft.to, draft.cc, draft.bcc)?.let { throw ApiError(HttpStatusCode.BadRequest, it) }
        attachmentsOf(draft)
        val id = Crypto.b64url(Crypto.randomBytes(12))
        val item = Queued(id, draft)
        synchronized(queued) {
            queued[id] = item
            setStatus(SendStatus(id, "waiting", draft.subject))
            item.job = scope.launch {
                delay(undoMs)
                deliver(id)
            }
        }
        return Sending(id, undoMs)
    }

    /** Takes back a send that hasn't left yet. */
    fun undo(id: String): Boolean = synchronized(queued) {
        val item = queued.remove(id) ?: return false
        item.job?.cancel()
        statuses.remove(id)
        true
    }

    fun statuses(): List<SendStatus> = synchronized(statuses) { statuses.values.toList() }

    /** Saves [draft] to iCloud's Drafts folder, replacing the one it was opened from. */
    suspend fun save(draft: Draft, draftsFolder: String) {
        val message = MessageComposer.build(smtp.session(), outgoing(draft), strict = false)
        mail.append(draftsFolder, message, Flags().apply { add(Flags.Flag.DRAFT); add(Flags.Flag.SEEN) })
        draft.replaces?.let { runCatching { mail.deleteForGood(it.folder, it.validity, it.uid) } }
        draft.attachments.forEach(::removeUpload)
    }

    /** Throws away a draft's files. */
    fun discard(draft: Draft) = draft.attachments.forEach(::removeUpload)

    private suspend fun deliver(id: String) {
        val item = synchronized(queued) { queued.remove(id) } ?: return
        val draft = item.draft
        setStatus(SendStatus(id, "sending", draft.subject))
        val outgoing = try {
            outgoing(draft)
        } catch (e: ApiError) {
            return setStatus(SendStatus(id, "failed", draft.subject, e.message))
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val session = smtp.session()
                val password = mail.passwordForSending() ?: throw ApiError(HttpStatusCode.Unauthorized, "Sign in again, then send.")
                smtp.send(session, MessageComposer.build(session, outgoing), username, password)
            }
        }
        result.exceptionOrNull()?.let { e ->
            val reason = when (e) {
                is ApiError -> e.message
                is AuthenticationFailedException -> "iCloud rejected your app-specific password."
                is SendFailedException -> e.message?.takeIf { it.isNotBlank() } ?: "iCloud refused one of the addresses."
                else -> "Couldn't reach iCloud to send it. Try again."
            }
            return setStatus(SendStatus(id, "failed", draft.subject, reason))
        }
        // From here on it has left; it is never sent again.
        setStatus(SendStatus(id, "sent", draft.subject))
        finish(draft, outgoing)
    }

    /** A copy in Sent unless iCloud already filed one, the original marked answered, a replaced draft removed. */
    private suspend fun finish(draft: Draft, outgoing: Outgoing) {
        delay(sentCopyDelayMs)
        runCatching {
            mail.folderWithRole(com.kayloh20.kyliemail.data.mail.FolderRole.SENT)?.let { sent ->
                if (!mail.hasMessageId(sent.id, outgoing.messageId)) {
                    mail.append(sent.id, MessageComposer.build(smtp.session(), outgoing), Flags(Flags.Flag.SEEN))
                }
            }
        }
        draft.replyTo?.let { runCatching { mail.setFlag(it.folder, it.validity, listOf(it.uid), Flags.Flag.ANSWERED, true) } }
        draft.replaces?.let { runCatching { mail.deleteForGood(it.folder, it.validity, it.uid) } }
        draft.attachments.forEach(::removeUpload)
    }

    /** Sends everything still waiting, now: the session is ending. */
    suspend fun flush() {
        val waiting = synchronized(queued) { queued.values.toList() }
        waiting.forEach { it.job?.cancel() }
        for (item in waiting) deliver(item.id)
    }

    /** Deletes every file still waiting. */
    fun close() {
        scope.cancel()
        synchronized(uploads) {
            uploads.values.forEach { runCatching { Files.deleteIfExists(it.file) } }
            uploads.clear()
        }
    }

    private fun outgoing(draft: Draft): Outgoing {
        val (name, _) = identity()
        return Outgoing(
            fromAddress = fromAddress,
            fromName = name.trim(),
            to = draft.to,
            cc = draft.cc,
            bcc = draft.bcc,
            subject = draft.subject.trim(),
            body = draft.body,
            // These come from the browser: no line breaks, so they can't add headers of their own.
            inReplyTo = draft.inReplyTo?.let(::oneLine),
            references = draft.references?.let(::oneLine),
            messageId = MessageComposer.newMessageId(fromAddress),
            date = Date(),
            attachments = attachmentsOf(draft),
        )
    }

    private fun attachmentsOf(draft: Draft): List<OutgoingAttachment> = synchronized(uploads) {
        val files = draft.attachments.map { id ->
            uploads[id] ?: throw ApiError(HttpStatusCode.BadRequest, "An attached file is gone. Attach it again.")
        }
        if (files.sumOf { it.info.size } > MessageComposer.MAX_ATTACHMENT_BYTES) {
            throw ApiError(HttpStatusCode.PayloadTooLarge, "Files can be up to 14 MB in all; iCloud refuses bigger messages.")
        }
        files.map { OutgoingAttachment(it.file.toFile(), it.info.filename, it.info.mimeType) }
    }

    private fun setStatus(status: SendStatus) = synchronized(statuses) {
        statuses[status.id] = status
        while (statuses.size > 20) statuses.remove(statuses.keys.first())
    }

    private fun oneLine(value: String) = value.filter { !it.isISOControl() }.take(2000).ifBlank { null }

    private fun cleanName(name: String) =
        name.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.take(200).ifBlank { "Attachment" }

    companion object {
        /** As in the app. */
        const val UNDO_SEND_MS = 6_000L

        /** How long to give iCloud to file its own copy in Sent before adding one, as in the app. */
        const val SENT_COPY_DELAY_MS = 10_000L

        /** Files one session may hold while writing. */
        private const val MAX_HELD_BYTES = 60L * 1024 * 1024
    }
}
