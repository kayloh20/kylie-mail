package com.kayloh20.kyliemail.ui.mail

import com.kayloh20.kyliemail.ui.theme.KylieTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.db.AttachmentEntity
import com.kayloh20.kyliemail.data.filing.FolderSuggestion
import com.kayloh20.kyliemail.data.mail.InlineImageStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailPane(
    message: MessageEntity?,
    selectionCount: Int,
    bodyError: String?,
    remoteImagesAllowed: Boolean,
    inlineImages: InlineImageStore,
    onRetryBody: () -> Unit,
    onLoadRemoteImages: () -> Unit,
    onTrustSender: () -> Unit,
    canArchive: Boolean,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleFlag: () -> Unit,
    onMove: () -> Unit,
    onClearSelection: () -> Unit,
    suggestions: List<FolderSuggestion>,
    filingTrained: Boolean,
    onFile: (Long) -> Unit,
    onTrainFiling: () -> Unit,
    writing: WritingActions,
    files: FileActions,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // A phone shows one pane at a time and arrives here with a Back button; space there is precious.
    val compact = onBack != null
    val edge = if (compact) 16.dp else 28.dp

    Column(modifier) {
        if (onBack != null && (selectionCount > 1 || message == null)) {
            TopAppBar(
                title = {},
                navigationIcon = { TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
                windowInsets = WindowInsets(0),
            )
        }

        if (selectionCount > 1) {
            BulkSelection(
                count = selectionCount,
                canArchive = canArchive,
                onArchive = onArchive,
                onDelete = onDelete,
                onToggleRead = onToggleRead,
                onToggleFlag = onToggleFlag,
                onMove = onMove,
                onClearSelection = onClearSelection,
                suggestions = suggestions,
                onFile = onFile,
            )
            return@Column
        }

        if (message == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.MarkEmailUnread, contentDescription = null, tint = KylieTheme.colors.textMuted)
                    Text("Select a message to read it", color = KylieTheme.colors.textMuted)
                }
            }
            return@Column
        }

        var remoteBlocked by remember(message.id) { mutableStateOf(false) }
        val html = message.bodyHtml?.takeIf { it.isNotBlank() }

        MessageToolbar(
            compact = compact,
            onBack = onBack,
            writing = writing,
            message = message,
            canArchive = canArchive,
            onArchive = onArchive,
            onDelete = onDelete,
            onToggleRead = onToggleRead,
            onToggleFlag = onToggleFlag,
            onMove = onMove,
            onPrint = if (message.bodyLoaded) {
                { MessagePrinter.print(context, message, remoteImagesAllowed, inlineImages) }
            } else {
                null
            },
        )
        MessageHeader(message = message, compact = compact, edge = edge)
        SuggestionBar(suggestions, filingTrained, onFile, onTrainFiling, start = edge)
        AttachmentStrip(files.attachments, files.busy, files.onOpen, files.onSave, start = edge)
        HorizontalDivider()

        if (remoteBlocked && !remoteImagesAllowed) {
            RemoteImagesBanner(
                sender = message.fromAddress,
                compact = compact,
                edge = edge,
                onLoad = onLoadRemoteImages,
                onTrust = onTrustSender,
            )
        }

        when {
            message.bodyLoaded && html != null -> EmailWebView(
                messageId = message.id,
                html = html,
                allowRemote = remoteImagesAllowed,
                inlineImages = inlineImages,
                onRemoteBlocked = { remoteBlocked = true },
                onOpenAttachment = files.onOpenInBody,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            message.bodyLoaded -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        message.bodyText?.trim().orEmpty().ifBlank { "(This message has no text.)" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = edge, vertical = 20.dp),
                    )
                }
            }
            bodyError != null -> Column(Modifier.padding(28.dp)) {
                Text("Couldn't load this message: $bodyError", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryBody) { Text("Try again") }
            }
            else -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun MessageToolbar(
    compact: Boolean,
    onBack: (() -> Unit)?,
    writing: WritingActions,
    message: MessageEntity,
    canArchive: Boolean,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleFlag: () -> Unit,
    onMove: () -> Unit,
    onPrint: (() -> Unit)?,
) {
    if (compact) {
        CompactToolbar(onBack, writing, message, canArchive, onArchive, onDelete, onToggleRead, onToggleFlag, onMove, onPrint)
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (writing.inDrafts) {
            TooltipIconButton(Icons.Outlined.Edit, "Edit draft", writing.onEditDraft)
        } else {
            TooltipIconButton(Icons.AutoMirrored.Outlined.Reply, "Reply (Ctrl+R)", writing.onReply)
            TooltipIconButton(Icons.AutoMirrored.Outlined.ReplyAll, "Reply all (Ctrl+Shift+R)", writing.onReplyAll)
            TooltipIconButton(Icons.AutoMirrored.Outlined.Forward, "Forward (Ctrl+Shift+F)", writing.onForward)
        }
        VerticalDivider(Modifier.height(24.dp).padding(horizontal = 8.dp))
        if (canArchive) TooltipIconButton(Icons.Outlined.Archive, "Archive", onArchive)
        TooltipIconButton(Icons.Outlined.Delete, "Delete (Delete key)", onDelete)
        TooltipIconButton(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to… (Ctrl+Shift+M)", onMove)
        TooltipIconButton(
            if (message.isSeen) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
            if (message.isSeen) "Mark as unread" else "Mark as read",
            onToggleRead,
        )
        TooltipIconButton(
            if (message.isFlagged) Icons.Filled.Flag else Icons.Outlined.Flag,
            if (message.isFlagged) "Remove flag" else "Flag",
            onToggleFlag,
            tint = if (message.isFlagged) KylieTheme.colors.warning else LocalContentColor.current,
        )
        Spacer(Modifier.weight(1f))
        if (onPrint != null) TooltipIconButton(Icons.Outlined.Print, "Print or save as PDF", onPrint)
    }
}

/** One row on phones: Back, the everyday actions, and the rest under ⋮. */
@Composable
private fun CompactToolbar(
    onBack: (() -> Unit)?,
    writing: WritingActions,
    message: MessageEntity,
    canArchive: Boolean,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleFlag: () -> Unit,
    onMove: () -> Unit,
    onPrint: (() -> Unit)?,
) {
    var more by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
        Spacer(Modifier.weight(1f))
        if (writing.inDrafts) {
            TooltipIconButton(Icons.Outlined.Edit, "Edit draft", writing.onEditDraft)
        } else {
            TooltipIconButton(Icons.AutoMirrored.Outlined.Reply, "Reply", writing.onReply)
        }
        if (canArchive) TooltipIconButton(Icons.Outlined.Archive, "Archive", onArchive)
        TooltipIconButton(Icons.Outlined.Delete, "Delete", onDelete)
        TooltipIconButton(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to…", onMove)
        Box {
            TooltipIconButton(Icons.Outlined.MoreVert, "More", { more = true })
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                if (!writing.inDrafts) {
                    DropdownMenuItem(
                        text = { Text("Reply all") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ReplyAll, null) },
                        onClick = { more = false; writing.onReplyAll() },
                    )
                    DropdownMenuItem(
                        text = { Text("Forward") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Forward, null) },
                        onClick = { more = false; writing.onForward() },
                    )
                    HorizontalDivider()
                }
                DropdownMenuItem(
                    text = { Text(if (message.isSeen) "Mark as unread" else "Mark as read") },
                    leadingIcon = { Icon(if (message.isSeen) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead, null) },
                    onClick = { more = false; onToggleRead() },
                )
                DropdownMenuItem(
                    text = { Text(if (message.isFlagged) "Remove flag" else "Flag") },
                    leadingIcon = {
                        Icon(
                            if (message.isFlagged) Icons.Filled.Flag else Icons.Outlined.Flag,
                            null,
                            tint = if (message.isFlagged) KylieTheme.colors.warning else LocalContentColor.current,
                        )
                    },
                    onClick = { more = false; onToggleFlag() },
                )
                if (onPrint != null) {
                    DropdownMenuItem(
                        text = { Text("Print or save as PDF") },
                        leadingIcon = { Icon(Icons.Outlined.Print, null) },
                        onClick = { more = false; onPrint() },
                    )
                }
            }
        }
    }
}

/** The open message's files and what clicking them does. */
data class FileActions(
    val attachments: List<AttachmentEntity>,
    val busy: Set<Long>,
    val onOpen: (AttachmentEntity) -> Unit,
    val onSave: (AttachmentEntity) -> Unit,
    val onOpenInBody: (String) -> Unit,
)

/** Reply, reply all, forward, and editing a saved draft. */
data class WritingActions(
    val inDrafts: Boolean,
    val onReply: () -> Unit,
    val onReplyAll: () -> Unit,
    val onForward: () -> Unit,
    val onEditDraft: () -> Unit,
)

@Composable
private fun BulkSelection(
    count: Int,
    canArchive: Boolean,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleFlag: () -> Unit,
    onMove: () -> Unit,
    onClearSelection: () -> Unit,
    suggestions: List<FolderSuggestion>,
    onFile: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$count messages selected", style = MaterialTheme.typography.titleLarge)
            Row {
                if (canArchive) TooltipIconButton(Icons.Outlined.Archive, "Archive", onArchive)
                TooltipIconButton(Icons.Outlined.Delete, "Delete (Delete key)", onDelete)
                TooltipIconButton(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to… (Ctrl+Shift+M)", onMove)
                TooltipIconButton(Icons.Outlined.MarkEmailRead, "Mark as read or unread", onToggleRead)
                TooltipIconButton(Icons.Outlined.Flag, "Flag or remove flag", onToggleFlag)
            }
            SuggestionBar(suggestions, trained = true, onFile = onFile, onTrain = {})
            TextButton(onClick = onClearSelection) { Text("Clear selection") }
        }
    }
}

/**
 * Subject, then who it's from. Collapsed (the default on phones) it's one line, "Sender · time";
 * tapping it shows the full address, date and recipients.
 */
@Composable
private fun MessageHeader(message: MessageEntity, compact: Boolean, edge: androidx.compose.ui.unit.Dp) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(!compact) }
    Column(Modifier.fillMaxWidth().padding(start = edge, end = 4.dp, top = 4.dp, bottom = if (compact) 8.dp else 12.dp)) {
        SelectionContainer(Modifier.padding(end = 12.dp)) {
            Text(
                message.subject.ifBlank { "(no subject)" },
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp),
        ) {
            Text(
                if (expanded) senderLine(message) else message.fromName.ifBlank { message.fromAddress },
                style = MaterialTheme.typography.titleSmall,
                maxLines = if (expanded) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!expanded) {
                Text(
                    " · ${formatListDate(message.sentAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = KylieTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Hide details" else "Show details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
            )
        }
        if (expanded) SelectionContainer(Modifier.padding(end = 12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    formatFullDate(message.sentAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.toList.isNotBlank()) {
                    Text(
                        "To: ${message.toList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (message.ccList.isNotBlank()) {
                    Text(
                        "Cc: ${message.ccList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RemoteImagesBanner(
    sender: String,
    compact: Boolean,
    edge: androidx.compose.ui.unit.Dp,
    onLoad: () -> Unit,
    onTrust: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = edge - 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.HideImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (compact) 18.dp else 24.dp),
            )
            Text(
                // The full reason is a tooltip-length sentence; phones get the short version.
                if (compact) "Images hidden" else "Remote images are hidden so the sender can't tell you opened this.",
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(horizontal = if (compact) 8.dp else 12.dp),
            )
            TextButton(onClick = onLoad) { Text(if (compact) "Load" else "Load images") }
            if (sender.isNotBlank()) TextButton(onClick = onTrust) { Text(if (compact) "Always load" else "Always from sender") }
        }
    }
}
