package com.kayloh20.kyliemail.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayloh20.kyliemail.data.account.CredentialStore
import com.kayloh20.kyliemail.data.compose.Outbox
import com.kayloh20.kyliemail.data.compose.MessageComposer
import com.kayloh20.kyliemail.data.MailAttachments
import com.kayloh20.kyliemail.data.db.DraftAttachmentEntity
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.kayloh20.kyliemail.data.db.Contact
import com.kayloh20.kyliemail.data.db.DraftState
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** What the compose window should focus first. */
enum class FirstFocus { TO, BODY }

@OptIn(FlowPreview::class)
class ComposeViewModel(
    private val draftId: Long,
    private val outbox: Outbox,
    settings: AppSettings,
    private val db: MailDatabase,
    credentials: CredentialStore,
    private val mailAttachments: MailAttachments,
) : ViewModel() {
    /** Files attached to this draft. */
    val attachments: StateFlow<List<DraftAttachmentEntity>> = db.attachments().observeDraft(draftId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var attaching by mutableIntStateOf(0)
        private set

    /** Copies picked or dropped files into the draft. */
    fun attach(uris: List<Uri>, resolver: ContentResolver) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            attaching += uris.size
            for (uri in uris) {
                try {
                    mailAttachments.addToDraft(draftId, uri, resolver)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    error = "Couldn't attach a file: ${e.message ?: "it may have moved"}"
                } finally {
                    attaching -= 1
                }
            }
            if (attachments.value.sumOf { it.size } > MessageComposer.MAX_ATTACHMENT_BYTES) {
                error = "These attachments are too large to send. iCloud accepts about 14 MB of files per message."
            }
        }
    }

    fun removeAttachment(attachment: DraftAttachmentEntity) {
        viewModelScope.launch {
            mailAttachments.removeFromDraft(attachment)
            if (attachments.value.sumOf { it.size } - attachment.size <= MessageComposer.MAX_ATTACHMENT_BYTES &&
                error?.contains("too large") == true
            ) {
                error = null
            }
        }
    }

    var to by mutableStateOf("")
    var cc by mutableStateOf("")
    var bcc by mutableStateOf("")
    var subject by mutableStateOf("")
    var body by mutableStateOf(TextFieldValue(""))
    var showCcBcc by mutableStateOf(false)
    var loaded by mutableStateOf(false)
        private set
    var isReply by mutableStateOf(false)
        private set
    var missingAttachments by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var confirmEmptySubject by mutableStateOf(false)
    var firstFocus by mutableStateOf(FirstFocus.TO)
        private set

    /** Set once the window was closed through one of its own buttons. */
    var handled = false
        private set

    val from: String = credentials.account.value?.let { account ->
        val name = settings.displayName.value.trim()
        if (name.isEmpty()) account.email else "$name <${account.email}>"
    }.orEmpty()

    val title: String
        get() = subject.ifBlank { if (isReply) "Reply" else "New message" }

    init {
        viewModelScope.launch {
            val draft = outbox.reopen(draftId) ?: return@launch
            to = draft.toList
            cc = draft.ccList
            bcc = draft.bccList
            subject = draft.subject
            // Typing starts at the top, above any quote or signature.
            body = TextFieldValue(draft.body, selection = TextRange(0))
            showCcBcc = draft.ccList.isNotBlank() || draft.bccList.isNotBlank()
            isReply = draft.inReplyTo != null
            missingAttachments = draft.missingAttachments
            error = draft.lastError?.takeIf { draft.state == DraftState.EDITING }
            firstFocus = if (draft.toList.isBlank()) FirstFocus.TO else FirstFocus.BODY
            loaded = true

            // Save as you type, so closing the window or the app never loses text.
            snapshotFlow { listOf(to, cc, bcc, subject, body.text) }
                .drop(1)
                .debounce(600)
                .collect { saveNow() }
        }
    }

    fun saveNow() {
        if (!loaded || handled) return
        viewModelScope.launch { outbox.saveFields(draftId, to, cc, bcc, subject, body.text) }
    }

    /** Sends unless something needs attention first; [onSent] closes the window. */
    fun send(onSent: () -> Unit, allowEmptySubject: Boolean = false) {
        if (!loaded) return
        if (subject.isBlank() && !allowEmptySubject) {
            confirmEmptySubject = true
            return
        }
        confirmEmptySubject = false
        viewModelScope.launch {
            outbox.saveFields(draftId, to, cc, bcc, subject, body.text)
            val problem = outbox.send(draftId)
            if (problem == null) {
                handled = true
                onSent()
            } else {
                error = problem
            }
        }
    }

    /** Closing keeps the draft: it is saved to iCloud Drafts unless it's empty. */
    fun close(onClosed: () -> Unit) {
        viewModelScope.launch {
            outbox.saveFields(draftId, to, cc, bcc, subject, body.text)
            outbox.close(draftId)
            handled = true
            onClosed()
        }
    }

    fun discard(onDiscarded: () -> Unit) {
        viewModelScope.launch {
            outbox.discard(draftId)
            handled = true
            onDiscarded()
        }
    }

    /** For a window closed by the system: saves and files the draft on a scope that outlives it. */
    fun closeFrom(scope: CoroutineScope) {
        val fields = listOf(to, cc, bcc, subject, body.text)
        handled = true
        scope.launch {
            outbox.saveFields(draftId, fields[0], fields[1], fields[2], fields[3], fields[4])
            outbox.close(draftId)
        }
    }

    /** People to suggest for the address being typed at the end of [field]. */
    suspend fun contactsFor(field: String): List<Contact> {
        val token = field.substringAfterLast(',').substringAfterLast(';').trim()
        if (token.length < 2 || token.contains('>')) return emptyList()
        return db.drafts().contacts("%$token%", 6)
    }
}
