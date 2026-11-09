package com.kayloh20.kyliemail.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageContentTest {
    @Test fun previewPrefersPlainText() {
        assertEquals("Hello there friend", makePreview("Hello\n\n  there   friend", "<p>ignored</p>"))
    }

    @Test fun previewFallsBackToHtmlAndDropsStylesAndTags() {
        val html = "<html><head><style>p { color: red; }</style></head><body><p>Your&nbsp;order</p><p>has shipped &amp; more</p></body></html>"
        assertEquals("Your order has shipped & more", makePreview(null, html))
    }

    @Test fun previewIsTrimmedToLength() {
        assertEquals("abcde", makePreview("abcdefghij", null, maxLength = 5))
    }

    @Test fun emptyBodiesGiveEmptyPreview() {
        assertEquals("", makePreview(null, null))
        assertEquals("", makePreview("   ", null))
    }
}
