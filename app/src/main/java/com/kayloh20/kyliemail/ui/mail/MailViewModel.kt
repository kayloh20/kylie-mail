package com.kayloh20.kyliemail.ui.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayloh20.kyliemail.data.MailRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MailViewModel(private val repository: MailRepository) : ViewModel() {

    val folders: StateFlow<List<FolderEntity>> =
        repository.folders.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedFolderId = MutableStateFlow<Long?>(null)
    val selectedFolderId: StateFlow<Long?> = _selectedFolderId.asStateFlow()

    private val _selectedMessageId = MutableStateFlow<Long?>(null)
    val selectedMessageId: StateFlow<Long?> = _selectedMessageId.asStateFlow()

    val messages: StateFlow<List<MessageSummary>> = _selectedFolderId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repository.messages(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedMessage: StateFlow<MessageEntity?> = _selectedMessageId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.message(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val syncState: StateFlow<SyncState> = repository.syncState

    val inlineImages = repository.inlineImages

    private val _remoteAllowedFor = MutableStateFlow<Set<Long>>(emptySet())

    /** Whether the open message may load remote images: allowed once, or its sender is trusted. */
    val remoteImagesAllowed: StateFlow<Boolean> =
        combine(selectedMessage, _remoteAllowedFor, repository.trustedImageSenders) { message, allowed, trusted ->
            message != null && (message.id in allowed || message.fromAddress.lowercase() in trusted)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadRemoteImages() {
        val id = _selectedMessageId.value ?: return
        _remoteAllowedFor.value = _remoteAllowedFor.value + id
    }

    fun trustSender() {
        val address = selectedMessage.value?.fromAddress ?: return
        repository.trustImageSender(address)
    }

    private val _bodyError = MutableStateFlow<String?>(null)
    val bodyError: StateFlow<String?> = _bodyError.asStateFlow()

    init {
        viewModelScope.launch {
            val list = folders.first { it.isNotEmpty() }
            if (_selectedFolderId.value == null) {
                _selectedFolderId.value = (list.firstOrNull { it.role == FolderRole.INBOX } ?: list.first()).id
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh(_selectedFolderId.value) }
    }

    fun selectFolder(id: Long) {
        if (_selectedFolderId.value == id) return
        _selectedFolderId.value = id
        _selectedMessageId.value = null
        viewModelScope.launch { repository.refreshFolder(id) }
    }

    fun selectMessage(id: Long?) {
        _selectedMessageId.value = id
        _bodyError.value = null
        if (id != null) {
            loadBody(id)
            viewModelScope.launch {
                val message = repository.messageById(id)
                if (message != null && !message.isSeen) repository.perform(id, UserAction.MARK_READ)
            }
        }
    }

    /** Opens a message from anywhere, such as a notification, switching to its folder. */
    fun openMessage(id: Long) {
        viewModelScope.launch {
            val message = repository.messageById(id) ?: return@launch
            _selectedFolderId.value = message.folderId
            selectMessage(id)
        }
    }

    /** True when the open folder can archive, meaning an Archive folder exists and this isn't it. */
    val canArchive: StateFlow<Boolean> = combine(folders, _selectedFolderId) { list, selected ->
        list.any { it.role == FolderRole.ARCHIVE } && list.firstOrNull { it.id == selected }?.role != FolderRole.ARCHIVE
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun archiveSelected() = removeSelected(UserAction.ARCHIVE)

    fun deleteSelected() = removeSelected(UserAction.DELETE)

    fun toggleReadSelected() {
        val message = selectedMessage.value ?: return
        viewModelScope.launch {
            repository.perform(message.id, if (message.isSeen) UserAction.MARK_UNREAD else UserAction.MARK_READ)
        }
    }

    fun toggleFlagSelected() {
        val message = selectedMessage.value ?: return
        viewModelScope.launch {
            repository.perform(message.id, if (message.isFlagged) UserAction.UNFLAG else UserAction.FLAG)
        }
    }

    /** Archives or deletes the open message and moves on to the next one, like desktop mail apps. */
    private fun removeSelected(action: UserAction) {
        val id = _selectedMessageId.value ?: return
        val list = messages.value
        val index = list.indexOfFirst { it.id == id }
        val next = list.getOrNull(index + 1) ?: list.getOrNull(index - 1)
        viewModelScope.launch {
            if (repository.perform(id, action)) {
                if (_selectedMessageId.value == id) {
                    _selectedMessageId.value = null
                    next?.let { selectMessage(it.id) }
                }
            }
        }
    }

    fun retryBody() {
        _selectedMessageId.value?.let(::loadBody)
    }

    /** Moves the selection up or down the list, as the arrow keys do. */
    fun moveSelection(delta: Int) {
        val list = messages.value
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == _selectedMessageId.value }
        val next = if (index < 0) 0 else (index + delta).coerceIn(0, list.lastIndex)
        if (next != index) selectMessage(list[next].id)
    }

    fun signOut() {
        viewModelScope.launch { repository.signOut() }
    }

    private fun loadBody(id: Long) {
        viewModelScope.launch {
            val outcome = repository.loadBody(id)
            if (_selectedMessageId.value != id) return@launch
            _bodyError.value = when (outcome) {
                is SyncOutcome.Failed -> outcome.message
                SyncOutcome.AuthFailed -> "iCloud rejected your password."
                else -> null
            }
        }
    }
}
