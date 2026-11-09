package com.kayloh20.kyliemail.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.kayloh20.kyliemail.data.db.Contact
import com.kayloh20.kyliemail.ui.mail.TooltipIconButton
import com.kayloh20.kyliemail.ui.theme.KylieTheme

@Composable
fun ComposeScreen(viewModel: ComposeViewModel, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val bodyFocus = remember { FocusRequester() }
    val toFocus = remember { FocusRequester() }
    var confirmDiscard by remember { mutableStateOf(false) }

    BackHandler { viewModel.close(onFinished) }

    LaunchedEffect(viewModel.loaded) {
        if (!viewModel.loaded) return@LaunchedEffect
        runCatching { if (viewModel.firstFocus == FirstFocus.TO) toFocus.requestFocus() else bodyFocus.requestFocus() }
    }

    Column(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed || event.isMetaPressed
                when {
                    ctrl && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> { viewModel.send(onFinished); true }
                    event.key == Key.Escape -> { viewModel.close(onFinished); true }
                    else -> false
                }
            },
    ) {
        // Toolbar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            TooltipIconButton(Icons.Outlined.Close, "Close and keep draft (Esc)", { viewModel.close(onFinished) })
            Text(viewModel.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TooltipIconButton(Icons.Outlined.Delete, "Discard draft", { confirmDiscard = true })
            Spacer(Modifier.width(8.dp))
            Button(onClick = { viewModel.send(onFinished) }, shape = MaterialTheme.shapes.small, enabled = viewModel.loaded) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send")
            }
        }

        viewModel.error?.let { Banner(it, error = true) }
        if (viewModel.missingAttachments) Banner("Attachments from the original message aren't included yet.", error = false)

        HeaderRow("From") {
            Text(viewModel.from, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AddressRow(
            label = "To",
            value = viewModel.to,
            onValueChange = { viewModel.to = it; viewModel.error = null },
            suggest = viewModel::contactsFor,
            focusRequester = toFocus,
            trailing = if (!viewModel.showCcBcc) ({
                TextButton(onClick = { viewModel.showCcBcc = true }) { Text("Cc Bcc") }
            }) else null,
        )
        if (viewModel.showCcBcc) {
            AddressRow("Cc", viewModel.cc, { viewModel.cc = it; viewModel.error = null }, viewModel::contactsFor)
            AddressRow("Bcc", viewModel.bcc, { viewModel.bcc = it; viewModel.error = null }, viewModel::contactsFor)
        }
        HeaderRow("Subject") {
            PlainField(viewModel.subject, { viewModel.subject = it }, capitalize = true)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            BasicTextField(
                value = viewModel.body,
                onValueChange = { viewModel.body = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp)
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .focusRequester(bodyFocus),
            )
        }
    }

    if (viewModel.confirmEmptySubject) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmEmptySubject = false },
            title = { Text("Send without a subject?") },
            confirmButton = { Button(onClick = { viewModel.send(onFinished, allowEmptySubject = true) }, shape = MaterialTheme.shapes.small) { Text("Send") } },
            dismissButton = { TextButton(onClick = { viewModel.confirmEmptySubject = false }) { Text("Add a subject") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this draft?") },
            text = { Text("It will be deleted, including any copy in your Drafts folder.") },
            confirmButton = {
                Button(onClick = { confirmDiscard = false; viewModel.discard(onFinished) }, shape = MaterialTheme.shapes.small) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun Banner(text: String, error: Boolean) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HeaderRow(label: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 24.dp, end = 12.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = KylieTheme.colors.textMuted, modifier = Modifier.widthIn(min = 64.dp))
            Box(Modifier.weight(1f)) { content() }
            trailing?.invoke()
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    capitalize: Boolean = false,
    email: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = if (email) KeyboardType.Email else KeyboardType.Text,
            autoCorrectEnabled = !email,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** An address field that suggests people you've had mail from as you type; Tab or Enter picks the first. */
@Composable
private fun AddressRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggest: suspend (String) -> List<Contact>,
    focusRequester: FocusRequester = remember { FocusRequester() },
    trailing: (@Composable () -> Unit)? = null,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<Contact>()) }
    var dismissedFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(field.text, focused) {
        suggestions = if (focused) suggest(field.text) else emptyList()
    }

    fun accept(contact: Contact) {
        val before = field.text.substringBeforeLast(',', "").let { if (it.isBlank()) "" else "$it, " }
        val entry = if (contact.name.isBlank() || contact.name == contact.address) contact.address else "${contact.name} <${contact.address}>"
        val updated = "$before$entry, "
        field = TextFieldValue(updated, TextRange(updated.length))
        onValueChange(updated)
        suggestions = emptyList()
    }

    val showing = suggestions.isNotEmpty() && dismissedFor != field.text

    HeaderRow(label, trailing) {
        Box {
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    dismissedFor = null
                    onValueChange(it.text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || !showing) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Tab, Key.Enter -> { accept(suggestions.first()); true }
                            Key.Escape -> { dismissedFor = field.text; true }
                            else -> false
                        }
                    },
            )
            DropdownMenu(
                expanded = showing,
                onDismissRequest = { dismissedFor = field.text },
                // Typing continues in the field while suggestions are shown.
                properties = PopupProperties(focusable = false),
            ) {
                suggestions.forEach { contact ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(contact.name.ifBlank { contact.address }, style = MaterialTheme.typography.bodyMedium)
                                if (contact.name.isNotBlank()) {
                                    Text(contact.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        onClick = { accept(contact) },
                    )
                }
            }
        }
    }
}
