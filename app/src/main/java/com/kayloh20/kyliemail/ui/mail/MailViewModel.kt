package com.kayloh20.kyliemail.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayloh20.kyliemail.data.MailRepository
import com.kayloh20.kyliemail.data.UndoToken
import com.kayloh20.kyliemail.data.MailAttachments
import com.kayloh20.kyliemail.data.db.AttachmentEntity
import android.content.ContentResolver
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kayloh20.kyliemail.data.compose.Outbox
import com.kayloh20.kyliemail.data.compose.ReplyMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.kayloh20.kyliemail.data.filing.FilingEngine
import com.kayloh20.kyliemail.data.filing.FolderSuggestion
import com.kayloh20.kyliemail.data.settings.AppSettings
import com.kayloh20.kyliemail.data.settings.SwipeAction
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.db.MessageSummary
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.sync.SyncOutcome
import com.kayloh20.kyliemail.data.sync.SyncState
import com.kayloh20.kyliemail.data.sync.UserAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.debounce
import com.kayloh20.kyliemail.data.search.SearchQuery
import com.kayloh20.kyliemail.data.sync.SkippedFolder
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the search box holds. */
data class SearchState(
    val text: String = "",
    val allFolders: Boolean = true,
    /** Matches iCloud found that weren't on the device. */
    val serverHits: Set<Long> = emptySet(),
    val server: ServerSearchState = ServerSearchState.Idle,
)

sealed interface ServerSearchState {
    data object Idle : ServerSearchState
    data class Running(val done: Int, val total: Int) : ServerSearchState
    data class Done(val found: Int, val skipped: List<SkippedFolder> = emptyList(), val searched: Int = 0) : ServerSearchState
    data class Failed(val message: String) : ServerSearchState
}

/** Which folder picker is open: moving the selection, or jumping to a folder. */
enum class PickerMode { MOVE, GO_TO }

/** A short message at the bottom of the screen, with Undo when the change can still be taken back. */
data class Notice(
    val text: String,
    val undo: UndoToken? = null,
    val restore: Selection? = null,
    /** A message waiting to be sent, which Undo takes back into its compose window. */
    val unsendDraftId: Long? = null,
) {
    val canUndo: Boolean get() = undo != null || unsendDraftId != null
}

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class MailViewModel(
    private val repository: MailRepository,
    private val filing: FilingEngine,
    private val settings: AppSettings,
    private val outbox: Outbox,
    private val mailAttachments: MailAttachments,
) : ViewModel() {

    val folders: StateFlow<List<FolderEntity>> =
        repository.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedFolderId = MutableStateFlow<Long?>(null)
    val selectedFolderId: StateFlow<Long?> = _selectedFolderId.asStateFlow()

    // Search

    private val _search = MutableStateFlow<SearchState?>(null)

    /** The search box's contents and results, or null when not searching. */
    val search: StateFlow<SearchState?> = _search.asStateFlow()

    private val searchResults: StateFlow<List<MessageSummary>> = _search
        .map { state -> state?.let { Triple(it.text, if (it.allFolders) null else _selectedFolderId.value, it.serverHits) } }
        .distinctUntilChanged()
        .debounce(120)
        .flatMapLatest { key ->
            if (key == null) {
                flowOf(emptyList())
            } else {
                val (text, folder, serverHits) = key
                // Matches on the device, plus what iCloud found that the index can't see yet (bodies not downloaded).
                combine(repository.search(SearchQuery.parse(text), folder), repository.summaries(serverHits)) { local, remote ->
                    (local + remote.filter { folder == null || it.folderId == folder })
                        .distinctBy { it.id }
                        .sortedByDescending { it.receivedAt }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun openSearch() {
        if (_search.value == null) _search.value = SearchState()
    }

    fun setSearchText(text: String) {
        val current = _search.value ?: SearchState()
        // New words mean an earlier iCloud search no longer applies.
        _search.value = current.copy(text = text, serverHits = emptySet(), server = ServerSearchState.Idle)
        serverSearchJob?.cancel()
    }

    fun setSearchAllFolders(all: Boolean) {
        _search.value = _search.value?.copy(allFolders = all, serverHits = emptySet(), server = ServerSearchState.Idle)
    }

    fun closeSearch() {
        serverSearchJob?.cancel()
        _search.value = null
        _selection.value = Selection()
    }

    private var serverSearchJob: kotlinx.coroutines.Job? = null

    /** Searches iCloud itself for older mail that isn't on the device. */
    fun searchServer() {
        val state = _search.value ?: return
        val query = SearchQuery.parse(state.text)
        if (query.serverTerms.isEmpty()) return
        val all = folders.value.filter { it.selectable }
        val targets = if (state.allFolders) all else all.filter { it.id == _selectedFolderId.value }
        serverSearchJob?.cancel()
        serverSearchJob = viewModelScope.launch {
            update { it.copy(server = ServerSearchState.Running(0, targets.size)) }
            try {
                val result = repository.searchServer(query, targets) { done, total ->
                    update { it.copy(server = ServerSearchState.Running(done, total)) }
                }
                update {
                    it.copy(
                        serverHits = it.serverHits + result.messageIds,
                        server = ServerSearchState.Done(result.messageIds.size, result.skipped, targets.size),
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                update { it.copy(server = ServerSearchState.Failed(e.message ?: "Couldn't reach iCloud.")) }
            }
        }
    }

    private fun update(change: (SearchState) -> SearchState) {
        _search.value = _search.value?.let(change)
    }

    val messages: StateFlow<List<MessageSummary>> = combine(
        _selectedFolderId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.messages(id) },
        _search,
        searchResults,
    ) { folder, search, results -> if (search == null || search.text.isBlank()) folder else results }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    /** The message in the reading pane: set when exactly one message is selected. */
    val selectedMessageId: StateFlow<Long?> = _selection
        .map { it.single }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val selectedMessage: StateFlow<MessageEntity?> = selectedMessageId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.message(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncState: StateFlow<SyncState> = repository.syncState

    val inlineImages = repository.inlineImages

    val recentMoveTargets: StateFlow<List<String>> = repository.recentMoveTargets

    private val _remoteAllowedFor = MutableStateFlow<Set<Long>>(emptySet())

    /** Whether the open message may load remote images: allowed once, or its sender is trusted. */
    val remoteImagesAllowed: StateFlow<Boolean> =
        combine(selectedMessage, _remoteAllowedFor, repository.trustedImageSenders) { message, allowed, trusted ->
            message != null && (message.id in allowed || message.fromAddress.lowercase() in trusted)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when the open folder can archive, meaning an Archive folder exists and this isn't it. */
    val canArchive: StateFlow<Boolean> = combine(folders, _selectedFolderId) { list, selected ->
        list.any { it.role == FolderRole.ARCHIVE } && list.firstOrNull { it.id == selected }?.role != FolderRole.ARCHIVE
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _bodyError = MutableStateFlow<String?>(null)
    val bodyError: StateFlow<String?> = _bodyError.asStateFlow()

    /** Smart filing suggestions for the selected mail, best first. Empty when turned off. */
    val suggestions: StateFlow<List<FolderSuggestion>> =
        combine(_selection, filing.version, settings.filingSuggestions) { selection, _, enabled -> selection to enabled }
            .flatMapLatest { (selection, enabled) ->
                if (!enabled || selection.ids.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    flow { emit(filing.suggest(SelectionRules.ordered(selection, order()))) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True once smart filing has something to learn from. */
    val filingTrained: StateFlow<Boolean> = filing.exampleCount.map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val filingEnabled: StateFlow<Boolean> = settings.filingSuggestions
    val swipeRight: StateFlow<SwipeAction> = settings.swipeRight
    val swipeLeft: StateFlow<SwipeAction> = settings.swipeLeft

    private val _picker = MutableStateFlow<PickerMode?>(null)
    val picker: StateFlow<PickerMode?> = _picker.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    init {
        viewModelScope.launch {
            val list = folders.first { it.isNotEmpty() }
            if (_selectedFolderId.value == null) {
                _selectedFolderId.value = (list.firstOrNull { it.role == FolderRole.INBOX } ?: list.first()).id
            }
        }
        // Opening a message loads its body and marks it read.
        viewModelScope.launch {
            selectedMessageId.collect { id ->
                _bodyError.value = null
                if (id != null) {
                    loadBody(id)
                    val message = repository.messageById(id)
                    if (message != null && !message.isSeen) repository.perform(id, UserAction.MARK_READ)
                }
            }
        }
        // Offer Undo while a sent message waits to leave.
        viewModelScope.launch {
            outbox.sending.collect { event ->
                val subject = event.subject.ifBlank { "Message" }.let { if (it.length > 40) it.take(40) + "…" else it }
                _notice.value = Notice("Sending \"$subject\"…", unsendDraftId = event.draftId)
            }
        }
        refresh()
    }

    // Folders

    fun refresh() {
        viewModelScope.launch { repository.refresh(_selectedFolderId.value) }
    }

    fun selectFolder(id: Long) {
        if (_selectedFolderId.value == id) return
        _selectedFolderId.value = id
        _selection.value = Selection()
        // Picking a folder while searching it alone narrows to that folder.
        if (_search.value?.allFolders == false) update { it.copy(serverHits = emptySet(), server = ServerSearchState.Idle) }
        viewModelScope.launch { repository.refreshFolder(id) }
    }

    // Selection

    private fun order() = messages.value.map { it.id }

    fun click(id: Long, ctrl: Boolean, shift: Boolean) {
        _selection.value = SelectionRules.click(_selection.value, order(), id, ctrl, shift)
    }

    /** Opens just this message (or closes the reading pane when null). */
    fun selectMessage(id: Long?) {
        _selection.value = if (id == null) Selection() else Selection.of(id)
    }

    fun moveSelection(delta: Int, extend: Boolean = false) {
        _selection.value = SelectionRules.move(_selection.value, order(), delta, extend)
    }

    fun selectAll() {
        _selection.value = SelectionRules.selectAll(_selection.value, order())
    }

    fun clearSelection() {
        _selection.value = Selection()
    }

    /** Right-clicking a message outside the selection selects it, as desktop apps do. */
    /** A long press on a touch screen starts picking messages. */
    fun startTouchSelection(id: Long) {
        _selection.value = SelectionRules.startTouch(_selection.value, id)
    }

    fun prepareContextMenu(id: Long) {
        if (id !in _selection.value.ids) _selection.value = Selection.of(id)
    }

    /** Opens a message from anywhere, such as a notification, switching to its folder. */
    fun openMessage(id: Long) {
        viewModelScope.launch {
            val message = repository.messageById(id) ?: return@launch
            if (_selectedFolderId.value != message.folderId) {
                _selectedFolderId.value = message.folderId
                viewModelScope.launch { repository.refreshFolder(message.folderId) }
            }
            _selection.value = Selection.of(id)
        }
    }

    /** A swiped message being moved, which acts on that message without changing the selection. */
    private var moveOverride: List<Long>? = null

    private fun targets(): List<Long> = moveOverride ?: SelectionRules.ordered(_selection.value, order())

    /** What a swipe does to one message, leaving the selection alone unless that message leaves the list. */
    fun swipe(id: Long, action: SwipeAction) {
        val message = messages.value.firstOrNull { it.id == id } ?: return
        when (action) {
            SwipeAction.NONE -> Unit
            SwipeAction.READ -> viewModelScope.launch {
                repository.perform(id, if (message.isSeen) UserAction.MARK_UNREAD else UserAction.MARK_READ)
            }
            SwipeAction.FLAG -> viewModelScope.launch {
                repository.perform(id, if (message.isFlagged) UserAction.UNFLAG else UserAction.FLAG)
            }
            SwipeAction.ARCHIVE -> removeIds(listOf(id), UserAction.ARCHIVE, null)
            SwipeAction.DELETE -> removeIds(listOf(id), UserAction.DELETE, null)
            SwipeAction.MOVE -> {
                moveOverride = listOf(id)
                _picker.value = PickerMode.MOVE
            }
        }
    }

    // Actions

    fun archiveSelected() = removeSelected(UserAction.ARCHIVE, null)

    fun deleteSelected() = removeSelected(UserAction.DELETE, null)

    fun moveSelected(targetFolderId: Long) = removeSelected(UserAction.MOVE, targetFolderId)

    fun toggleReadSelected() {
        val ids = targets().toSet()
        if (ids.isEmpty()) return
        val anyUnread = messages.value.any { it.id in ids && !it.isSeen } || selectedMessage.value?.let { !it.isSeen } == true
        viewModelScope.launch {
            repository.performAll(ids.toList(), if (anyUnread) UserAction.MARK_READ else UserAction.MARK_UNREAD)
        }
    }

    fun toggleFlagSelected() {
        val ids = targets().toSet()
        if (ids.isEmpty()) return
        val anyUnflagged = messages.value.any { it.id in ids && !it.isFlagged }
        viewModelScope.launch {
            repository.performAll(ids.toList(), if (anyUnflagged) UserAction.FLAG else UserAction.UNFLAG)
        }
    }

    /** Archives, deletes or moves the selection, then selects the message after it, like desktop mail apps. */
    private fun removeSelected(action: UserAction, targetFolderId: Long?) = removeIds(targets(), action, targetFolderId)

    private fun removeIds(ids: List<Long>, action: UserAction, targetFolderId: Long?) {
        if (ids.isEmpty()) return
        val before = _selection.value
        // Only move the selection along if what was selected is leaving.
        val next = if (before.ids.any { it in ids }) SelectionRules.afterRemoval(order(), ids.toSet()) else before
        viewModelScope.launch {
            val result = repository.performAll(ids, action, targetFolderId)
            if (result.applied == 0) return@launch
            if (_selection.value == before) _selection.value = next
            _notice.value = Notice(describe(action, result.applied, result.targetName), result.undo, before)
        }
    }

    fun undo() {
        val notice = _notice.value ?: return
        _notice.value = null
        notice.unsendDraftId?.let { id ->
            viewModelScope.launch {
                if (outbox.undoSend(id)) _composeRequests.emit(id) else _notice.value = Notice("Too late to undo. The message was already sent.")
            }
            return
        }
        val token = notice.undo ?: return
        viewModelScope.launch {
            if (repository.undo(token)) {
                notice.restore?.let { _selection.value = it }
            } else {
                _notice.value = Notice("Too late to undo. The change was already sent to iCloud.")
            }
        }
    }

    // Attachments

    /** Files in the open message. */
    val attachments: StateFlow<List<AttachmentEntity>> = selectedMessageId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.attachments(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _downloading = MutableStateFlow<Set<Long>>(emptySet())
    val downloading: StateFlow<Set<Long>> = _downloading.asStateFlow()

    sealed interface FileEvent {
        data class Open(val file: File, val mimeType: String) : FileEvent
        data class ChooseSaveLocation(val attachment: AttachmentEntity) : FileEvent
    }

    private val _fileEvents = MutableSharedFlow<FileEvent>(extraBufferCapacity = 4)
    val fileEvents: SharedFlow<FileEvent> = _fileEvents.asSharedFlow()

    fun openAttachment(attachment: AttachmentEntity) {
        withDownload(attachment) { file -> _fileEvents.emit(FileEvent.Open(file, attachment.mimeType)) }
    }

    /** A click on a file card inside the message text. */
    fun openAttachmentAt(path: String) {
        val messageId = selectedMessageId.value ?: return
        viewModelScope.launch {
            val id = mailAttachments.idForPath(messageId, path) ?: return@launch
            attachments.value.firstOrNull { it.id == id }?.let(::openAttachment)
        }
    }

    fun saveAttachment(attachment: AttachmentEntity) {
        viewModelScope.launch { _fileEvents.emit(FileEvent.ChooseSaveLocation(attachment)) }
    }

    /** Writes the attachment to the place picked in the system's save dialog. */
    fun writeAttachment(attachment: AttachmentEntity, destination: Uri, resolver: ContentResolver) {
        withDownload(attachment) { file ->
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(destination)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw java.io.IOException("Couldn't write the file.")
            }
            _notice.value = Notice("Saved ${attachment.filename}")
        }
    }

    fun showProblem(text: String) {
        _notice.value = Notice(text)
    }

    private fun withDownload(attachment: AttachmentEntity, then: suspend (File) -> Unit) {
        if (attachment.id in _downloading.value) return
        viewModelScope.launch {
            _downloading.value = _downloading.value + attachment.id
            try {
                then(mailAttachments.file(attachment.id))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _notice.value = Notice("Couldn't get ${attachment.filename}: ${e.message ?: "connection problem"}")
            } finally {
                _downloading.value = _downloading.value - attachment.id
            }
        }
    }

    // Writing mail

    private val _composeRequests = MutableSharedFlow<Long>(extraBufferCapacity = 4)

    /** Drafts to open in a compose window. */
    val composeRequests: SharedFlow<Long> = _composeRequests.asSharedFlow()

    /** True when the open folder is Drafts, where messages are edited rather than replied to. */
    val inDrafts: StateFlow<Boolean> = combine(folders, _selectedFolderId) { list, selected ->
        list.firstOrNull { it.id == selected }?.role == FolderRole.DRAFTS
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun newMessage() {
        viewModelScope.launch { _composeRequests.emit(outbox.createNew()) }
    }

    fun reply(mode: ReplyMode) {
        val id = selectedMessageId.value ?: return
        viewModelScope.launch { outbox.createReply(id, mode)?.let { _composeRequests.emit(it) } }
    }

    fun editDraft() {
        val id = selectedMessageId.value ?: return
        viewModelScope.launch { outbox.editServerDraft(id)?.let { _composeRequests.emit(it) } }
    }

    fun dismissNotice(notice: Notice) {
        if (_notice.value === notice) _notice.value = null
    }

    // Folder pickers

    fun openMovePicker() {
        moveOverride = null
        if (targets().isNotEmpty()) _picker.value = PickerMode.MOVE
    }

    fun openGoToPicker() {
        _picker.value = PickerMode.GO_TO
    }

    fun closePicker() {
        _picker.value = null
        moveOverride = null
    }

    fun pickFolder(id: Long) {
        val mode = _picker.value
        _picker.value = null
        when (mode) {
            PickerMode.MOVE -> moveSelected(id)
            PickerMode.GO_TO -> selectFolder(id)
            null -> Unit
        }
        moveOverride = null
    }

    /** How many messages the move picker would move, for its title. */
    fun selectionCount(): Int = targets().size

    // Message body and images

    fun retryBody() {
        selectedMessageId.value?.let(::loadBody)
    }

    fun loadRemoteImages() {
        val id = selectedMessageId.value ?: return
        _remoteAllowedFor.value = _remoteAllowedFor.value + id
    }

    fun trustSender() {
        val address = selectedMessage.value?.fromAddress ?: return
        repository.trustImageSender(address)
    }

    fun signOut() {
        viewModelScope.launch { repository.signOut() }
    }

    private fun loadBody(id: Long) {
        viewModelScope.launch {
            val outcome = repository.loadBody(id)
            if (selectedMessageId.value != id) return@launch
            _bodyError.value = when (outcome) {
                is SyncOutcome.Failed -> outcome.message
                SyncOutcome.AuthFailed -> "iCloud rejected your password."
                else -> null
            }
        }
    }

    private fun describe(action: UserAction, count: Int, target: String?): String {
        val what = if (count == 1) "Message" else "$count messages"
        return when (action) {
            UserAction.ARCHIVE -> "$what archived"
            UserAction.DELETE -> "$what deleted"
            UserAction.MOVE -> "$what moved to ${target ?: "folder"}"
            else -> "$what updated"
        }
    }
}
