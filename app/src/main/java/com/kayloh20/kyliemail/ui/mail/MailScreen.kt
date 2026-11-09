package com.kayloh20.kyliemail.ui.mail

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.Alignment
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.kayloh20.kyliemail.data.MailActions
import com.kayloh20.kyliemail.data.db.AttachmentEntity
import androidx.compose.runtime.mutableStateOf
import com.kayloh20.kyliemail.data.compose.ReplyMode
import com.kayloh20.kyliemail.ui.compose.ComposeActivity
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

// Three panes need real room; a phone in landscape (about 900dp) gets two.
private val ExpandedWidth = 1000.dp
private val MediumWidth = 600.dp

@Composable
fun MailScreen(
    viewModel: MailViewModel,
    openRequest: Long?,
    onOpenRequestHandled: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTraining: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val selectedFolderId by viewModel.selectedFolderId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val selectedMessageId by viewModel.selectedMessageId.collectAsStateWithLifecycle()
    val selectedMessage by viewModel.selectedMessage.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val bodyError by viewModel.bodyError.collectAsStateWithLifecycle()
    val remoteImagesAllowed by viewModel.remoteImagesAllowed.collectAsStateWithLifecycle()
    val canArchive by viewModel.canArchive.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val recentMoveTargets by viewModel.recentMoveTargets.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val filingTrained by viewModel.filingTrained.collectAsStateWithLifecycle()
    val filingEnabled by viewModel.filingEnabled.collectAsStateWithLifecycle()
    val inDrafts by viewModel.inDrafts.collectAsStateWithLifecycle()
    val swipeRight by viewModel.swipeRight.collectAsStateWithLifecycle()
    val swipeLeft by viewModel.swipeLeft.collectAsStateWithLifecycle()
    val writing = WritingActions(
        inDrafts = inDrafts,
        onReply = { viewModel.reply(ReplyMode.REPLY) },
        onReplyAll = { viewModel.reply(ReplyMode.REPLY_ALL) },
        onForward = { viewModel.reply(ReplyMode.FORWARD) },
        onEditDraft = viewModel::editDraft,
    )
    val composeContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.composeRequests.collect { draftId -> ComposeActivity.open(composeContext, draftId) }
    }

    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val downloading by viewModel.downloading.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf<AttachmentEntity?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(SaveFileContract()) { uri ->
        val attachment = saving
        saving = null
        if (uri != null && attachment != null) viewModel.writeAttachment(attachment, uri, composeContext.contentResolver)
    }
    LaunchedEffect(Unit) {
        viewModel.fileEvents.collect { event ->
            when (event) {
                is MailViewModel.FileEvent.Open ->
                    openAttachmentFile(composeContext, event.file, event.mimeType)?.let(viewModel::showProblem)
                is MailViewModel.FileEvent.ChooseSaveLocation -> {
                    saving = event.attachment
                    saveLauncher.launch(event.attachment.filename to event.attachment.mimeType)
                }
            }
        }
    }
    val files = FileActions(
        attachments = attachments,
        busy = downloading,
        onOpen = viewModel::openAttachment,
        onSave = viewModel::saveAttachment,
        onOpenInBody = viewModel::openAttachmentAt,
    )
    val snackbarHost = remember { SnackbarHostState() }

    // Show each notice; ones with Undo stay up for the undo window.
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        val result = withTimeoutOrNull(if (current.canUndo) MailActions.UNDO_WINDOW_MS else 4_000L) {
            snackbarHost.showSnackbar(
                message = current.text,
                actionLabel = if (current.canUndo) "Undo" else null,
                duration = SnackbarDuration.Indefinite,
            )
        }
        if (result == SnackbarResult.ActionPerformed) viewModel.undo() else viewModel.dismissNotice(current)
    }

    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            viewModel.openMessage(openRequest)
            onOpenRequestHandled()
        }
    }

    // Android 13+ asks before an app may show notifications.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val folderTitle = folders.firstOrNull { it.id == selectedFolderId }?.displayName ?: "Mail"
    var listWidth by rememberSaveable { mutableFloatStateOf(380f) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val keyModifier = Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val ctrl = event.isCtrlPressed || event.isMetaPressed
            val shift = event.isShiftPressed
            val hasSelection = selection.ids.isNotEmpty()
            when {
                ctrl && shift && event.key == Key.M -> { viewModel.openMovePicker(); true }
                ctrl && event.key == Key.K -> { viewModel.openGoToPicker(); true }
                ctrl && event.key == Key.Comma -> { onOpenSettings(); true }
                ctrl && event.key == Key.N -> { viewModel.newMessage(); true }
                ctrl && shift && event.key == Key.R -> { writing.onReplyAll(); true }
                ctrl && event.key == Key.R -> { writing.onReply(); true }
                ctrl && shift && event.key == Key.F -> { writing.onForward(); true }
                ctrl && event.key == Key.Z -> { viewModel.undo(); true }
                ctrl && event.key == Key.A -> { viewModel.selectAll(); true }
                ctrl -> false
                event.key == Key.DirectionDown -> { viewModel.moveSelection(1, extend = shift); true }
                event.key == Key.DirectionUp -> { viewModel.moveSelection(-1, extend = shift); true }
                event.key == Key.Escape -> if (hasSelection) { viewModel.clearSelection(); true } else false
                // Delete, or Backspace on keyboards without a Delete key.
                event.key == Key.Delete || event.key == Key.Backspace ->
                    if (hasSelection) { viewModel.deleteSelected(); true } else false
                else -> false
            }
        }

    @Composable
    fun folderPane(inDrawer: Boolean, paneModifier: Modifier = Modifier) = FolderPane(
        folders = folders,
        selectedFolderId = selectedFolderId,
        onSelect = { id ->
            viewModel.selectFolder(id)
            if (inDrawer) scope.launch { drawerState.close() }
        },
        onNewMessage = {
            viewModel.newMessage()
            if (inDrawer) scope.launch { drawerState.close() }
        },
        modifier = paneModifier,
    )

    // Picking messages by touch: Back ends it before anything else.
    BackHandler(enabled = selection.touch) { viewModel.clearSelection() }

    @Composable
    fun listPane(showMenu: Boolean, paneModifier: Modifier, swipes: Boolean) = MessageListPane(
        touch = TouchActions(
            swipeEnabled = swipes,
            swipeRight = swipeRight,
            swipeLeft = swipeLeft,
            onSwipe = viewModel::swipe,
            onLongPress = viewModel::startTouchSelection,
            selecting = selection.touch,
            selectionCount = selection.ids.size,
            canArchive = canArchive,
            onClearSelection = viewModel::clearSelection,
            onArchive = viewModel::archiveSelected,
            onDelete = viewModel::deleteSelected,
            onMove = viewModel::openMovePicker,
            onToggleRead = viewModel::toggleReadSelected,
            onToggleFlag = viewModel::toggleFlagSelected,
        ),
        title = folderTitle,
        messages = messages,
        selectedIds = selection.ids,
        cursorId = selection.cursor,
        syncState = syncState,
        listState = listState,
        onClick = { id, ctrl, shift ->
            viewModel.click(id, ctrl, shift)
            runCatching { focusRequester.requestFocus() }
        },
        onContextMenu = viewModel::prepareContextMenu,
        contextMenu = { message, close ->
            MessageContextMenu(
                message = message,
                multiple = selection.ids.size > 1,
                canArchive = canArchive,
                onToggleRead = { close(); viewModel.toggleReadSelected() },
                onToggleFlag = { close(); viewModel.toggleFlagSelected() },
                onArchive = { close(); viewModel.archiveSelected() },
                onMove = { close(); viewModel.openMovePicker() },
                onDelete = { close(); viewModel.deleteSelected() },
                suggestion = suggestions.firstOrNull { it.confident },
                onFile = { id -> close(); viewModel.moveSelected(id) },
                writing = WritingActions(
                    inDrafts = inDrafts,
                    onReply = { close(); writing.onReply() },
                    onReplyAll = { close(); writing.onReplyAll() },
                    onForward = { close(); writing.onForward() },
                    onEditDraft = { close(); writing.onEditDraft() },
                ),
            )
        },
        onRefresh = viewModel::refresh,
        onSignOut = viewModel::signOut,
        onOpenSettings = onOpenSettings,
        onNewMessage = viewModel::newMessage,
        onOpenFolders = if (showMenu) ({ scope.launch { drawerState.open() } }) else null,
        modifier = paneModifier,
    )

    @Composable
    fun detailPane(showBack: Boolean, paneModifier: Modifier) = MessageDetailPane(
        message = selectedMessage,
        selectionCount = selection.ids.size,
        bodyError = bodyError,
        remoteImagesAllowed = remoteImagesAllowed,
        inlineImages = viewModel.inlineImages,
        onRetryBody = viewModel::retryBody,
        onLoadRemoteImages = viewModel::loadRemoteImages,
        onTrustSender = viewModel::trustSender,
        canArchive = canArchive,
        onArchive = viewModel::archiveSelected,
        onDelete = viewModel::deleteSelected,
        onToggleRead = viewModel::toggleReadSelected,
        onToggleFlag = viewModel::toggleFlagSelected,
        onMove = viewModel::openMovePicker,
        onClearSelection = viewModel::clearSelection,
        suggestions = suggestions,
        // Only nudge toward training when suggestions are turned on.
        filingTrained = filingTrained || !filingEnabled,
        onFile = viewModel::moveSelected,
        onTrainFiling = onOpenTraining,
        writing = writing,
        files = files,
        onBack = if (showBack) ({ viewModel.clearSelection() }) else null,
        modifier = paneModifier,
    )

    BoxWithConstraints(modifier.fillMaxSize().then(keyModifier)) {
        val wide = maxWidth >= ExpandedWidth
        val medium = maxWidth >= MediumWidth
        val maxList = (maxWidth.value * 0.6f).coerceAtLeast(300f)

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                folderPane(
                    inDrawer = false,
                    paneModifier = Modifier
                        .width(248.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
                VerticalDivider()
                listPane(showMenu = false, paneModifier = Modifier.width(listWidth.coerceAtMost(maxList).dp).fillMaxHeight(), swipes = false)
                PaneDivider(onDrag = { delta -> listWidth = (listWidth + delta).coerceIn(280f, maxList) })
                detailPane(showBack = false, paneModifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { ModalDrawerSheet { folderPane(inDrawer = true) } },
            ) {
                if (medium) {
                    Row(Modifier.fillMaxSize()) {
                        listPane(showMenu = true, paneModifier = Modifier.width(listWidth.coerceAtMost(maxList).dp).fillMaxHeight(), swipes = true)
                        PaneDivider(onDrag = { delta -> listWidth = (listWidth + delta).coerceIn(280f, maxList) })
                        detailPane(showBack = false, paneModifier = Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    // On a phone, picking several messages by touch stays in the list.
                    val showingMessage = selectedMessageId != null || (selection.ids.size > 1 && !selection.touch)
                    BackHandler(enabled = showingMessage) { viewModel.clearSelection() }
                    if (showingMessage) {
                        detailPane(showBack = true, paneModifier = Modifier.fillMaxSize())
                    } else {
                        listPane(showMenu = true, paneModifier = Modifier.fillMaxSize(), swipes = true)
                    }
                }
            }
        }

        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
    }

    when (picker) {
        PickerMode.MOVE -> {
            val count = viewModel.selectionCount()
            FolderPickerDialog(
                title = if (count == 1) "Move message to…" else "Move $count messages to…",
                folders = folders,
                recentFullNames = recentMoveTargets,
                excludeId = selectedFolderId,
                onPick = viewModel::pickFolder,
                onDismiss = viewModel::closePicker,
                suggestions = suggestions,
            )
        }
        PickerMode.GO_TO -> FolderPickerDialog(
            title = "Go to folder",
            folders = folders,
            recentFullNames = emptyList(),
            excludeId = null,
            onPick = viewModel::pickFolder,
            onDismiss = viewModel::closePicker,
        )
        null -> Unit
    }
}
