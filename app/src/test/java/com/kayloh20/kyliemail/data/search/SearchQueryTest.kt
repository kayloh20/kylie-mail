package com.kayloh20.kyliemail.data.search

import com.kayloh20.kyliemail.data.search.SearchQuery.Field
import com.kayloh20.kyliemail.data.search.SearchQuery.ServerTerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {
    @Test fun wordsMatchAsYouType() {
        val q = SearchQuery.parse("cheer Madi")
        assertEquals("cheer* madi*", q.fts)
        assertEquals(listOf(ServerTerm("cheer", null), ServerTerm("madi", null)), q.serverTerms)
    }

    @Test fun fieldFilters() {
        assertEquals("sender:madison* subject:practice*", SearchQuery.parse("from:Madison subject:practice").fts)
        assertEquals("recipients:coach*", SearchQuery.parse("to:coach").fts)
        assertEquals(listOf(ServerTerm("madison", Field.FROM)), SearchQuery.parse("from:madison").serverTerms)
    }

    @Test fun quotedPhrasesAreExact() {
        assertEquals("\"moved to 4pm\" cheer*", SearchQuery.parse("\"Moved to 4pm\" cheer").fts)
        assertEquals("subject:\"order shipped\"", SearchQuery.parse("subject:\"order shipped\"").fts)
        // An unfinished quote while typing still works.
        assertEquals("\"moved to\"", SearchQuery.parse("\"moved to").fts)
    }

    @Test fun punctuationCantBreakTheQuery() {
        assertEquals("o* brien*", SearchQuery.parse("O'Brien").fts)
        assertEquals("order* 48213*", SearchQuery.parse("order #48213").fts)
        // Words are lowercased, so OR, AND and NOT are never treated as search operators.
        assertEquals("a* or* b*", SearchQuery.parse("a* OR -b ( ) ^").fts)
        assertEquals("kylie* icloud* com*", SearchQuery.parse("kylie@icloud.com").fts)
    }

    @Test fun unknownPrefixesAreJustText() {
        assertEquals("re* hello*", SearchQuery.parse("re:hello").fts)
    }

    @Test fun nothingToSearch() {
        assertNull(SearchQuery.parse("   ").fts)
        assertNull(SearchQuery.parse("!!! ---").fts)
    }

    @Test fun accentedAndNonLatinWords() {
        assertEquals("café* 東京*", SearchQuery.parse("Café 東京").fts)
    }
}
