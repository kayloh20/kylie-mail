package com.kayloh20.kyliemail.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Everything the search box needs, so the message list can switch between folder and search views. */
data class SearchUi(
    val state: SearchState?,
    val folderName: String,
    val resultCount: Int,
    /** Folder names for labelling results from other folders. */
    val folderNames: Map<Long, String>,
    val focusRequester: FocusRequester,
    val onOpen: () -> Unit,
    val onTextChange: (String) -> Unit,
    val onClose: () -> Unit,
    val onAllFoldersChange: (Boolean) -> Unit,
    val onSearchServer: () -> Unit,
    val onFocusChange: (Boolean) -> Unit,
    val onSubmit: () -> Unit,
)

/** The search box that replaces the folder title while searching. */
@Composable
fun SearchHeader(search: SearchUi) {
    val state = search.state ?: return
    LaunchedEffect(Unit) { runCatching { search.focusRequester.requestFocus() } }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp)) {
            TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Close search (Esc)", search.onClose)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                if (state.text.isEmpty()) {
                    Text("Search mail", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = state.text,
                    onValueChange = search.onTextChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search.onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(search.focusRequester)
                        .onFocusChanged { search.onFocusChange(it.isFocused) },
                )
            }
            if (state.text.isNotEmpty()) TooltipIconButton(Icons.Outlined.Close, "Clear", { search.onTextChange("") })
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            FilterChip(
                selected = state.allFolders,
                onClick = { search.onAllFoldersChange(true) },
                label = { Text("All folders") },
                shape = MaterialTheme.shapes.small,
            )
            FilterChip(
                selected = !state.allFolders,
                onClick = { search.onAllFoldersChange(false) },
                label = { Text(search.folderName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                shape = MaterialTheme.shapes.small,
            )
            Spacer(Modifier.weight(1f))
            if (state.text.isNotBlank()) {
                Text(
                    if (search.resultCount == 1) "1 result" else "${search.resultCount} results",
                    style = MaterialTheme.typography.labelMedium,
                    color = com.kayloh20.kyliemail.ui.theme.KylieTheme.colors.textMuted,
                )
            }
        }
    }
}

/** Below the results: look further on iCloud for mail that isn't on this device. */
@Composable
fun ServerSearchFooter(search: SearchUi) {
    val state = search.state ?: return
    if (state.text.isBlank()) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        when (val server = state.server) {
            ServerSearchState.Idle -> {
                Text(
                    "Showing mail on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.kayloh20.kyliemail.ui.theme.KylieTheme.colors.textMuted,
                )
                OutlinedButton(onClick = search.onSearchServer, shape = MaterialTheme.shapes.small) {
                    Icon(Icons.Outlined.CloudSync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Search iCloud for older mail")
                }
            }
            is ServerSearchState.Running -> {
                Text(
                    if (server.total == 0) "Searching iCloud…" else "Searching iCloud… folder ${minOf(server.done + 1, server.total)} of ${server.total}",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { if (server.total == 0) 0f else server.done.toFloat() / server.total },
                    modifier = Modifier.width(220.dp),
                )
            }
            is ServerSearchState.Done -> {
                val missed = server.skipped.isNotEmpty()
                Text(
                    serverSearchSummary(server.found, server.skipped, server.searched),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (missed) MaterialTheme.colorScheme.error else com.kayloh20.kyliemail.ui.theme.KylieTheme.colors.textMuted,
                    textAlign = TextAlign.Center,
                )
                if (missed) {
                    // Each folder that was missed, and why, so a refusal is never mistaken for "no results".
                    server.skipped.take(5).forEach { folder ->
                        Text(
                            "${folder.name}: ${folder.reason}",
                            style = MaterialTheme.typography.labelSmall,
                            color = com.kayloh20.kyliemail.ui.theme.KylieTheme.colors.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (server.skipped.size > 5) {
                        Text(
                            "and ${server.skipped.size - 5} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = com.kayloh20.kyliemail.ui.theme.KylieTheme.colors.textMuted,
                        )
                    }
                    TextButton(onClick = search.onSearchServer) { Text("Try again") }
                }
            }
            is ServerSearchState.Failed -> {
                Text("Couldn't search iCloud: ${server.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = search.onSearchServer) { Text("Try again") }
            }
        }
    }
}
