package com.kayloh20.kyliemail.ui.mail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.NavigationDrawerItem
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
            modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 12.dp),
        )
        LazyColumn {
            items(folders, key = { it.id }) { folder ->
                NavigationDrawerItem(
                    label = { Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    icon = { Icon(folder.role.icon(), contentDescription = null) },
                    badge = {
                        if (folder.unreadCount > 0) Text(folder.unreadCount.toString(), style = MaterialTheme.typography.labelMedium)
                    },
                    selected = folder.id == selectedFolderId,
                    onClick = { onSelect(folder.id) },
                    modifier = Modifier.padding(start = 12.dp + 16.dp * folder.depth.coerceAtMost(4), end = 12.dp),
                )
            }
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
