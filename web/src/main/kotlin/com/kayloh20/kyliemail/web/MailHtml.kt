package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.mail.EmailHtml
import com.kayloh20.kyliemail.data.mail.InlinePart
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

/** An email made safe to show: the document, and whether it asks for images from the internet. */
data class SafeEmail(val html: String, val remoteImages: Int)

/**
 * Cleans email HTML before it's shown. This is the first of three locks: the message is also shown
 * in a sandboxed frame with scripts off, under a content security policy that blocks everything it
 * doesn't need (see [MailHtml.contentSecurityPolicy]). Here we remove anything active, make links
 * open in a new tab without telling the site where you came from, embed the message's own images,
 * and hold back images from the internet, which can tell the sender when you read it.
 */
object MailHtml {
    /** Elements removed with everything inside them. */
    private val removed = setOf(
        "script", "noscript", "iframe", "frame", "frameset", "object", "embed", "applet", "link", "meta", "base",
        "svg", "math", "template", "audio", "video", "source", "track", "portal", "dialog", "canvas", "map",
        "input", "button", "select", "textarea", "option", "datalist", "output", "progress", "meter",
    )

    /** Elements replaced by their contents. */
    private val unwrapped = setOf("form", "fieldset", "label")

    /** Attributes that can load something, run something or send something somewhere. */
    private val droppedAttributes = setOf(
        "srcset", "srcdoc", "formaction", "action", "ping", "poster", "lowsrc", "dynsrc", "longdesc", "usemap",
        "data", "codebase", "archive", "xmlns", "http-equiv", "target", "rel", "integrity", "crossorigin",
        "autofocus", "contenteditable", "draggable", "popover", "popovertarget",
    )

    private val safeLinkSchemes = listOf("http://", "https://", "mailto:", "tel:")
    /** A whole CSS url(...) that points at the internet, quoted or not. */
    private val remoteUrlInCss = Regex("(?i)url\\(\\s*(['\"]?)\\s*(https?:)?//[^)]*\\)")
    private val cssImports = Regex("(?i)@import[^;]*;?")
    private val cssExpressions = Regex("(?i)(expression\\s*\\(|behavior\\s*:|-moz-binding|javascript:)")

    /** Cleans [html]; [allowRemoteImages] lets images from the internet through. */
    fun clean(html: String, inline: List<InlinePart>, allowRemoteImages: Boolean): SafeEmail {
        val inlineByKey = inline.associateBy { EmailHtml.contentIdKey(it.contentId) }
        val doc = Jsoup.parse(html)
        var remote = 0

        doc.select(removed.joinToString(",")).remove()
        // Comments can hide conditional markup for old Outlook; nothing in them is shown anyway.
        doc.allElements.forEach { el -> el.childNodes().filter { it.nodeName() == "#comment" }.forEach { it.remove() } }

        for (el in doc.allElements.toList()) {
            val tag = el.tagName().lowercase()
            for (attr in el.attributes().asList().toList()) {
                val name = attr.key.lowercase()
                if (name.startsWith("on") || name in droppedAttributes || name.startsWith("xlink")) el.removeAttr(attr.key)
            }

            if (el.hasAttr("style")) {
                val style = el.attr("style")
                if (cssExpressions.containsMatchIn(style)) {
                    el.removeAttr("style")
                } else if (remoteUrlInCss.containsMatchIn(style)) {
                    remote++
                    if (!allowRemoteImages) el.attr("style", remoteUrlInCss.replace(style, "none"))
                }
            }

            if (el.hasAttr("background")) {
                val value = el.attr("background").trim()
                if (isRemote(value)) remote++
                if (!isRemote(value) || !allowRemoteImages) el.removeAttr("background")
            }

            when (tag) {
                "a", "area" -> cleanLink(el)
                "img" -> {
                    val src = el.attr("src").trim()
                    when {
                        src.startsWith("cid:", ignoreCase = true) -> {
                            val part = inlineByKey[EmailHtml.contentIdKey(src.substring(4))]
                            if (part != null) el.attr("src", dataUri(part)) else el.removeAttr("src")
                        }
                        src.startsWith(EmailHtml.BASE_URL) -> {
                            val part = inlineByKey[src.removePrefix(EmailHtml.BASE_URL)]
                            if (part != null) el.attr("src", dataUri(part)) else el.removeAttr("src")
                        }
                        isRemote(src) -> {
                            remote++
                            if (!allowRemoteImages) {
                                el.removeAttr("src")
                                el.attr("data-blocked", "")
                            } else {
                                // The frame only loads https images; most senders serve both.
                                el.attr("src", if (src.startsWith("//")) "https:$src" else "https://" + src.substringAfter("://"))
                            }
                        }
                        src.startsWith("data:image/", ignoreCase = true) -> Unit
                        else -> el.removeAttr("src")
                    }
                }
                "style" -> {
                    val css = el.data()
                    var cleaned = cssImports.replace(css, "")
                    if (cssExpressions.containsMatchIn(cleaned)) cleaned = cssExpressions.replace(cleaned, "x(")
                    if (remoteUrlInCss.containsMatchIn(cleaned)) {
                        remote++
                        if (!allowRemoteImages) cleaned = remoteUrlInCss.replace(cleaned, "none")
                    }
                    if (cleaned != css) {
                        el.empty()
                        el.appendChild(DataNode(cleaned))
                    }
                }
            }
        }
        doc.select(unwrapped.joinToString(",")).forEach { it.unwrap() }

        return SafeEmail(document(doc), remote)
    }

    /** A plain-text email as a document, with web addresses turned into links. */
    fun plainText(text: String): SafeEmail {
        val escaped = EmailHtml.escape(text)
        val linked = Regex("""https?://[^\s<>"']+""").replace(escaped) { match ->
            val url = match.value.trimEnd('.', ',', ')', ';', ':', '!', '?')
            val rest = match.value.substring(url.length)
            """<a href="$url" target="_blank" rel="noopener noreferrer">$url</a>$rest"""
        }
        val body = """<div class="plain">$linked</div>"""
        return SafeEmail(wrap("", body), 0)
    }

    private fun cleanLink(el: Element) {
        val href = el.attr("href").trim()
        when {
            href.isEmpty() -> el.removeAttr("href")
            href.startsWith("#") -> Unit
            href.startsWith(EmailHtml.BASE_URL) -> el.removeAttr("href") // attachment cards: download from the list instead
            safeLinkSchemes.any { href.startsWith(it, ignoreCase = true) } -> {
                el.attr("target", "_blank")
                el.attr("rel", "noopener noreferrer")
            }
            else -> el.removeAttr("href")
        }
    }

    private fun isRemote(url: String) =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) || url.startsWith("//")

    private fun dataUri(part: InlinePart): String {
        val type = part.mimeType.takeIf { it.startsWith("image/") && it != "image/svg+xml" } ?: "application/octet-stream"
        return "data:$type;base64," + Base64.getEncoder().encodeToString(part.data)
    }

    private fun document(doc: Document): String {
        val styles = doc.select("style").joinToString("") { it.outerHtml() }
        doc.select("style").remove()
        return wrap(styles, doc.body().html())
    }

    /** Emails are designed for a white page, so they always get one, even in dark mode. */
    private fun wrap(styles: String, body: String) =
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"light\">" +
            "<meta name=\"referrer\" content=\"no-referrer\"><style>$BASE_STYLE</style>$styles</head><body>$body</body></html>"

    private const val BASE_STYLE =
        "html{background:#fff;color:#1d1b20}body{margin:0;padding:16px 24px;overflow-wrap:break-word;" +
            "font:15px/1.5 -apple-system,system-ui,'Segoe UI',Roboto,sans-serif}img{max-width:100%;height:auto}" +
            "img[data-blocked]{display:none}pre,.plain{white-space:pre-wrap;font:inherit}" +
            "blockquote{margin:0 0 0 8px;padding-left:12px;border-left:3px solid #d0c8d6}"

    /**
     * The browser rules for an email: no scripts, nothing loaded but its own embedded images (and,
     * when allowed, images from the internet), no forms, framed only by Kylie Mail itself, and links
     * open in a new tab outside the sandbox.
     */
    fun contentSecurityPolicy(allowRemoteImages: Boolean): String =
        "default-src 'none'; img-src data:${if (allowRemoteImages) " https:" else ""}; style-src 'unsafe-inline'; " +
            "font-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'self'; " +
            "sandbox allow-popups allow-popups-to-escape-sandbox"
}
