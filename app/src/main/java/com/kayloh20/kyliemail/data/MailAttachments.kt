package com.kayloh20.kyliemail.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.kayloh20.kyliemail.data.db.DraftAttachmentEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.mail.AttachmentFiles
import com.kayloh20.kyliemail.data.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Downloading received attachments and attaching files to drafts. */
class MailAttachments(
    private val db: MailDatabase,
    private val syncEngine: SyncEngine,
    val files: AttachmentFiles,
) {
    private val dao = db.attachments()

    /** The attachment's file on the device, downloading it first if needed. */
    suspend fun file(attachmentId: Long): File = withContext(Dispatchers.IO) {
        val attachment = dao.get(attachmentId) ?: throw IOException("This attachment is gone.")
        val message = db.messages().getById(attachment.messageId) ?: throw IOException("This message is gone.")
        val folder = db.folders().getById(message.folderId) ?: throw IOException("This folder is gone.")
        val target = files.downloadFile(message.id, attachment.path, attachment.filename)
        if (!target.exists() || target.length() == 0L) syncEngine.downloadPart(folder.fullName, message.uid, attachment.path, target)
        target
    }

    /** The attachment at an in-body card's section number, for clicks inside the message. */
    suspend fun idForPath(messageId: Long, path: String): Long? = dao.byPath(messageId, path)?.id

    /** Copies every attachment of a message into a draft, for forwarding. Returns false if any couldn't be fetched. */
    suspend fun copyToDraft(messageId: Long, draftId: Long): Boolean {
        var complete = true
        for (attachment in dao.forMessage(messageId)) {
            val ok = runCatching {
                val source = file(attachment.id)
                val copy = files.draftFile(draftId, attachment.filename)
                withContext(Dispatchers.IO) { source.copyTo(copy, overwrite = true) }
                dao.insertDraft(DraftAttachmentEntity(draftId = draftId, filename = attachment.filename, mimeType = attachment.mimeType, size = copy.length(), path = copy.absolutePath))
            }.isSuccess
            complete = complete && ok
        }
        return complete
    }

    /** Copies a file the user picked or dropped into the draft. */
    suspend fun addToDraft(draftId: Long, uri: Uri, resolver: ContentResolver): DraftAttachmentEntity = withContext(Dispatchers.IO) {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val type = resolver.getType(uri) ?: "application/octet-stream"
        val copy = files.draftFile(draftId, name)
        resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("Couldn't read $name.")
        val entity = DraftAttachmentEntity(draftId = draftId, filename = name, mimeType = type, size = copy.length(), path = copy.absolutePath)
        entity.copy(id = dao.insertDraft(entity))
    }

    suspend fun removeFromDraft(attachment: DraftAttachmentEntity) {
        dao.deleteDraft(attachment.id)
        withContext(Dispatchers.IO) { File(attachment.path).delete() }
    }
}
