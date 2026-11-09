package com.kayloh20.kyliemail.data.mail

import java.net.URLDecoder
import java.security.MessageDigest

/** Prepares email HTML for the in-app viewer. Pure string work, so it can be unit tested. */
object EmailHtml {
    /** Fake origin the viewer serves inline (cid:) images from. Never reaches the network. */
    const val INLINE_HOST = "inline.kyliemail.invalid"
    const val BASE_URL = "https://$INLINE_HOST/"

    private val cidReference = Regex("(?i)cid:([^\"'()\\s>]+)")
    private val headOpen = Regex("(?i)<head\\b[^>]*>")
    private val htmlOpen = Regex("(?i)<html\\b[^>]*>")

    private const val VIEWER_HEAD = """<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style>html,body{margin:0;padding:0}body{padding:16px 24px;overflow-wrap:break-word;font-family:sans-serif}img{max-width:100%;height:auto}pre{white-space:pre-wrap}</style>"""

    /** Stable file-safe key for a Content-ID, ignoring angle brackets, case and URL encoding. */
    fun contentIdKey(contentId: String): String {
        val decoded = runCatching { URLDecoder.decode(contentId, "UTF-8") }.getOrDefault(contentId)
        val normalized = decoded.trim().removePrefix("<").removeSuffix(">").lowercase()
        val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Points cid: references at the viewer's inline image host. */
    fun rewriteCidReferences(html: String): String =
        cidReference.replace(html) { match -> BASE_URL + contentIdKey(match.groupValues[1]) }

    /** The full document handed to the viewer: inline images resolved and a small base style added. */
    fun prepareForDisplay(html: String): String {
        val rewritten = rewriteCidReferences(html)
        headOpen.find(rewritten)?.let { return rewritten.replaceRange(it.range.last + 1, it.range.last + 1, VIEWER_HEAD) }
        htmlOpen.find(rewritten)?.let {
            return rewritten.replaceRange(it.range.last + 1, it.range.last + 1, "<head>$VIEWER_HEAD</head>")
        }
        return "<!doctype html><html><head>$VIEWER_HEAD</head><body>$rewritten</body></html>"
    }

    fun plainTextToHtml(text: String): String = "<pre>${escape(text)}</pre>"

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** True for URLs that would leave the device. */
    fun isRemote(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = lower.substringAfter("://").substringBefore('/').substringBefore(':')
        return host != INLINE_HOST
    }
}
