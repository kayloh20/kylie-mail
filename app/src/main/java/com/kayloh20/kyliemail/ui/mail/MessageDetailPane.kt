package com.kayloh20.kyliemail.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.kayloh20.kyliemail.data.db.MessageEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailPane(
    message: MessageEntity?,
    bodyError: String?,
    onRetryBody: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (onBack != null) {
            TopAppBar(
                title = {},
                navigationIcon = { TooltipIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
                windowInsets = WindowInsets(0),
            )
        }

        if (message == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Outlined.MarkEmailUnread,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Select a message to read it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        val body = remember(message.bodyText, message.bodyHtml) { displayBody(message) }

        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SelectionContainer {
                Column(
                    modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(message.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.headlineSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append(message.fromName.ifBlank { message.fromAddress })
                                if (message.fromAddress.isNotBlank() && message.fromAddress != message.fromName) {
                                    append("  <").append(message.fromAddress).append(">")
                                }
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatFullDate(message.sentAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (message.toList.isNotBlank()) {
                        Text("To: ${message.toList}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (message.ccList.isNotBlank()) {
                        Text("Cc: ${message.ccList}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))

                    when {
                        message.bodyLoaded -> Text(
                            body.ifBlank { "(This message has no text.)" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        bodyError != null -> Column {
                            Text("Couldn't load this message: $bodyError", color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = onRetryBody) { Text("Try again") }
                        }
                        else -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

private val hiddenBlocks = Regex("(?is)<(style|script|head)\\b.*?</\\1>")

private fun displayBody(message: MessageEntity): String {
    message.bodyText?.takeIf { it.isNotBlank() }?.let { return it.trim() }
    val html = message.bodyHtml ?: return ""
    return HtmlCompat.fromHtml(hiddenBlocks.replace(html, ""), HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString()
        .replace('￼', ' ')
        .trim()
}
