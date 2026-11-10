package com.kayloh20.kyliemail.data.mail

import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.internet.ContentType
import javax.mail.internet.MimeUtility

/** A file in a message. [path] is its IMAP section number ("2", "1.3"), used to download it later. */
data class AttachmentInfo(
    val path: String,
    val filename: String,
    val mimeType: String,
    /** Approximate decoded size in bytes. */
    val size: Long,
    /** Shown inside the message text, as Apple Mail does, rather than only in the attachment list. */
    val inBody: Boolean,
)

/** An image the HTML refers to, saved with the body so it shows offline. */
class InlinePart(val contentId: String, val mimeType: String, val data: ByteArray)

data class ParsedBody(
    /** Plain text for previews and quoting in replies. */
    val text: String?,
    /** HTML to display, with every text part and in-body attachment in its original order. */
    val html: String?,
    val inlineImages: List<InlinePart>,
    val attachments: List<AttachmentInfo>,
)

/**
 * Reads a message's structure in order. Apple Mail places attachments where they were dropped and
 * splits the text around them (text, photo, more text), so every text part is kept, not just the first.
 */
object BodyParser {
    /** Positioned images larger than this show as a file card instead of being embedded. */
    private const val MAX_EMBED_BYTES = 10L * 1024 * 1024

    private sealed interface Segment {
        data class Html(val html: String) : Segment
        data class Text(val text: String) : Segment
        data class Image(val key: String) : Segment
        data class Card(val attachment: AttachmentInfo) : Segment
    }

    private class Walk(val preferHtml: Boolean) {
        val segments = mutableListOf<Segment>()
        val attachments = mutableListOf<AttachmentInfo>()
        val images = mutableListOf<InlinePart>()
    }

    fun parse(root: Part): ParsedBody {
        val rich = Walk(preferHtml = true).also { walk(root, "", it, inBody = true) }
        val plain = Walk(preferHtml = false).also { walk(root, "", it, inBody = true, collectFiles = false) }

        val hasHtml = rich.segments.any { it is Segment.Html }
        val onlyText = rich.segments.all { it is Segment.Text }
        val html = when {
            rich.segments.isEmpty() -> null
            onlyText && rich.segments.size == 1 -> null
            hasHtml || rich.segments.size > 1 -> compose(rich.segments)
            else -> null
        }
        val text = plain.segments.mapNotNull {
            when (it) {
                is Segment.Text -> it.text.trimEnd()
                is Segment.Html -> htmlToPlainText(it.html).trim()
                is Segment.Card -> "[${it.attachment.filename}]"
                is Segment.Image -> null
            }
        }.filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }

        return ParsedBody(text, html, rich.images, rich.attachments)
    }

    /**
     * Just the readable text, for search. Only text parts are read, so on the server this downloads
     * the text and skips attachments and images entirely.
     */
    fun searchText(root: Part): String? {
        val plain = Walk(preferHtml = false).also { walk(root, "", it, inBody = true, collectFiles = false) }
        return plain.segments.mapNotNull {
            when (it) {
                is Segment.Text -> it.text.trimEnd()
                is Segment.Html -> htmlToPlainText(it.html).trim()
                is Segment.Card -> "[${it.attachment.filename}]"
                is Segment.Image -> null
            }
        }.filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { null }
    }

    private fun walk(part: Part, path: String, w: Walk, inBody: Boolean, collectFiles: Boolean = true) {
        val type = mimeType(part)
        val disposition = runCatching { part.disposition }.getOrNull()?.lowercase(Locale.ROOT)
        val filename = filename(part)
        val isAttachment = disposition == Part.ATTACHMENT

        when {
            type.startsWith("multipart/") -> {
                val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return
                val children = (0 until multipart.count).map { i -> multipart.getBodyPart(i) to childPath(path, i) }
                when (type) {
                    "multipart/alternative" -> {
                        // Use the richest version the reader wants; plain text stays the fallback.
                        val chosen = if (w.preferHtml) {
                            children.lastOrNull { (child, _) -> contains(child, "text/html") } ?: children.lastOrNull()
                        } else {
                            children.firstOrNull { (child, _) -> contains(child, "text/plain") } ?: children.firstOrNull()
                        }
                        chosen?.let { (child, childPath) -> walk(child, childPath, w, inBody, collectFiles) }
                    }
                    "multipart/related" -> {
                        // The first part is the document; the rest are images it refers to by Content-ID.
                        children.forEachIndexed { index, (child, childPath) ->
                            val cid = contentId(child)
                            if (index > 0 && cid != null && mimeType(child).startsWith("image/")) {
                                if (collectFiles) readBytes(child, MAX_EMBED_BYTES)?.let { w.images += InlinePart(cid, mimeType(child), it) }
                            } else {
                                walk(child, childPath, w, inBody = index == 0, collectFiles = collectFiles)
                            }
                        }
                    }
                    else -> children.forEach { (child, childPath) -> walk(child, childPath, w, inBody, collectFiles) }
                }
            }
            !isAttachment && filename == null && type == "text/html" -> readText(part)?.let { w.segments += Segment.Html(it) }
            !isAttachment && filename == null && type == "text/plain" -> readText(part)?.let { w.segments += Segment.Text(it) }
            else -> {
                if (!collectFiles) {
                    if (inBody && disposition != Part.ATTACHMENT) w.segments += Segment.Card(info(part, path, type, filename, inBody = true))
                    return
                }
                // Apple Mail marks attachments placed in the text as inline; show those in place.
                val shownInBody = inBody && !isAttachment
                val attachment = info(part, path, type, filename, shownInBody)
                w.attachments += attachment
                if (!shownInBody) return
                if (type.startsWith("image/") && attachment.size <= MAX_EMBED_BYTES) {
                    val key = contentId(part) ?: "kylie-part-${attachment.path}"
                    val bytes = readBytes(part, MAX_EMBED_BYTES)
                    if (bytes != null) {
                        w.images += InlinePart(key, type, bytes)
                        w.segments += Segment.Image(key)
                        return
                    }
                }
                w.segments += Segment.Card(attachment)
            }
        }
    }

    /** Joins the parts into one document, keeping each HTML part's styles. */
    private fun compose(segments: List<Segment>): String = buildString {
        append("<!doctype html><html><head>")
        segments.filterIsInstance<Segment.Html>().forEach { segment ->
            styleBlock.findAll(segment.html).forEach { append(it.value) }
        }
        append("</head><body>")
        for (segment in segments) {
            when (segment) {
                is Segment.Html -> append("<div>").append(bodyOf(segment.html)).append("</div>")
                is Segment.Text -> append("<div style=\"white-space:pre-wrap\">").append(EmailHtml.escape(segment.text.trimEnd())).append("</div>")
                is Segment.Image -> append("<div><img src=\"cid:").append(segment.key).append("\" style=\"max-width:100%\"></div>")
                is Segment.Card -> append(card(segment.attachment))
            }
        }
        append("</body></html>")
    }

    private fun card(attachment: AttachmentInfo): String =
        "<div style=\"margin:12px 0\"><a href=\"${EmailHtml.BASE_URL}attachment/${attachment.path}\" " +
            "style=\"display:inline-block;padding:10px 14px;border:1px solid rgba(128,128,128,.35);border-radius:8px;" +
            "text-decoration:none;color:inherit;font-family:sans-serif\">📎 ${EmailHtml.escape(attachment.filename)} " +
            "<span style=\"opacity:.6\">· ${formatSize(attachment.size)}</span></a></div>"

    private val styleBlock = Regex("(?is)<style\\b.*?</style>")
    private val bodyContent = Regex("(?is)<body\\b[^>]*>(.*)</body>")
    private val documentWrapper = Regex("(?is)</?(html|head|body)\\b[^>]*>|<head\\b.*?</head>|<!doctype[^>]*>")

    private fun bodyOf(html: String): String =
        bodyContent.find(html)?.groupValues?.get(1) ?: documentWrapper.replace(html, "")

    private fun info(part: Part, path: String, type: String, filename: String?, inBody: Boolean): AttachmentInfo {
        val encoded = runCatching { part.size.toLong() }.getOrDefault(-1L).coerceAtLeast(0)
        val base64 = runCatching { part.getHeader("Content-Transfer-Encoding")?.firstOrNull() }.getOrNull()
            ?.trim()?.equals("base64", ignoreCase = true) == true
        return AttachmentInfo(
            path = path.ifEmpty { "1" },
            filename = filename ?: defaultName(type),
            mimeType = type,
            size = if (base64) encoded * 3 / 4 else encoded,
            inBody = inBody,
        )
    }

    private fun defaultName(type: String) = when {
        type == "message/rfc822" -> "Forwarded message.eml"
        type.startsWith("image/") -> "Image.${type.substringAfter('/').substringBefore('+')}"
        else -> "Attachment"
    }

    private fun childPath(parent: String, index: Int) = if (parent.isEmpty()) "${index + 1}" else "$parent.${index + 1}"

    private fun mimeType(part: Part): String = runCatching {
        ContentType(part.contentType).baseType.lowercase(Locale.ROOT)
    }.getOrDefault("application/octet-stream")

    private fun filename(part: Part): String? = runCatching { part.fileName }.getOrNull()
        ?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        ?.trim()?.takeIf { it.isNotEmpty() }

    private fun contentId(part: Part): String? =
        runCatching { part.getHeader("Content-ID")?.firstOrNull() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun contains(part: Part, type: String): Boolean {
        val own = mimeType(part)
        if (own == type) return true
        if (!own.startsWith("multipart/")) return false
        val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return false
        return (0 until multipart.count).any { contains(multipart.getBodyPart(it), type) }
    }

    private fun readBytes(part: Part, limit: Long): ByteArray? = runCatching {
        part.inputStream.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > limit) return null
            }
            out.toByteArray()
        }
    }.getOrNull()

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
