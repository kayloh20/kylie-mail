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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kayloh20.kyliemail.data.settings.ThemeMode
import com.kayloh20.kyliemail.ui.mail.TooltipIconButton
import com.kayloh20.kyliemail.ui.theme.KylieTheme

@Composable
fun SettingsDialog(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accountEmail: String,
    onSignOut: () -> Unit,
    versionName: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SettingsContent(themeMode, onThemeModeChange, accountEmail, onSignOut, versionName, onDismiss)
    }
}

@Composable
fun SettingsContent(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
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
private fun ModeIcon(icon: ImageVector, selected: Boolean) {
    Icon(
        icon,
        contentDescription = null,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
    )
}
