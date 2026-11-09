package com.kayloh20.kyliemail.ui.mail

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val ExpandedWidth = 840.dp
private val MediumWidth = 600.dp

@Composable
fun MailScreen(viewModel: MailViewModel, modifier: Modifier = Modifier) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val selectedFolderId by viewModel.selectedFolderId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val selectedMessageId by viewModel.selectedMessageId.collectAsStateWithLifecycle()
    val selectedMessage by viewModel.selectedMessage.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val bodyError by viewModel.bodyError.collectAsStateWithLifecycle()
    val remoteImagesAllowed by viewModel.remoteImagesAllowed.collectAsStateWithLifecycle()

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
            when (event.key) {
                Key.DirectionDown -> { viewModel.moveSelection(1); true }
                Key.DirectionUp -> { viewModel.moveSelection(-1); true }
                Key.Escape -> if (selectedMessageId != null) { viewModel.selectMessage(null); true } else false
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
        modifier = paneModifier,
    )

    @Composable
    fun listPane(showMenu: Boolean, paneModifier: Modifier) = MessageListPane(
        title = folderTitle,
        messages = messages,
        selectedMessageId = selectedMessageId,
        syncState = syncState,
        listState = listState,
        onSelect = viewModel::selectMessage,
        onRefresh = viewModel::refresh,
        onSignOut = viewModel::signOut,
        onOpenFolders = if (showMenu) ({ scope.launch { drawerState.open() } }) else null,
        modifier = paneModifier,
    )

    @Composable
    fun detailPane(showBack: Boolean, paneModifier: Modifier) = MessageDetailPane(
        message = selectedMessage,
        bodyError = bodyError,
        remoteImagesAllowed = remoteImagesAllowed,
        inlineImages = viewModel.inlineImages,
        onRetryBody = viewModel::retryBody,
        onLoadRemoteImages = viewModel::loadRemoteImages,
        onTrustSender = viewModel::trustSender,
        onBack = if (showBack) ({ viewModel.selectMessage(null) }) else null,
        modifier = paneModifier,
    )

    BoxWithConstraints(modifier.fillMaxSize().then(keyModifier)) {
        val wide = maxWidth >= ExpandedWidth
        val medium = maxWidth >= MediumWidth
        val maxList = (maxWidth.value * 0.6f).coerceAtLeast(300f)

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                folderPane(inDrawer = false, paneModifier = Modifier.width(248.dp).fillMaxHeight())
                VerticalDivider()
                listPane(showMenu = false, paneModifier = Modifier.width(listWidth.coerceAtMost(maxList).dp).fillMaxHeight())
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
                        listPane(showMenu = true, paneModifier = Modifier.width(listWidth.coerceAtMost(maxList).dp).fillMaxHeight())
                        PaneDivider(onDrag = { delta -> listWidth = (listWidth + delta).coerceIn(280f, maxList) })
                        detailPane(showBack = false, paneModifier = Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    val showingMessage = selectedMessageId != null
                    BackHandler(enabled = showingMessage) { viewModel.selectMessage(null) }
                    if (showingMessage) {
                        detailPane(showBack = true, paneModifier = Modifier.fillMaxSize())
                    } else {
                        listPane(showMenu = true, paneModifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
