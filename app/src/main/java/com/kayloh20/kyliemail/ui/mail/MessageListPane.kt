package com.kayloh20.kyliemail.ui.mail

import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import com.kayloh20.kyliemail.ui.theme.KylieTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import com.kayloh20.kyliemail.data.settings.SwipeAction
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayloh20.kyliemail.data.db.MessageSummary
import com.kayloh20.kyliemail.data.sync.SyncOutcome
import com.kayloh20.kyliemail.data.sync.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageListPane(
    title: String,
    messages: List<MessageSummary>,
    selectedIds: Set<Long>,
    cursorId: Long?,
    syncState: SyncState,
    listState: LazyListState,
    onClick: (id: Long, ctrl: Boolean, shift: Boolean) -> Unit,
    onContextMenu: (Long) -> Unit,
    contextMenu: @Composable (message: MessageSummary, close: () -> Unit) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
    onOpenFolders: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onNewMessage: () -> Unit,
    touch: TouchActions,
    search: SearchUi? = null,
    modifier: Modifier = Modifier,
) {
    val searching = search?.state != null
    var menuOpen by remember { mutableStateOf(false) }
    // The pull indicator only shows for refreshes started by pulling; others use the bar under the title.
    var pulled by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(syncState.running) {
        if (wasRunning && !syncState.running) pulled = false
        wasRunning = syncState.running
    }

    // Keep the keyboard cursor on screen.
    LaunchedEffect(cursorId) {
        val index = messages.indexOfFirst { it.id == cursorId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.size > 2 && index > visible.first().index && index < visible.last().index
        if (!fullyVisible) listState.animateScrollToItem(maxOf(0, index - 1))
    }

    Column(modifier) {
        if (touch.selecting) {
            SelectionBar(touch)
        } else if (searching) {
            SearchHeader(search!!)
        } else TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (onOpenFolders != null) {
                    TooltipIconButton(Icons.Outlined.Menu, "Folders", onOpenFolders)
                }
            },
            actions = {
                // Without the folder sidebar, New message lives here.
                if (onOpenFolders != null) TooltipIconButton(Icons.Outlined.Edit, "New message (Ctrl+N)", onNewMessage)
                if (search != null) TooltipIconButton(Icons.Outlined.Search, "Search (Ctrl+F)", search.onOpen)
                // Stays pressable mid-sync: pressing it then is how to un-stick one.
                TooltipIconButton(Icons.Outlined.Refresh, "Refresh", onRefresh)
                Box {
                    TooltipIconButton(Icons.Outlined.MoreVert, "More", { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            trailingIcon = {
                                Text("Ctrl+,", style = MaterialTheme.typography.labelMedium, color = KylieTheme.colors.textMuted)
                            },
                            onClick = { menuOpen = false; onOpenSettings() },
                        )
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; onSignOut() })
                    }
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (syncState.running && !pulled) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }

        SyncBanner(syncState.lastOutcome, onRetry = onRefresh, onSignOut = onSignOut)

        PullToRefreshBox(
            isRefreshing = pulled,
            onRefresh = {
                pulled = true
                onRefresh()
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                if (messages.isEmpty()) {
                    // Still scrollable, so an empty folder can be pulled to refresh.
                    item(key = "empty") {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    searching && search!!.state!!.text.isBlank() -> "Type to search every folder on this device"
                                    searching -> "No matches on this device"
                                    syncState.running -> "Checking iCloud…"
                                    else -> "No messages"
                                },
                                color = KylieTheme.colors.textMuted,
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { message ->
                    Column(Modifier.animateItem()) {
                        SwipeableRow(message, touch) {
                            MessageRow(
                                message = message,
                                selected = message.id in selectedIds,
                                selecting = touch.selecting,
                                onClick = { ctrl, shift -> onClick(message.id, ctrl, shift) },
                                onContextMenu = { onContextMenu(message.id) },
                                onLongPress = { touch.onLongPress(message.id) },
                                contextMenu = contextMenu,
                                // Results from every folder say which folder they're in.
                                folderLabel = if (searching && search!!.state!!.allFolders) search.folderNames[message.folderId] else null,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (searching) item(key = "server-search") { ServerSearchFooter(search!!) }
            }
        }
    }
}

/** Touch-screen behaviour for the message list: swipes, long-press selecting, and the selection bar. */
data class TouchActions(
    val swipeEnabled: Boolean,
    val swipes: SwipeConfig,
    val onSwipe: (Long, SwipeAction) -> Unit,
    val onLongPress: (Long) -> Unit,
    val selecting: Boolean,
    val selectionCount: Int,
    val canArchive: Boolean,
    val onClearSelection: () -> Unit,
    val onArchive: () -> Unit,
    val onDelete: () -> Unit,
    val onMove: () -> Unit,
    val onToggleRead: () -> Unit,
    val onToggleFlag: () -> Unit,
)

/** Replaces the title while picking messages by touch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(touch: TouchActions) {
    TopAppBar(
        title = { Text("${touch.selectionCount} selected") },
        navigationIcon = { TooltipIconButton(Icons.Outlined.Close, "Done", touch.onClearSelection) },
        actions = {
            if (touch.canArchive) TooltipIconButton(Icons.Outlined.Archive, "Archive", touch.onArchive)
            TooltipIconButton(Icons.Outlined.Delete, "Delete", touch.onDelete)
            TooltipIconButton(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to…", touch.onMove)
            TooltipIconButton(Icons.Outlined.MarkEmailRead, "Mark as read or unread", touch.onToggleRead)
            TooltipIconButton(Icons.Outlined.Flag, "Flag or remove flag", touch.onToggleFlag)
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        windowInsets = WindowInsets(0),
    )
}

/**
 * Swipe a message left or right to act on it: a short swipe for one action, past about 60% for the
 * long one. The colour, icon and label follow the finger, with a tick when crossing into a zone.
 * Letting go acts once, then the row springs back (removed messages then leave the list).
 */
@Composable
private fun SwipeableRow(message: MessageSummary, touch: TouchActions, content: @Composable () -> Unit) {
    val config = touch.swipes
    if (!touch.swipeEnabled || touch.selecting || !(config.rightEnabled || config.leftEnabled)) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var dragX by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(1) }
    var zone by remember { mutableStateOf(SwipeZone.NONE) }
    // The look at the moment of letting go, kept while the row springs back so it can't change midway.
    var released by remember { mutableStateOf<Triple<Color, ImageVector, String>?>(null) }
    var springing by remember { mutableStateOf(false) }

    val looks = SwipeAction.entries.associateWith { swipeLook(it, message) }
    val currentLooks by rememberUpdatedState(looks)
    val currentMessage by rememberUpdatedState(message)
    val currentTouch by rememberUpdatedState(touch)

    val dragState = rememberDraggableState { delta ->
        if (springing) return@rememberDraggableState
        val swipes = currentTouch.swipes
        val reach = width * 0.85f
        dragX = (dragX + delta).coerceIn(if (swipes.leftEnabled) -reach else 0f, if (swipes.rightEnabled) reach else 0f)
        val now = SwipeZones.zone(dragX / width, swipes)
        if (now != zone) {
            if (now != SwipeZone.NONE) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            zone = now
        }
    }

    Box(
        Modifier
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = !springing,
                onDragStopped = {
                    val action = SwipeZones.action(dragX / width, currentTouch.swipes)
                    if (action != SwipeAction.NONE) {
                        released = currentLooks.getValue(action)
                        currentTouch.onSwipe(currentMessage.id, action)
                    }
                    springing = true
                    scope.launch {
                        try {
                            animate(dragX, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> dragX = value }
                        } finally {
                            dragX = 0f
                            zone = SwipeZone.NONE
                            released = null
                            springing = false
                        }
                    }
                },
            ),
    ) {
        if (dragX != 0f) {
            val live = SwipeZones.action(dragX / width, config)
            val (color, icon, label) = released ?: if (live != SwipeAction.NONE) looks.getValue(live) else neutralLook()
            val right = dragX > 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (right) Arrangement.Start else Arrangement.End,
                modifier = Modifier.matchParentSize().background(color).padding(horizontal = 24.dp),
            ) {
                if (label.isNotEmpty()) {
                    Icon(icon, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Box(Modifier.offset { IntOffset(dragX.roundToInt(), 0) }) { content() }
    }
}

/** Before a drag reaches a zone: a quiet strip with no label. */
@Composable
private fun neutralLook(): Triple<Color, ImageVector, String> =
    Triple(MaterialTheme.colorScheme.surfaceVariant, Icons.Outlined.Close, "")

@Composable
private fun swipeLook(action: SwipeAction, message: MessageSummary): Triple<Color, ImageVector, String> = when (action) {
    SwipeAction.READ -> Triple(
        MaterialTheme.colorScheme.primary,
        if (message.isSeen) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
        if (message.isSeen) "Unread" else "Read",
    )
    SwipeAction.FLAG -> Triple(KylieTheme.colors.warning, Icons.Outlined.Flag, if (message.isFlagged) "Unflag" else "Flag")
    SwipeAction.ARCHIVE -> Triple(MaterialTheme.colorScheme.primary, Icons.Outlined.Archive, "Archive")
    SwipeAction.MOVE -> Triple(MaterialTheme.colorScheme.primary, Icons.AutoMirrored.Outlined.DriveFileMove, "Move")
    SwipeAction.DELETE -> Triple(MaterialTheme.colorScheme.error, Icons.Outlined.Delete, "Delete")
    SwipeAction.NONE -> Triple(Color.Transparent, Icons.Outlined.Close, "")
}

@Composable
private fun SyncBanner(outcome: SyncOutcome?, onRetry: () -> Unit, onSignOut: () -> Unit) {
    val (message, action, onAction) = when (outcome) {
        SyncOutcome.AuthFailed -> Triple(
            "iCloud rejected your password. Sign in again with a new app-specific password.",
            "Sign out",
            onSignOut,
        )
        is SyncOutcome.Failed -> Triple(outcome.message, "Retry", onRetry)
        else -> return
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: MessageSummary,
    selected: Boolean,
    selecting: Boolean,
    onClick: (ctrl: Boolean, shift: Boolean) -> Unit,
    onContextMenu: () -> Unit,
    onLongPress: () -> Unit,
    contextMenu: @Composable (message: MessageSummary, close: () -> Unit) -> Unit,
    folderLabel: String? = null,
) {
    val unread = !message.isSeen
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    // Modifier keys held at the moment of the press, read when the click completes.
    val pressModifiers = remember { arrayOf(false, false) }
    var menuOffset by remember { mutableStateOf<DpOffset?>(null) }
    var rowHeight by remember { mutableIntStateOf(0) }

    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowHeight = it.height }
            .background(background)
            .drawBehind {
                // The same slim accent bar the folder list uses marks selected messages.
                if (selected) drawRect(accent, size = Size(3.dp.toPx(), size.height))
            }
            .pointerInput(message.id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        val keys = event.keyboardModifiers
                        pressModifiers[0] = keys.isCtrlPressed || keys.isMetaPressed
                        pressModifiers[1] = keys.isShiftPressed
                        if (event.buttons.isSecondaryPressed) {
                            // Right-click: open the menu where the pointer is, and keep it from counting as a click.
                            event.changes.forEach { it.consume() }
                            val position = event.changes.first().position
                            onContextMenu()
                            menuOffset = with(density) { DpOffset(position.x.toDp(), (position.y - rowHeight).toDp()) }
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = {
                    onClick(pressModifiers[0], pressModifiers[1])
                    pressModifiers[0] = false
                    pressModifiers[1] = false
                },
                // A long press starts picking messages; right-click is the menu.
                onLongClick = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (selecting) {
            // While picking by touch, each row shows whether it's picked.
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else KylieTheme.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Box(Modifier.padding(top = 6.dp).size(8.dp)) {
                if (unread) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.fromName.ifBlank { "(no sender)" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (message.hasAttachments) {
                    Icon(Icons.Outlined.Attachment, contentDescription = "Has attachments", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (message.isFlagged) {
                    Icon(Icons.Filled.Flag, contentDescription = "Flagged", tint = KylieTheme.colors.warning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (folderLabel != null) {
                    Text(
                        "$folderLabel · ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                Text(
                    formatListDate(message.receivedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = KylieTheme.colors.textMuted,
                )
            }
            Text(
                message.subject.ifBlank { "(no subject)" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.preview.isNotBlank()) {
                Text(
                    message.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    DropdownMenu(
        expanded = menuOffset != null,
        onDismissRequest = { menuOffset = null },
        offset = menuOffset ?: DpOffset.Zero,
    ) {
        contextMenu(message) { menuOffset = null }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
        }
    }
}
