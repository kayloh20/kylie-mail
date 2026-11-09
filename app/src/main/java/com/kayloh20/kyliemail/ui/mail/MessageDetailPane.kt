package com.kayloh20.kyliemail.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.mail.InlineImageStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailPane(
    message: MessageEntity?,
    bodyError: String?,
    remoteImagesAllowed: Boolean,
    inlineImages: InlineImageStore,
    onRetryBody: () -> Unit,
    onLoadRemoteImages: () -> Unit,
    onTrustSender: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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
                    Icon(Icons.Outlined.MarkEmailUnread, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Select a message to read it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        var remoteBlocked by remember(message.id) { mutableStateOf(false) }
        val html = message.bodyHtml?.takeIf { it.isNotBlank() }

        MessageHeader(
            message = message,
            onPrint = if (message.bodyLoaded) {
                { MessagePrinter.print(context, message, remoteImagesAllowed, inlineImages) }
            } else {
                null
            },
        )
        HorizontalDivider()

        if (remoteBlocked && !remoteImagesAllowed) {
            RemoteImagesBanner(
                sender = message.fromAddress,
                onLoad = onLoadRemoteImages,
                onTrust = onTrustSender,
            )
        }

        when {
            message.bodyLoaded && html != null -> EmailWebView(
                messageId = message.id,
                html = html,
                allowRemote = remoteImagesAllowed,
                inlineImages = inlineImages,
                onRemoteBlocked = { remoteBlocked = true },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            message.bodyLoaded -> Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        message.bodyText?.trim().orEmpty().ifBlank { "(This message has no text.)" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                }
            }
            bodyError != null -> Column(Modifier.padding(28.dp)) {
                Text("Couldn't load this message: $bodyError", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetryBody) { Text("Try again") }
            }
            else -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun MessageHeader(message: MessageEntity, onPrint: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 16.dp, bottom = 12.dp)) {
        SelectionContainer(Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(message.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.headlineSmall)
                Text(senderLine(message), style = MaterialTheme.typography.titleSmall)
                Text(
                    formatFullDate(message.sentAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.toList.isNotBlank()) {
                    Text(
                        "To: ${message.toList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (message.ccList.isNotBlank()) {
                    Text(
                        "Cc: ${message.ccList}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (onPrint != null) TooltipIconButton(Icons.Outlined.Print, "Print or save as PDF", onPrint)
    }
}

@Composable
private fun RemoteImagesBanner(sender: String, onLoad: () -> Unit, onTrust: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.HideImage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Remote images are hidden so the sender can't tell you opened this.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            TextButton(onClick = onLoad) { Text("Load images") }
            if (sender.isNotBlank()) TextButton(onClick = onTrust) { Text("Always from sender") }
        }
    }
}
