package com.kayloh20.kyliemail.data.mail

import java.io.InputStream
import javax.mail.Multipart
import javax.mail.Part

data class ExtractedBody(val text: String?, val html: String?, val inlineParts: List<InlinePart> = emptyList())

/** An image the HTML body refers to by Content-ID. */
class InlinePart(val contentId: String, val mimeType: String, val data: ByteArray)

private const val MAX_INLINE_BYTES = 15 * 1024 * 1024

/** Pulls the first plain-text and first HTML body out of a message, plus its inline images, skipping attachments. */
fun extractBody(root: Part): ExtractedBody {
    var text: String? = null
    var html: String? = null
    val inline = mutableListOf<InlinePart>()

    fun walk(part: Part) {
        val contentId = runCatching { part.getHeader("Content-ID")?.firstOrNull() }.getOrNull()
        if (contentId != null && runCatching { part.isMimeType("image/*") }.getOrDefault(false)) {
            readInline(part, contentId)?.let(inline::add)
            return
        }
        val disposition = runCatching { part.disposition }.getOrNull()
        if (Part.ATTACHMENT.equals(disposition, ignoreCase = true)) return
        when {
            part.isMimeType("text/plain") -> if (text == null) text = readText(part)
            part.isMimeType("text/html") -> if (html == null) html = readText(part)
            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return
                for (i in 0 until multipart.count) walk(multipart.getBodyPart(i))
            }
        }
    }

    walk(root)
    return ExtractedBody(text, html, inline)
}

private fun readInline(part: Part, contentId: String): InlinePart? = runCatching {
    val mimeType = part.contentType.substringBefore(';').trim().lowercase()
    val bytes = part.inputStream.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_INLINE_BYTES) return null
        }
        out.toByteArray()
    }
    InlinePart(contentId, mimeType, bytes)
}.getOrNull()

private fun readText(part: Part): String? = when (val content = runCatching { part.content }.getOrNull()) {
    is String -> content
    is InputStream -> content.use { it.readBytes().toString(Charsets.UTF_8) }
    else -> null
}

private val blockTags = Regex("(?is)<(style|script|head)\\b.*?</\\1>")
private val breakTags = Regex("(?i)<\\s*(br|/p|/div|/tr|/li|/h[1-6])\\b[^>]*>")
private val anyTag = Regex("(?s)<[^>]*>")
private val whitespace = Regex("\\s+")
private val entities = mapOf(
    "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
    "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&zwnj;" to "", "&#8203;" to "",
)

/** Rough HTML to text conversion, good enough for previews. */
fun htmlToPlainText(html: String): String {
    var s = blockTags.replace(html, " ")
    s = breakTags.replace(s, "\n")
    s = anyTag.replace(s, " ")
    for ((entity, value) in entities) s = s.replace(entity, value, ignoreCase = true)
    return s
}

fun makePreview(text: String?, html: String?, maxLength: Int = 200): String {
    val source = text?.takeIf { it.isNotBlank() } ?: html?.let(::htmlToPlainText) ?: return ""
    return whitespace.replace(source, " ").trim().take(maxLength)
}
