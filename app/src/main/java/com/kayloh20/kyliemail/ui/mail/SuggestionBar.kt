package com.kayloh20.kyliemail.ui.mail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kayloh20.kyliemail.data.filing.FolderSuggestion
import com.kayloh20.kyliemail.ui.theme.KylieTheme

/**
 * One-click filing: the confident suggestions as buttons, each explaining itself on hover.
 * Before smart filing has learned anything, a hint offers to train it instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionBar(
    suggestions: List<FolderSuggestion>,
    trained: Boolean,
    onFile: (Long) -> Unit,
    onTrain: () -> Unit,
    modifier: Modifier = Modifier,
    start: androidx.compose.ui.unit.Dp = 28.dp,
) {
    val confident = suggestions.filter { it.confident }.take(2)
    if (confident.isEmpty() && trained) return

    FlowRow(
        modifier = modifier.padding(start = start, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        if (confident.isEmpty()) {
            Text(
                "Smart filing can suggest folders once it learns how you file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onTrain) { Text("Train smart filing") }
            return@FlowRow
        }
        Text("File to", style = MaterialTheme.typography.labelLarge, color = KylieTheme.colors.textMuted)
        for (suggestion in confident) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = { PlainTooltip { Text(suggestion.reason) } },
                state = rememberTooltipState(),
            ) {
                AssistChip(
                    onClick = { onFile(suggestion.folderId) },
                    label = { Text(suggestion.displayName) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                    shape = MaterialTheme.shapes.small,
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        leadingIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                )
            }
        }
    }
}
