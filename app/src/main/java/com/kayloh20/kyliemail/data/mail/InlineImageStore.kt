package com.kayloh20.kyliemail.data.mail

import java.io.File

/** Saves images embedded in emails (cid: references) as files, one folder per message. */
class InlineImageStore(filesDir: File) {
    private val root = File(filesDir, "inline")

    fun save(messageId: Long, parts: List<InlinePart>) {
        if (parts.isEmpty()) return
        val dir = File(root, messageId.toString()).apply { mkdirs() }
        for (part in parts) {
            val name = EmailHtml.contentIdKey(part.contentId) + "__" + part.mimeType.replace('/', '_')
            File(dir, name).writeBytes(part.data)
        }
    }

    /** The image file and its MIME type for a key from [EmailHtml.contentIdKey], if saved. */
    fun find(messageId: Long, key: String): Pair<File, String>? {
        val dir = File(root, messageId.toString())
        val file = dir.listFiles()?.firstOrNull { it.name.startsWith(key + "__") } ?: return null
        return file to file.name.substringAfter("__").replaceFirst('_', '/')
    }

    /** Deletes saved images for messages that are no longer cached. */
    fun retainOnly(messageIds: Set<Long>) {
        root.listFiles()?.forEach { dir ->
            val id = dir.name.toLongOrNull()
            if (id == null || id !in messageIds) dir.deleteRecursively()
        }
    }

    fun clear() {
        root.deleteRecursively()
    }
}
