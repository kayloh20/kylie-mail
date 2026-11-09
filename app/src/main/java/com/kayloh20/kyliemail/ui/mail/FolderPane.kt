package com.kayloh20.kyliemail.ui.mail

import com.kayloh20.kyliemail.ui.theme.KylieTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.mail.FolderRole

@Composable
fun FolderPane(
    folders: List<FolderEntity>,
    selectedFolderId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            "Kylie Mail",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            folders.forEachIndexed { index, folder ->
                val custom = folder.role == FolderRole.CUSTOM
                val startsGroup = index == 0 || (folders[index - 1].role == FolderRole.CUSTOM) != custom
                if (startsGroup) {
                    item(key = "header-$custom") { SectionLabel(if (custom) "Folders" else "Mailboxes") }
                }
                item(key = folder.id) {
                    FolderRow(folder, selected = folder.id == selectedFolderId, onClick = { onSelect(folder.id) })
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = KylieTheme.colors.textMuted,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** A compact folder row: a slim accent bar marks the selected folder instead of a large pill. */
@Composable
private fun FolderRow(folder: FolderEntity, selected: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .drawBehind {
                if (selected) {
                    val barHeight = 16.dp.toPx()
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(0f, (size.height - barHeight) / 2),
                        size = Size(3.dp.toPx(), barHeight),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(start = 12.dp + 16.dp * folder.depth.coerceAtMost(4), end = 12.dp),
    ) {
        Icon(
            folder.role.icon(),
            contentDescription = null,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            folder.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (folder.unreadCount > 0) {
            Text(
                folder.unreadCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
        }
    }
}

internal fun FolderRole.icon(): ImageVector = when (this) {
    FolderRole.INBOX -> Icons.Outlined.Inbox
    FolderRole.DRAFTS -> Icons.Outlined.Drafts
    FolderRole.SENT -> Icons.AutoMirrored.Outlined.Send
    FolderRole.ARCHIVE -> Icons.Outlined.Archive
    FolderRole.JUNK -> Icons.Outlined.Report
    FolderRole.TRASH -> Icons.Outlined.Delete
    FolderRole.CUSTOM -> Icons.Outlined.Folder
}
