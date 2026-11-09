package com.kayloh20.kyliemail.ui.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kayloh20.kyliemail.data.db.AttachmentEntity
import com.kayloh20.kyliemail.data.mail.BodyParser
import com.kayloh20.kyliemail.ui.theme.KylieTheme
import java.io.File

/** Every file in the message: click to open, the download button to save a copy where you choose. */
@Composable
fun AttachmentStrip(
    attachments: List<AttachmentEntity>,
    busy: Set<Long>,
    onOpen: (AttachmentEntity) -> Unit,
    onSave: (AttachmentEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Column(modifier.padding(start = 28.dp, end = 16.dp, bottom = 12.dp)) {
        Text(
            if (attachments.size == 1) "1 attachment" else "${attachments.size} attachments",
            style = MaterialTheme.typography.labelMedium,
            color = KylieTheme.colors.textMuted,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            attachments.forEach { attachment ->
                AttachmentCard(attachment, attachment.id in busy, onOpen = { onOpen(attachment) }, onSave = { onSave(attachment) })
            }
        }
    }
}

@Composable
private fun AttachmentCard(attachment: AttachmentEntity, busy: Boolean, onOpen: () -> Unit, onSave: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.widthIn(max = 280.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onOpen).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp).weight(1f, fill = false),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(iconFor(attachment.mimeType), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        attachment.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(BodyParser.formatSize(attachment.size), style = MaterialTheme.typography.labelSmall, color = KylieTheme.colors.textMuted)
                }
            }
            TooltipIconButton(Icons.Outlined.Download, "Save as…", onSave)
        }
    }
}

fun iconFor(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Outlined.Image
    mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
    mimeType.startsWith("audio/") -> Icons.Outlined.AudioFile
    mimeType.startsWith("video/") -> Icons.Outlined.VideoFile
    mimeType.contains("zip") || mimeType.contains("compressed") -> Icons.Outlined.FolderZip
    mimeType.startsWith("text/") || mimeType.contains("document") || mimeType.contains("word") -> Icons.AutoMirrored.Outlined.Article
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

/** Opens a downloaded attachment in another app. Returns a problem to show, or null. */
fun openAttachmentFile(context: Context, file: File, mimeType: String): String? {
    // Installing apps from email is a classic attack; those have to be saved and opened deliberately.
    if (mimeType == "application/vnd.android.package-archive" || file.name.endsWith(".apk", ignoreCase = true)) {
        return "For your safety, app installers can't be opened from email. Save it first if you trust it."
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType.ifBlank { "application/octet-stream" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "No app on this device can open ${file.name}. Use Save as… instead."
    }
}

/** The system "save as" picker, with a suggested name and type. */
class SaveFileContract : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}
