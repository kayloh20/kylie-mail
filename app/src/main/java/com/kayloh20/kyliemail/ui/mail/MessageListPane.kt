package com.kayloh20.kyliemail.ui.mail

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import com.kayloh20.kyliemail.ui.theme.KylieTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
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
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Keep the keyboard cursor on screen.
    LaunchedEffect(cursorId) {
        val index = messages.indexOfFirst { it.id == cursorId }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.size > 2 && index > visible.first().index && index < visible.last().index
        if (!fullyVisible) listState.animateScrollToItem(maxOf(0, index - 1))
    }

    Column(modifier) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (onOpenFolders != null) {
                    TooltipIconButton(Icons.Outlined.Menu, "Folders", onOpenFolders)
                }
            },
            actions = {
                TooltipIconButton(Icons.Outlined.Refresh, "Refresh", onRefresh, enabled = !syncState.running)
                Box {
                    TooltipIconButton(Icons.Outlined.MoreVert, "More", { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; onSignOut() })
                    }
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (syncState.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }

        SyncBanner(syncState.lastOutcome, onRetry = onRefresh, onSignOut = onSignOut)

        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (syncState.running) "Checking iCloud…" else "No messages",
                    color = KylieTheme.colors.textMuted,
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        selected = message.id in selectedIds,
                        onClick = { ctrl, shift -> onClick(message.id, ctrl, shift) },
                        onContextMenu = { onContextMenu(message.id) },
                        contextMenu = contextMenu,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
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
    onClick: (ctrl: Boolean, shift: Boolean) -> Unit,
    onContextMenu: () -> Unit,
    contextMenu: @Composable (message: MessageSummary, close: () -> Unit) -> Unit,
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
                onLongClick = {
                    onContextMenu()
                    menuOffset = DpOffset(24.dp, 0.dp)
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp).size(8.dp)) {
            if (unread) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
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
