package com.kayloh20.kyliemail.ui.mail

import androidx.compose.foundation.BorderStroke
import com.kayloh20.kyliemail.ui.theme.KylieTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.mail.FolderSearch

/**
 * Type to find a folder: arrow keys move, Enter picks, Esc closes. With nothing typed,
 * recently used folders come first.
 */
@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<FolderEntity>,
    recentFullNames: List<String>,
    excludeId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableIntStateOf(0) }
    val results = remember(query, folders, recentFullNames, excludeId) {
        FolderSearch.rank(folders, query, recentFullNames, excludeId)
    }
    val recent = remember(recentFullNames) { recentFullNames.toSet() }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(query) { highlighted = 0 }
    LaunchedEffect(highlighted, results) {
        if (results.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val shown = visible.isNotEmpty() && highlighted > visible.first().index && highlighted < visible.last().index
        if (!shown) listState.scrollToItem(maxOf(0, highlighted - 2))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun pickHighlighted() {
        results.getOrNull(highlighted)?.let { onPick(it.id) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, KylieTheme.colors.borderStrong),
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Type a folder name") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { pickHighlighted() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (results.isNotEmpty()) highlighted = (highlighted + 1).coerceAtMost(results.lastIndex)
                                    true
                                }
                                Key.DirectionUp -> {
                                    highlighted = (highlighted - 1).coerceAtLeast(0)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    pickHighlighted()
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        },
                )
                if (results.isEmpty()) {
                    Text(
                        "No folders match \"$query\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(results, key = { _, folder -> folder.id }) { index, folder ->
                        val path = FolderSearch.path(folder)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index == highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                )
                                .clickable { onPick(folder.id) }
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                        ) {
                            Icon(folder.role.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (path != folder.displayName) {
                                    Text(
                                        path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (folder.fullName in recent) {
                                Icon(
                                    Icons.Outlined.History,
                                    contentDescription = "Recently used",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
