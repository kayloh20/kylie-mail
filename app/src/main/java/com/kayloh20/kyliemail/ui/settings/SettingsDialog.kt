package com.kayloh20.kyliemail.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kayloh20.kyliemail.data.settings.ThemeMode
import com.kayloh20.kyliemail.data.settings.SwipeAction
import com.kayloh20.kyliemail.data.offline.KeepAllProgress
import com.kayloh20.kyliemail.data.mail.BodyParser
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kayloh20.kyliemail.ui.mail.TooltipIconButton
import com.kayloh20.kyliemail.ui.theme.KylieTheme

/** Your name and signature for mail you send. */
data class SendingSettings(
    val name: String,
    val onNameChange: (String) -> Unit,
    val signature: String,
    val onSignatureChange: (String) -> Unit,
)

/** Instant notifications and the ongoing notification they need. */
data class NotificationSettings(
    val instant: Boolean,
    val onInstantChange: (Boolean) -> Unit,
    val onHideOngoing: () -> Unit,
)

/** "Keep all mail on this device": the switches and how far it has got. */
data class KeepAllSettings(
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    val wifiOnly: Boolean,
    val onWifiOnlyChange: (Boolean) -> Unit,
    val progress: KeepAllProgress?,
    val onRemove: () -> Unit,
)

/** What swiping a message does on touch screens. */
data class SwipeSettings(
    val right: SwipeAction,
    val onRightChange: (SwipeAction) -> Unit,
    val left: SwipeAction,
    val onLeftChange: (SwipeAction) -> Unit,
)

/** What the Smart filing section shows and does. */
data class FilingSettings(
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    val status: String,
    val onTrain: () -> Unit,
)

@Composable
fun SettingsDialog(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    filing: FilingSettings,
    sending: SendingSettings,
    swipes: SwipeSettings,
    keepAll: KeepAllSettings,
    notifications: NotificationSettings,
    accountEmail: String,
    onSignOut: () -> Unit,
    versionName: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SettingsContent(themeMode, onThemeModeChange, filing, sending, swipes, keepAll, notifications, accountEmail, onSignOut, versionName, onDismiss)
    }
}

@Composable
fun SettingsContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    filing: FilingSettings,
    sending: SendingSettings,
    swipes: SwipeSettings,
    keepAll: KeepAllSettings,
    notifications: NotificationSettings,
    accountEmail: String,
    onSignOut: () -> Unit,
    versionName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, KylieTheme.colors.borderStrong),
        modifier = modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            ) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TooltipIconButton(Icons.Outlined.Close, "Close (Esc)", onDismiss)
            }

            Section("Appearance") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(
                        Triple(ThemeMode.SYSTEM, "System", Icons.Outlined.BrightnessAuto),
                        Triple(ThemeMode.LIGHT, "Light", Icons.Outlined.LightMode),
                        Triple(ThemeMode.DARK, "Dark", Icons.Outlined.DarkMode),
                    )
                    options.forEachIndexed { index, (mode, label, icon) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size, MaterialTheme.shapes.small),
                            icon = { ModeIcon(icon, selected = themeMode == mode) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "System follows your device's light or dark setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp))

            Section("Notifications") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Instant notifications", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (notifications.instant) {
                                "New mail is announced within seconds. Android shows a small ongoing notification while this is on."
                            } else {
                                "New mail is checked every 15 minutes, with no ongoing notification."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = notifications.instant, onCheckedChange = notifications.onInstantChange)
                }
                if (notifications.instant) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "You can hide the ongoing notification and keep instant mail.",
                            style = MaterialTheme.typography.bodySmall,
                            color = KylieTheme.colors.textMuted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = notifications.onHideOngoing) { Text("Hide it") }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp))

            Section("Sending") {
                OutlinedTextField(
                    value = sending.name,
                    onValueChange = sending.onNameChange,
                    label = { Text("Your name") },
                    placeholder = { Text("Shown next to $accountEmail") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sending.signature,
                    onValueChange = sending.onSignatureChange,
                    label = { Text("Signature") },
                    placeholder = { Text("Plain text, added below new messages and replies") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Mail is sent as plain text, exactly as you type it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp))

            Section("Swipe actions") {
                SwipeChoice("Swipe right", swipes.right, swipes.onRightChange)
                SwipeChoice("Swipe left", swipes.left, swipes.onLeftChange)
                Text(
                    "On touch screens and narrower windows. Swiped messages can be undone like any other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp))

            Section("Offline mail") {
                KeepAllSection(keepAll)
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp))

            Section("Smart filing") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Suggest folders", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Learns where you file mail, on this device only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = filing.enabled, onCheckedChange = filing.onEnabledChange)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        filing.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = filing.onTrain, shape = MaterialTheme.shapes.small) { Text("Train…") }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp))

            Section("Account") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(accountEmail, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "iCloud Mail",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onSignOut, shape = MaterialTheme.shapes.small) { Text("Sign out") }
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 24.dp))

            Section("About") {
                Text("Kylie Mail $versionName", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Uses the Inter typeface, licensed under the SIL Open Font License 1.1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KylieTheme.colors.textMuted,
                )
            }
            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = KylieTheme.colors.textMuted)
        content()
    }
}

@Composable
private fun KeepAllSection(keepAll: KeepAllSettings) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Keep all mail on this device", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Downloads the text of every email (not attachments) so search finds everything, even offline. Nothing is marked read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = keepAll.enabled, onCheckedChange = keepAll.onEnabledChange)
    }
    val progress = keepAll.progress
    if (keepAll.enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Only on Wi-Fi", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = keepAll.wifiOnly, onCheckedChange = keepAll.onWifiOnlyChange)
        }
        if (progress != null && progress.total > 0) {
            val done = progress.searchable >= progress.total
            if (!done) {
                LinearProgressIndicator(
                    progress = { (progress.searchable.toFloat() / progress.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                when {
                    done -> "All ${count(progress.total)} emails are on this device · ${BodyParser.formatSize(progress.bytesOnDevice)}"
                    else -> "Searchable: ${count(progress.searchable)} of ${count(progress.total)} emails · " +
                        BodyParser.formatSize(progress.bytesOnDevice)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!done) {
                Text(
                    when {
                        progress.running -> "Downloading in the background…"
                        keepAll.wifiOnly -> "Continues on Wi-Fi when the battery isn't low."
                        else -> "Continues when there's a connection and the battery isn't low."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = KylieTheme.colors.textMuted,
                )
            }
        }
    } else if (progress != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Mail on this device: ${BodyParser.formatSize(progress.bytesOnDevice)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = keepAll.onRemove) { Text("Free up space") }
        }
    }
}

private fun count(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

@Composable
private fun SwipeChoice(label: String, value: SwipeAction, onChange: (SwipeAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }, shape = MaterialTheme.shapes.small) {
                Text(value.label)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SwipeAction.entries.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            open = false
                            onChange(action)
                        },
                        trailingIcon = if (action == value) ({ Icon(Icons.Outlined.Check, contentDescription = "Current") }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeIcon(icon: ImageVector, selected: Boolean) {
    Icon(
        icon,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}
