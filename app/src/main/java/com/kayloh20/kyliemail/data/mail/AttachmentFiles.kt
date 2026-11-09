package com.kayloh20.kyliemail.data.mail

import java.io.File

/**
 * Where attachment files live on the device: downloads of received attachments (a cache Android may
 * clear) and copies of files attached to drafts (kept until the draft is sent or discarded).
 */
class AttachmentFiles(cacheDir: File, filesDir: File) {
    private val downloads = File(cacheDir, "attachments")
    private val drafts = File(filesDir, "draft-attachments")

    fun downloadFile(messageId: Long, path: String, filename: String): File =
        File(File(downloads, "$messageId/${safe(path)}"), safe(filename))

    fun draftFile(draftId: Long, filename: String): File {
        val dir = File(drafts, draftId.toString()).apply { mkdirs() }
        var candidate = File(dir, safe(filename))
        var n = 2
        // Two files with the same name can be attached; keep both.
        while (candidate.exists()) {
            val base = safe(filename).substringBeforeLast('.')
            val ext = safe(filename).substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    fun deleteDraftFiles(draftId: Long) {
        File(drafts, draftId.toString()).deleteRecursively()
    }

    fun clear() {
        downloads.deleteRecursively()
        drafts.deleteRecursively()
    }

    companion object {
        private val unsafe = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

        /** A file name that can't escape its folder or break the file system. */
        fun safe(name: String): String {
            val cleaned = unsafe.replace(name, "_").trim().trimStart('.').take(120)
            return cleaned.ifBlank { "attachment" }
        }
    }
}
