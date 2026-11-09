package com.kayloh20.kyliemail.data.compose

import android.content.Context
import androidx.core.text.HtmlCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kayloh20.kyliemail.KylieMailApp
import com.kayloh20.kyliemail.data.MailAttachments
import com.kayloh20.kyliemail.data.account.Account
import com.kayloh20.kyliemail.data.account.CredentialStore
import com.kayloh20.kyliemail.data.db.DraftEntity
import com.kayloh20.kyliemail.data.db.DraftState
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.notify.MailNotifier
import com.kayloh20.kyliemail.data.settings.AppSettings
import com.kayloh20.kyliemail.data.sync.SyncEngine
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.mail.AuthenticationFailedException
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.MessagingException
import javax.mail.SendFailedException
import javax.mail.search.MessageIDTerm

/** Something the main window should announce. */
data class SendingEvent(val draftId: Long, val subject: String)

/**
 * Drafts and sending. Every message goes through the database first, so nothing is lost if the app
 * closes, and each step is a one-way state change so a message is never sent twice.
 */
class Outbox(
    private val context: Context,
    private val db: MailDatabase,
    private val credentials: CredentialStore,
    private val settings: AppSettings,
    private val syncEngine: SyncEngine,
    private val notifier: MailNotifier,
    private val attachments: MailAttachments,
    private val scope: CoroutineScope,
) {
    private val attachmentDao = db.attachments()
    private val drafts = db.drafts()
    private val smtp = SmtpSender()
    private val processLock = Mutex()

    private val _sending = MutableSharedFlow<SendingEvent>(extraBufferCapacity = 4)

    /** Fires when a message is queued, so the main window can offer Undo. */
    val sending: SharedFlow<SendingEvent> = _sending.asSharedFlow()

    // Starting drafts

    suspend fun createNew(): Long =
        drafts.insert(DraftEntity(body = signatureBlock(), updatedAt = now()))

    /** Starts a reply or forward to a message. Returns null if the message is gone. */
    suspend fun createReply(messageId: Long, mode: ReplyMode): Long? {
        val message = loadedMessage(messageId) ?: return null
        val folder = db.folders().getById(message.folderId)
        val reply = ReplyBuilder.build(
            OriginalMessage(
                fromName = message.fromName,
                fromAddress = message.fromAddress,
                toList = message.toList,
                ccList = message.ccList,
                subject = message.subject,
                sentAt = message.sentAt,
                bodyText = readableText(message.bodyText, message.bodyHtml),
                messageIdHeader = message.messageIdHeader,
            ),
            mode,
            ownAddresses(),
            settings.signature.value,
        )
        val draftId = drafts.insert(
            DraftEntity(
                toList = reply.to,
                ccList = reply.cc,
                subject = reply.subject,
                body = reply.body,
                inReplyTo = reply.inReplyTo,
                references = reply.references,
                replyFolder = folder?.fullName?.takeIf { mode != ReplyMode.FORWARD },
                replyUid = message.uid.takeIf { mode != ReplyMode.FORWARD },
                updatedAt = now(),
            ),
        )
        // Forwards carry the original's files; if any can't be fetched (offline, say), the window says so.
        if (mode == ReplyMode.FORWARD && !attachments.copyToDraft(messageId, draftId)) {
            drafts.get(draftId)?.let { drafts.update(it.copy(missingAttachments = true)) }
        }
        return draftId
    }

    /** Opens a message from the iCloud Drafts folder for editing; it is replaced when saved or sent. */
    suspend fun editServerDraft(messageId: Long): Long? {
        val message = loadedMessage(messageId) ?: return null
        val folder = db.folders().getById(message.folderId) ?: return null
        val draftId = drafts.insert(
            DraftEntity(
                toList = message.toList,
                ccList = message.ccList,
                subject = message.subject,
                body = readableText(message.bodyText, message.bodyHtml),
                serverDraftFolder = folder.fullName,
                serverDraftUid = message.uid,
                updatedAt = now(),
            ),
        )
        if (!attachments.copyToDraft(messageId, draftId)) {
            drafts.get(draftId)?.let { drafts.update(it.copy(missingAttachments = true)) }
        }
        return draftId
    }

    // Editing

    suspend fun get(id: Long): DraftEntity? = drafts.get(id)

    /** Makes a failed or undone draft editable again. */
    suspend fun reopen(id: Long): DraftEntity? {
        val draft = drafts.get(id) ?: return null
        if (draft.state == DraftState.FAILED) drafts.transition(id, DraftState.FAILED, DraftState.EDITING, now())
        return drafts.get(id)
    }

    suspend fun saveFields(id: Long, to: String, cc: String, bcc: String, subject: String, body: String) {
        val draft = drafts.get(id) ?: return
        if (draft.state != DraftState.EDITING) return
        drafts.update(draft.copy(toList = to, ccList = cc, bccList = bcc, subject = subject, body = body, updatedAt = now()))
    }

    /** Queues the draft to send after the undo window. Returns a problem to show instead, if any. */
    suspend fun send(id: Long): String? {
        val draft = drafts.get(id) ?: return "This draft no longer exists."
        MessageComposer.validateRecipients(draft.toList, draft.ccList, draft.bccList)?.let { return it }
        val total = attachmentDao.forDraft(id).sumOf { it.size }
        if (total > MessageComposer.MAX_ATTACHMENT_BYTES) {
            return "Attachments are too large to send. iCloud accepts messages up to 20 MB, which leaves about 14 MB for files."
        }
        val account = credentials.account.value ?: return "You're signed out."
        drafts.update(
            draft.copy(
                messageId = draft.messageId ?: MessageComposer.newMessageId(account.email),
                sendAfter = now() + UNDO_SEND_MS,
                lastError = null,
                updatedAt = now(),
            ),
        )
        if (drafts.transition(id, DraftState.EDITING, DraftState.QUEUED, now()) != 1) return "This draft is already being sent."
        _sending.tryEmit(SendingEvent(id, draft.subject))
        schedule(UNDO_SEND_MS)
        return null
    }

    /** Takes a queued message back before it leaves. Returns false once it is on its way. */
    suspend fun undoSend(id: Long): Boolean = drafts.transition(id, DraftState.QUEUED, DraftState.EDITING, now()) == 1

    /** Closing a compose window: empty drafts disappear, others are saved to iCloud Drafts. */
    suspend fun close(id: Long) {
        val draft = drafts.get(id) ?: return
        if (draft.state != DraftState.EDITING) return
        if (isEmpty(draft) && draft.serverDraftUid == null && attachmentDao.forDraft(id).isEmpty()) {
            drafts.delete(id)
            attachments.files.deleteDraftFiles(id)
            return
        }
        if (drafts.transition(id, DraftState.EDITING, DraftState.SAVING, now()) == 1) schedule(0)
    }

    suspend fun discard(id: Long) {
        val draft = drafts.get(id) ?: return
        if (draft.serverDraftUid == null) {
            drafts.delete(id)
            attachments.files.deleteDraftFiles(id)
        } else if (drafts.transition(id, draft.state, DraftState.DISCARDING, now()) == 1) {
            schedule(0)
        }
    }

    // Processing

    /** Does whatever is due. Returns true when something still needs another attempt. */
    suspend fun process(): Boolean = processLock.withLock {
        withContext(Dispatchers.IO) {
            recoverInterrupted()
            val account = credentials.account.value ?: return@withContext false
            var retry = false
            for (draft in drafts.ready(now())) {
                retry = retry or when (draft.state) {
                    DraftState.QUEUED -> sendNow(draft, account)
                    DraftState.SENT -> !finishSent(draft, account)
                    DraftState.SAVING -> !saveToServer(draft, account)
                    DraftState.DISCARDING -> !discardOnServer(draft)
                    else -> false
                }
            }
            retry || drafts.pendingCount() > 0
        }
    }

    /**
     * A send interrupted before the server answered may or may not have gone out. Rather than risk
     * sending it twice, it is handed back to the user to check.
     */
    private suspend fun recoverInterrupted() {
        for (draft in drafts.stuckSending(now() - STUCK_AFTER_MS)) {
            if (drafts.transition(draft.id, DraftState.SENDING, DraftState.FAILED, now()) == 1) {
                drafts.update(drafts.get(draft.id)!!.copy(lastError = "Sending was interrupted. Check your Sent folder before sending again."))
                notifier.notifySendFailed(draft.id, draft.subject, "Sending was interrupted. Check Sent before sending again.")
            }
        }
    }

    /** Returns true if it should be retried later. */
    private suspend fun sendNow(draft: DraftEntity, account: Account): Boolean {
        if (drafts.transition(draft.id, DraftState.QUEUED, DraftState.SENDING, now()) != 1) return false
        return try {
            val session = smtp.session()
            smtp.send(session, MessageComposer.build(session, outgoing(draft, account)), account.username, account.password)
            // From here on the message has left; it is never sent again.
            drafts.update(drafts.get(draft.id)!!.copy(state = DraftState.SENT, sendAfter = now() + SENT_COPY_DELAY_MS, updatedAt = now()))
            schedule(SENT_COPY_DELAY_MS)
            false
        } catch (e: AuthenticationFailedException) {
            fail(draft, "iCloud rejected your password. Sign in again, then send.")
            false
        } catch (e: SendFailedException) {
            fail(draft, e.message?.takeIf { it.isNotBlank() } ?: "iCloud refused one of the addresses.")
            false
        } catch (e: MessagingException) {
            requeue(draft, e)
        } catch (e: IOException) {
            requeue(draft, e)
        }
    }

    private suspend fun requeue(draft: DraftEntity, error: Exception): Boolean {
        val current = drafts.get(draft.id) ?: return false
        if (current.attempts + 1 >= MAX_ATTEMPTS) {
            fail(current, "Couldn't reach iCloud after several tries: ${error.message ?: "connection problem"}")
            return false
        }
        drafts.update(current.copy(state = DraftState.QUEUED, attempts = current.attempts + 1, lastError = error.message, updatedAt = now()))
        return true
    }

    private suspend fun fail(draft: DraftEntity, reason: String) {
        val current = drafts.get(draft.id) ?: return
        drafts.update(current.copy(state = DraftState.FAILED, lastError = reason, updatedAt = now()))
        notifier.notifySendFailed(draft.id, draft.subject, reason)
    }

    /** Puts a copy in Sent (unless iCloud already did), marks the original answered, removes a replaced draft. */
    private suspend fun finishSent(draft: DraftEntity, account: Account): Boolean = runCatching {
        syncEngine.useConnection { store ->
            db.folders().getByRole(FolderRole.SENT)?.let { sent ->
                val folder = store.getFolder(sent.fullName) as IMAPFolder
                if (folder.exists()) {
                    folder.open(Folder.READ_ONLY)
                    val already = try {
                        folder.search(MessageIDTerm(draft.messageId.orEmpty())).isNotEmpty()
                    } finally {
                        runCatching { folder.close(false) }
                    }
                    if (!already) {
                        val copy = MessageComposer.build(smtp.session(), outgoing(draft, account))
                        copy.setFlag(Flags.Flag.SEEN, true)
                        folder.appendMessages(arrayOf(copy))
                    }
                }
            }
            if (draft.replyFolder != null && draft.replyUid != null) {
                runCatching { setAnswered(store, draft.replyFolder, draft.replyUid) }
            }
            if (draft.serverDraftFolder != null && draft.serverDraftUid != null) {
                runCatching { deleteForGood(store, draft.serverDraftFolder, draft.serverDraftUid) }
            }
        }
        drafts.delete(draft.id)
        attachments.files.deleteDraftFiles(draft.id)
    }.isSuccess

    private suspend fun saveToServer(draft: DraftEntity, account: Account): Boolean = runCatching {
        val draftsFolder = db.folders().getByRole(FolderRole.DRAFTS) ?: error("There is no Drafts folder.")
        syncEngine.useConnection { store ->
            val folder = store.getFolder(draftsFolder.fullName) as IMAPFolder
            val message = MessageComposer.build(
                smtp.session(),
                outgoing(draft, account).copy(messageId = MessageComposer.newMessageId(account.email)),
                strict = false,
            )
            message.setFlags(Flags().apply { add(Flags.Flag.DRAFT); add(Flags.Flag.SEEN) }, true)
            folder.appendMessages(arrayOf(message))
            if (draft.serverDraftFolder != null && draft.serverDraftUid != null) {
                runCatching { deleteForGood(store, draft.serverDraftFolder, draft.serverDraftUid) }
            }
        }
        drafts.delete(draft.id)
        attachments.files.deleteDraftFiles(draft.id)
        scope.launch { syncEngine.syncFolder(draftsFolder.id) }
    }.isSuccess

    private suspend fun discardOnServer(draft: DraftEntity): Boolean = runCatching {
        if (draft.serverDraftFolder != null && draft.serverDraftUid != null) {
            syncEngine.useConnection { store -> deleteForGood(store, draft.serverDraftFolder, draft.serverDraftUid) }
            db.folders().getByName(draft.serverDraftFolder)?.let { scope.launch { syncEngine.syncFolder(it.id) } }
        }
        drafts.delete(draft.id)
        attachments.files.deleteDraftFiles(draft.id)
    }.isSuccess

    private suspend fun setAnswered(store: IMAPStore, folderName: String, uid: Long) {
        val folder = store.getFolder(folderName) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            folder.getMessageByUID(uid)?.setFlag(Flags.Flag.ANSWERED, true)
        } finally {
            runCatching { folder.close(false) }
        }
        db.folders().getByName(folderName)?.let { db.messages().setAnswered(it.id, uid) }
    }

    private fun deleteForGood(store: IMAPStore, folderName: String, uid: Long) {
        val folder = store.getFolder(folderName) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            val message = folder.getMessageByUID(uid) ?: return
            message.setFlag(Flags.Flag.DELETED, true)
            if (store.hasCapability("UIDPLUS")) folder.expunge(arrayOf(message)) else folder.expunge()
        } finally {
            runCatching { folder.close(false) }
        }
    }

    // Helpers

    /** Runs [process] after [delayMs] in the app, with a background job as a safety net. */
    fun schedule(delayMs: Long) {
        scope.launch {
            delay(delayMs + 300)
            process()
        }
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInitialDelay(delayMs + SAFETY_NET_DELAY_MS, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private suspend fun outgoing(draft: DraftEntity, account: Account) = Outgoing(
        fromAddress = account.email,
        fromName = settings.displayName.value.trim(),
        to = draft.toList,
        cc = draft.ccList,
        bcc = draft.bccList,
        subject = draft.subject.trim(),
        body = draft.body,
        inReplyTo = draft.inReplyTo,
        references = draft.references,
        messageId = draft.messageId ?: MessageComposer.newMessageId(account.email),
        date = Date(),
        attachments = attachmentDao.forDraft(draft.id).map { OutgoingAttachment(java.io.File(it.path), it.filename, it.mimeType) },
    )

    private suspend fun loadedMessage(messageId: Long) = db.messages().getById(messageId)?.let { message ->
        if (message.bodyLoaded) message else syncEngine.loadBody(messageId).let { db.messages().getById(messageId) }
    }

    private fun readableText(text: String?, html: String?): String {
        text?.takeIf { it.isNotBlank() }?.let { return it.trimEnd() }
        val source = html ?: return ""
        val withoutStyles = Regex("(?is)<(style|script|head)\\b.*?</\\1>").replace(source, "")
        return HtmlCompat.fromHtml(withoutStyles, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            .replace('￼', ' ')
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun signatureBlock(): String {
        val signature = settings.signature.value
        return if (signature.isBlank()) "" else "\n\n-- \n${signature.trimEnd()}"
    }

    private fun isEmpty(draft: DraftEntity) =
        draft.toList.isBlank() && draft.ccList.isBlank() && draft.bccList.isBlank() && draft.subject.isBlank() &&
            draft.body.trim() == signatureBlock().trim()

    private fun ownAddresses(): Set<String> {
        val account = credentials.account.value ?: return emptySet()
        val local = account.email.substringBefore('@').lowercase()
        return setOf(account.email.lowercase(), account.username.lowercase(), "$local@icloud.com", "$local@me.com", "$local@mac.com")
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val UNDO_SEND_MS = 6_000L
        private const val SENT_COPY_DELAY_MS = 10_000L
        private const val SAFETY_NET_DELAY_MS = 60_000L
        private const val STUCK_AFTER_MS = 5 * 60_000L
        private const val MAX_ATTEMPTS = 8
        private const val WORK_NAME = "outbox"
    }
}

/** Sends and saves drafts when the app isn't running, or after a connection comes back. */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outbox = (applicationContext as KylieMailApp).container.outbox
        return if (outbox.process()) Result.retry() else Result.success()
    }
}
