package com.kayloh20.kyliemail.ui.mail

import com.kayloh20.kyliemail.ui.theme.KylieTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kayloh20.kyliemail.data.db.MessageSummary
import com.kayloh20.kyliemail.data.filing.FolderSuggestion

/** The right-click (or long-press) menu for a message, or for the whole selection when several are selected. */
@Composable
fun MessageContextMenu(
    message: MessageSummary,
    multiple: Boolean,
    canArchive: Boolean,
    onToggleRead: () -> Unit,
    onToggleFlag: () -> Unit,
    onArchive: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    suggestion: FolderSuggestion? = null,
    onFile: (Long) -> Unit = {},
) {
    if (suggestion != null) {
        DropdownMenuItem(
            text = { Text("File to ${suggestion.displayName}") },
            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
            onClick = { onFile(suggestion.folderId) },
        )
        HorizontalDivider()
    }
    val readLabel = when {
        multiple -> "Mark as read or unread"
        message.isSeen -> "Mark as unread"
        else -> "Mark as read"
    }
    val flagLabel = when {
        multiple -> "Flag or remove flag"
        message.isFlagged -> "Remove flag"
        else -> "Flag"
    }
    DropdownMenuItem(
        text = { Text(readLabel) },
        leadingIcon = { Icon(if (message.isSeen) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead, null) },
        onClick = onToggleRead,
    )
    DropdownMenuItem(text = { Text(flagLabel) }, leadingIcon = { Icon(Icons.Outlined.Flag, null) }, onClick = onToggleFlag)
    HorizontalDivider()
    if (canArchive) {
        DropdownMenuItem(text = { Text("Archive") }, leadingIcon = { Icon(Icons.Outlined.Archive, null) }, onClick = onArchive)
    }
    DropdownMenuItem(
        text = { Text("Move to…") },
        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null) },
        trailingIcon = { Shortcut("Ctrl+Shift+M") },
        onClick = onMove,
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("Delete") },
        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
        trailingIcon = { Shortcut("Del") },
        onClick = onDelete,
    )
}

@Composable
private fun Shortcut(keys: String) {
    Text(keys, style = MaterialTheme.typography.labelMedium, color = KylieTheme.colors.textMuted)
}
