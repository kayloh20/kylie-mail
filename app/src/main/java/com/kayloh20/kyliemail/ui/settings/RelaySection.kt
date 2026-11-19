package com.kayloh20.kyliemail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kayloh20.kyliemail.data.relay.ConnectResult
import com.kayloh20.kyliemail.data.relay.RelayStatus
import com.kayloh20.kyliemail.ui.theme.KylieTheme
import kotlinx.coroutines.launch
import java.net.URI

/** The push relay's place in Settings. */
data class RelaySettings(
    val status: RelayStatus,
    val onConnect: suspend (server: String, setupCode: String) -> ConnectResult,
    val onDisconnect: () -> Unit,
)

@Composable
fun RelaySection(relay: RelaySettings, now: Long = System.currentTimeMillis()) {
    var settingUp by remember { mutableStateOf(false) }
    val status = relay.status
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when (status) {
                    RelayStatus.Off -> "Push relay"
                    else -> "Push relay: ${host(status)}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                when (status) {
                    RelayStatus.Off ->
                        "Your own server watches for new mail and wakes this device, so there's no ongoing notification. " +
                            "The server keeps your app-specific password, encrypted."
                    is RelayStatus.Connected ->
                        "Watching for new mail. Last heard from ${ago(now - status.lastOkAt)}."
                    is RelayStatus.PasswordRejected ->
                        "iCloud turned down the password the relay has. Sign out and in with a new app-specific password, then connect again."
                    is RelayStatus.Unreachable ->
                        "Can't reach it right now, so this device is watching for mail itself until it's back."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status is RelayStatus.PasswordRejected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status == RelayStatus.Off) {
            OutlinedButton(onClick = { settingUp = true }, shape = MaterialTheme.shapes.small) { Text("Set up") }
        } else {
            TextButton(onClick = relay.onDisconnect) { Text("Disconnect") }
        }
    }
    if (settingUp) {
        RelaySetupDialog(onConnect = relay.onConnect, onDone = { settingUp = false })
    }
}

@Composable
fun RelaySetupDialog(
    onConnect: suspend (String, String) -> ConnectResult,
    onDone: () -> Unit,
    initialError: String? = null,
) {
    var server by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(initialError) }
    val scope = rememberCoroutineScope()

    fun connect() {
        connecting = true
        error = null
        scope.launch {
            val result = onConnect(server, code)
            connecting = false
            error = when (result) {
                ConnectResult.Connected -> return@launch onDone()
                ConnectResult.BadAddress -> "That doesn't look like a server address. Try something like mail-push.example.org."
                ConnectResult.BadSetupCode -> "The relay didn't accept that setup code."
                ConnectResult.PasswordRejected -> "iCloud turned down your password when the relay tried it."
                ConnectResult.NotSignedIn -> "Sign in first."
                is ConnectResult.Failed -> result.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!connecting) onDone() },
        title = { Text("Connect a push relay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Your relay will keep your app-specific password, encrypted with a key only your devices hold, " +
                        "and wake this device through Google when mail arrives. It never sees what your mail says.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KylieTheme.colors.textMuted,
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server") },
                    placeholder = { Text("mail-push.example.org") },
                    singleLine = true,
                    enabled = !connecting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Setup code") },
                    singleLine = true,
                    enabled = !connecting,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = ::connect,
                enabled = !connecting && server.isNotBlank() && code.isNotBlank(),
                shape = MaterialTheme.shapes.small,
            ) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Connect")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !connecting) { Text("Cancel") } },
    )
}

private fun host(status: RelayStatus): String {
    val server = when (status) {
        is RelayStatus.Connected -> status.server
        is RelayStatus.PasswordRejected -> status.server
        is RelayStatus.Unreachable -> status.server
        RelayStatus.Off -> return ""
    }
    return runCatching { URI(server).host }.getOrNull() ?: server
}

private fun ago(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes == 1L -> "a minute ago"
        minutes < 60 -> "$minutes minutes ago"
        else -> "over an hour ago"
    }
}
