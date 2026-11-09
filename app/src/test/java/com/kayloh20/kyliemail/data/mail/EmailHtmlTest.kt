package com.kayloh20.kyliemail.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailHtmlTest {
    @Test fun contentIdKeyIgnoresBracketsCaseAndEncoding() {
        val key = EmailHtml.contentIdKey("<Logo@Example.com>")
        assertEquals(key, EmailHtml.contentIdKey("logo@example.com"))
        assertEquals(key, EmailHtml.contentIdKey("logo%40example.com"))
        assertNotEquals(key, EmailHtml.contentIdKey("other@example.com"))
        assertTrue(key.matches(Regex("[0-9a-f]{40}")))
    }

    @Test fun cidReferencesPointAtTheInlineHost() {
        val html = """<img src="cid:logo@example.com"><div style="background:url(cid:bg)">"""
        val rewritten = EmailHtml.rewriteCidReferences(html)
        assertTrue(rewritten.contains("""src="${EmailHtml.BASE_URL}${EmailHtml.contentIdKey("logo@example.com")}""""))
        assertTrue(rewritten.contains("url(${EmailHtml.BASE_URL}${EmailHtml.contentIdKey("bg")})"))
        assertFalse(rewritten.contains("cid:"))
    }

    @Test fun viewerStyleGoesIntoAnExistingHead() {
        val prepared = EmailHtml.prepareForDisplay("<html><head><title>x</title></head><body>Hi</body></html>")
        assertTrue(prepared.startsWith("<html><head><meta charset=\"utf-8\">"))
        assertTrue(prepared.contains("<title>x</title></head><body>Hi</body></html>"))
    }

    @Test fun fragmentsAreWrappedInADocument() {
        val prepared = EmailHtml.prepareForDisplay("<p>Hello</p>")
        assertTrue(prepared.startsWith("<!doctype html><html><head>"))
        assertTrue(prepared.endsWith("<body><p>Hello</p></body></html>"))
    }

    @Test fun headWithoutHtmlTagAndHtmlWithoutHeadAreHandled() {
        assertTrue(EmailHtml.prepareForDisplay("<html lang=\"en\"><body>a</body></html>").startsWith("<html lang=\"en\"><head><meta"))
    }

    @Test fun plainTextIsEscaped() {
        assertEquals("<pre>a &lt;b&gt; &amp; &quot;c&quot;</pre>", EmailHtml.plainTextToHtml("a <b> & \"c\""))
    }

    @Test fun onlyRealInternetUrlsCountAsRemote() {
        assertTrue(EmailHtml.isRemote("https://tracker.example.com/pixel.gif"))
        assertTrue(EmailHtml.isRemote("HTTP://example.com"))
        assertFalse(EmailHtml.isRemote(EmailHtml.BASE_URL + "abc"))
        assertFalse(EmailHtml.isRemote("data:image/png;base64,AAAA"))
        assertFalse(EmailHtml.isRemote("file:///etc/passwd"))
    }
}
