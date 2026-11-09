package com.kayloh20.kyliemail.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.filing.FilingEngine
import com.kayloh20.kyliemail.data.filing.TrainingState
import com.kayloh20.kyliemail.ui.mail.icon
import com.kayloh20.kyliemail.ui.theme.KylieTheme
import java.text.NumberFormat

@Composable
fun TrainingDialog(
    folders: List<FolderEntity>,
    trainedNames: Set<String>,
    state: TrainingState,
    onStart: (List<FolderEntity>) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TrainingContent(folders, trainedNames, state, onStart, onStop, onClose)
    }
}

/** Which folders smart filing can learn from: the user's own folders and Archive. */
fun trainableFolders(folders: List<FolderEntity>) =
    folders.filter { it.selectable && it.role in FilingEngine.TARGET_ROLES }

@Composable
fun TrainingContent(
    folders: List<FolderEntity>,
    trainedNames: Set<String>,
    state: TrainingState,
    onStart: (List<FolderEntity>) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidates = remember(folders) { trainableFolders(folders) }
    // Start from what was trained before, or everything the first time.
    var selected by remember(candidates, trainedNames) {
        mutableStateOf(
            if (trainedNames.isEmpty()) candidates.map { it.fullName }.toSet() else trainedNames intersect candidates.map { it.fullName }.toSet(),
        )
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, KylieTheme.colors.borderStrong),
        modifier = modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Train smart filing", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.padding(top = 12.dp))

            when (state) {
                is TrainingState.Running -> Running(state, onStop, onClose)
                is TrainingState.Finished -> Result(
                    icon = Icons.Outlined.CheckCircle,
                    tint = KylieTheme.colors.success,
                    text = "Learned from ${count(state.learned)} new emails in ${plural(state.folders, "folder")}. " +
                        "Suggestions are ready.",
                    onClose = onClose,
                )
                is TrainingState.Failed -> Result(
                    icon = Icons.Outlined.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    text = "Training stopped: ${state.message}. What it learned so far is kept; try again to continue.",
                    onClose = onClose,
                )
                TrainingState.Idle -> Picker(candidates, selected, { selected = it }, trainedNames, onStart, onClose)
            }
        }
    }
}

@Composable
private fun Picker(
    candidates: List<FolderEntity>,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    trainedNames: Set<String>,
    onStart: (List<FolderEntity>) -> Unit,
    onClose: () -> Unit,
) {
    Text(
        "Choose the folders that show how you file. Kylie Mail reads the sender, recipients and subject of up to " +
            "${count(FilingEngine.TRAINING_DEPTH)} recent emails in each, never the message text. Everything stays on this device.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    Spacer(Modifier.padding(top = 8.dp))

    if (candidates.isEmpty()) {
        Text(
            "You don't have any folders yet. Create folders in iCloud Mail, then train.",
            modifier = Modifier.padding(24.dp),
        )
    } else {
        val all = candidates.map { it.fullName }.toSet()
        val toggle = when {
            selected.containsAll(all) -> ToggleableState.On
            selected.isEmpty() -> ToggleableState.Off
            else -> ToggleableState.Indeterminate
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectedChange(if (toggle == ToggleableState.On) emptySet() else all) }
                .padding(horizontal = 12.dp),
        ) {
            TriStateCheckbox(state = toggle, onClick = { onSelectedChange(if (toggle == ToggleableState.On) emptySet() else all) })
            Text("All folders", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "${selected.size} of ${candidates.size}",
                style = MaterialTheme.typography.labelMedium,
                color = KylieTheme.colors.textMuted,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        HorizontalDivider()
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(candidates, key = { it.id }) { folder ->
                val checked = folder.fullName in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectedChange(if (checked) selected - folder.fullName else selected + folder.fullName) }
                        .padding(start = 12.dp + 16.dp * folder.depth.coerceAtMost(4), end = 24.dp),
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onSelectedChange(if (it) selected + folder.fullName else selected - folder.fullName) },
                    )
                    Icon(folder.role.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    // Indentation already shows nesting, so the folder's own name is enough.
                    Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (folder.fullName in trainedNames) {
                        Text("Trained", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                    }
                    if (folder.totalCount > 0) {
                        Text(count(folder.totalCount), style = MaterialTheme.typography.labelMedium, color = KylieTheme.colors.textMuted)
                    }
                }
            }
        }
        HorizontalDivider()
    }

    val forgetting = trainedNames - selected
    if (forgetting.isNotEmpty()) {
        Text(
            "Unchecked folders that were trained before will be forgotten.",
            style = MaterialTheme.typography.bodySmall,
            color = KylieTheme.colors.textMuted,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
        )
    }
    Buttons {
        TextButton(onClick = onClose) { Text("Cancel") }
        Button(
            onClick = { onStart(candidates.filter { it.fullName in selected }) },
            enabled = selected.isNotEmpty() || forgetting.isNotEmpty(),
            shape = MaterialTheme.shapes.small,
        ) { Text(if (selected.isEmpty()) "Forget all" else "Start training") }
    }
}

@Composable
private fun Running(state: TrainingState.Running, onStop: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Learning from ${state.folder}…", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { (state.index - 1).toFloat() / state.total },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Folder ${state.index} of ${state.total} · ${count(state.learned)} emails learned so far",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "You can close this; training keeps going in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = KylieTheme.colors.textMuted,
        )
    }
    Buttons {
        TextButton(onClick = onStop) { Text("Stop") }
        Button(onClick = onClose, shape = MaterialTheme.shapes.small) { Text("Run in background") }
    }
}

@Composable
private fun Result(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, text: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(horizontal = 24.dp)) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
    Buttons {
        Button(onClick = onClose, shape = MaterialTheme.shapes.small) { Text("Done") }
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
    ) { content() }
}

private fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "${count(n)} ${word}s"
