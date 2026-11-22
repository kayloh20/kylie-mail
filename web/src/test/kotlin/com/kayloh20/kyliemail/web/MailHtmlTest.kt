package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.mail.InlinePart
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailHtmlTest {
    private val sneaky = MailHtml.clean(TestMail.SNEAKY_HTML, emptyList(), allowRemoteImages = false)
    private val html = sneaky.html.lowercase()

    @Test
    fun removesEverythingActive() {
        for (bad in listOf("<script", "alert(", "onload", "onclick", "<iframe", "<object", "<svg", "<form", "<input", "<button",
            "<meta http-equiv", "<base", "<link", "javascript:", "data:text/html", "expression(", "@import", "evil.example")) {
            assertFalse(bad in html, "still has $bad:\n${sneaky.html}")
        }
        assertTrue("huge deal!" in html && "table" in html && "old ie trick" in html, "keeps the content")
        assertTrue("color: red" in html, "keeps harmless styles")
    }

    @Test
    fun holdsBackRemoteImagesUntilAllowed() {
        assertFalse("tracker.example" in html, sneaky.html)
        assertEquals(4, sneaky.remoteImages, "pixel, banner, CSS background and table background")
        val allowed = MailHtml.clean(TestMail.SNEAKY_HTML, emptyList(), allowRemoteImages = true)
        assertTrue("https://tracker.example/open.gif?id=123" in allowed.html)
        assertTrue("https://tracker.example/table.png" in allowed.html)
        assertTrue("https://tracker.example/banner.png" in allowed.html, "http images are asked for over https")
        assertFalse("http://" in allowed.html)
        assertFalse("evil.example" in allowed.html, "allowing images allows nothing else")
    }

    @Test
    fun linksOpenInANewTabWithoutSayingWhereFrom() {
        assertTrue("""<a href="https://shop.example/deal" target="_blank" rel="noopener noreferrer">""" in sneaky.html, sneaky.html)
        assertTrue("""<meta name="referrer" content="no-referrer">""" in sneaky.html)
    }

    @Test
    fun embedsTheMessagesOwnImages() {
        val part = InlinePart("<logo@shop>", "image/png", TestMail.PNG)
        val safe = MailHtml.clean("""<img src="cid:logo@shop"><img src="cid:missing">""", listOf(part), allowRemoteImages = false)
        assertTrue("src=\"data:image/png;base64," in safe.html, safe.html)
        assertEquals(1, Regex("<img src=").findAll(safe.html).count(), "a missing image loses its source")
        assertEquals(0, safe.remoteImages)
    }

    @Test
    fun plainTextIsEscapedAndLinked() {
        val safe = MailHtml.plainText("<b>hi</b> see https://example.org/a?b=1&c=2. Bye")
        assertTrue("&lt;b&gt;hi&lt;/b&gt;" in safe.html, safe.html)
        assertTrue("""<a href="https://example.org/a?b=1&amp;c=2" target="_blank" rel="noopener noreferrer">""" in safe.html, safe.html)
        assertTrue("</a>. Bye" in safe.html)
    }

    @Test
    fun policyBlocksScriptsAndOutsideRequests() {
        val policy = MailHtml.contentSecurityPolicy(allowRemoteImages = false)
        assertTrue("default-src 'none'" in policy && "sandbox" in policy && "frame-ancestors 'self'" in policy)
        assertFalse("script-src" in policy || "allow-scripts" in policy || "allow-same-origin" in policy)
        assertFalse("https:" in policy)
        assertTrue("img-src data: https:" in MailHtml.contentSecurityPolicy(allowRemoteImages = true))
    }
}
