package com.kayloh20.kyliemail.ui.mail

import androidx.compose.foundation.BorderStroke
import com.kayloh20.kyliemail.ui.theme.KylieTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
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
import com.kayloh20.kyliemail.data.filing.FolderSuggestion

/** Smart filing suggestions for the move picker, which load while it opens. */
sealed interface PickerSuggestions {
    /** Turned off, untrained, or not a move: no suggestion section at all. */
    data object Off : PickerSuggestions
    data object Loading : PickerSuggestions
    data class Ready(val list: List<FolderSuggestion>) : PickerSuggestions
}

/** What the keyboard highlight is on: a suggestion by rank, or a folder in the list. */
private sealed interface Highlight {
    data class Suggested(val rank: Int) : Highlight
    data class Folder(val id: Long) : Highlight
}

/** Room kept for suggestions while they load, so nothing moves when they arrive. */
private const val SUGGESTION_SLOTS = 3
private val SuggestionRowHeight: Dp = 56.dp

/**
 * Type to find a folder: arrow keys move, Enter picks, Esc closes. With nothing typed,
 * recently used folders come first.
 *
 * Suggestions sit in their own fixed section above the folder list rather than inside it, and
 * that section keeps the same height from the moment the dialog opens: placeholder rows hold
 * the space and turn into suggestions when they arrive. The folder list never changes under
 * you. Enter before they arrive waits for them, then picks the best one.
 */
@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<FolderEntity>,
    recentFullNames: List<String>,
    excludeId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
    suggestions: PickerSuggestions = PickerSuggestions.Off,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, folders, recentFullNames, excludeId) {
        FolderSearch.rank(folders, query, recentFullNames, excludeId)
    }
    val showSuggestions = suggestions != PickerSuggestions.Off && query.isBlank()
    val ready = (suggestions as? PickerSuggestions.Ready)?.list.orEmpty()
    // Only the first placeholder can be highlighted: it stands for "the best suggestion".
    val suggestionSlots = when {
        !showSuggestions -> 0
        suggestions == PickerSuggestions.Loading -> 1
        else -> ready.size
    }
    val order: List<Highlight> = remember(suggestionSlots, results) {
        List(suggestionSlots) { Highlight.Suggested(it) } + results.map { Highlight.Folder(it.id) }
    }
    var highlighted by remember { mutableStateOf<Highlight?>(null) }
    var waitingToPick by remember { mutableStateOf(false) }
    val current = highlighted?.takeIf { it in order } ?: order.firstOrNull()

    val recent = remember(recentFullNames) { recentFullNames.toSet() }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(query) {
        highlighted = null
        waitingToPick = false
    }
    LaunchedEffect(current, results) {
        val folder = current as? Highlight.Folder ?: return@LaunchedEffect
        val index = results.indexOfFirst { it.id == folder.id }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val shown = visible.isNotEmpty() && index > visible.first().index && index < visible.last().index
        if (!shown) listState.scrollToItem(maxOf(0, index - 2))
    }
    // Enter pressed while suggestions were loading: pick the best once it's known. With none,
    // land on the first folder instead of filing somewhere nobody chose.
    LaunchedEffect(waitingToPick, suggestions) {
        if (!waitingToPick || suggestions !is PickerSuggestions.Ready) return@LaunchedEffect
        waitingToPick = false
        val best = suggestions.list.firstOrNull()
        if (best != null) onPick(best.folderId) else highlighted = results.firstOrNull()?.let { Highlight.Folder(it.id) }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun pick(target: Highlight?) {
        when (target) {
            is Highlight.Folder -> onPick(target.id)
            is Highlight.Suggested -> {
                val suggestion = ready.getOrNull(target.rank)
                if (suggestion != null) onPick(suggestion.folderId) else if (suggestions == PickerSuggestions.Loading) waitingToPick = true
            }
            null -> Unit
        }
    }

    fun step(by: Int) {
        if (order.isEmpty()) return
        val index = order.indexOf(current).coerceAtLeast(0)
        highlighted = order[(index + by).coerceIn(0, order.lastIndex)]
        waitingToPick = false
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
                    keyboardActions = KeyboardActions(onGo = { pick(current) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    step(1)
                                    true
                                }
                                Key.DirectionUp -> {
                                    step(-1)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    pick(current)
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
                if (showSuggestions) {
                    SuggestedSection(
                        suggestions = suggestions,
                        highlighted = (current as? Highlight.Suggested)?.rank,
                        waiting = waitingToPick,
                        onPick = onPick,
                    )
                    SectionLabel("All folders")
                }
                if (results.isEmpty()) {
                    Text(
                        "No folders match \"$query\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    // Gives way to the sections above on short screens instead of pushing them off.
                    modifier = Modifier.weight(1f, fill = false).heightIn(max = 380.dp),
                ) {
                    itemsIndexed(results, key = { _, folder -> folder.id }) { _, folder ->
                        val path = FolderSearch.path(folder)
                        PickerRow(
                            highlighted = current == Highlight.Folder(folder.id),
                            onClick = { onPick(folder.id) },
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
    )
}

/** Always [SUGGESTION_SLOTS] rows tall, loading or not, so the list below stays put. */
@Composable
private fun SuggestedSection(
    suggestions: PickerSuggestions,
    highlighted: Int?,
    waiting: Boolean,
    onPick: (Long) -> Unit,
) {
    SectionLabel("Suggested")
    Box(
        Modifier
            .fillMaxWidth()
            .height(SuggestionRowHeight * SUGGESTION_SLOTS)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        when (suggestions) {
            PickerSuggestions.Loading -> Column(Modifier.semantics { contentDescription = "Loading suggestions" }) {
                repeat(SUGGESTION_SLOTS) { slot -> PlaceholderRow(highlighted = slot == highlighted, waiting = waiting && slot == 0) }
            }
            is PickerSuggestions.Ready -> if (suggestions.list.isEmpty()) {
                Text(
                    "No suggestions for this one yet. Smart filing learns from every move.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                )
            } else {
                Column {
                    suggestions.list.take(SUGGESTION_SLOTS).forEachIndexed { rank, suggestion ->
                        PickerRow(
                            highlighted = rank == highlighted,
                            onClick = { onPick(suggestion.folderId) },
                            modifier = Modifier.height(SuggestionRowHeight),
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = "Suggested", tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(suggestion.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    suggestion.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            PickerSuggestions.Off -> Unit
        }
    }
    HorizontalDivider(color = KylieTheme.colors.borderStrong, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun PickerRow(
    highlighted: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        content = content,
    )
}

/** A suggestion row's shape, gently pulsing, the same height as the real thing. */
@Composable
private fun PlaceholderRow(highlighted: Boolean, waiting: Boolean) {
    val pulse by rememberInfiniteTransition(label = "placeholder").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    PickerRow(highlighted = highlighted, onClick = null, modifier = Modifier.height(SuggestionRowHeight)) {
        Box(Modifier.size(24.dp).alpha(pulse).background(bar, RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).alpha(pulse)) {
            Box(Modifier.fillMaxWidth(0.45f).height(12.dp).background(bar, RoundedCornerShape(4.dp)))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.75f).height(10.dp).background(bar, RoundedCornerShape(4.dp)))
        }
        if (waiting) {
            Text("Picking the best…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
