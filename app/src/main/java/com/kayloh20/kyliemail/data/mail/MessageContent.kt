package com.kayloh20.kyliemail.data.mail

import java.io.InputStream
import javax.mail.Part

internal fun readText(part: Part): String? = when (val content = runCatching { part.content }.getOrNull()) {
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
