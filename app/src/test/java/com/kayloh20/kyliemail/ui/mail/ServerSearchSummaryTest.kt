package com.kayloh20.kyliemail.ui.mail

import com.kayloh20.kyliemail.data.sync.SkippedFolder
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSearchSummaryTest {
    private val refused = "iCloud couldn't run this search there"
    private fun skipped(vararg names: String, reason: String = refused) = names.map { SkippedFolder(it, reason) }

    @Test fun everythingSearched() {
        assertEquals("iCloud has nothing more for this search.", serverSearchSummary(0, emptyList(), 12))
        assertEquals("Included 1 match from iCloud.", serverSearchSummary(1, emptyList(), 12))
        assertEquals("Included 7 matches from iCloud.", serverSearchSummary(7, emptyList(), 12))
    }

    @Test fun someFoldersMissedAreNamedNeverHidden() {
        assertEquals(
            "Included 7 matches from iCloud, but couldn't search 2 folders: Receipts, Travel.",
            serverSearchSummary(7, skipped("Receipts", "Travel"), 12),
        )
        assertEquals(
            "No matches in the folders iCloud searched, but couldn't search 1 folder: Receipts.",
            serverSearchSummary(0, skipped("Receipts"), 12),
        )
        assertEquals(
            "Included 3 matches from iCloud, but couldn't search 5 folders: A, B, C and 2 more.",
            serverSearchSummary(3, skipped("A", "B", "C", "D", "E"), 12),
        )
    }

    @Test fun everyFolderMissedIsAFailureNotAnEmptyResult() {
        assertEquals("Couldn't search Inbox: $refused.", serverSearchSummary(0, skipped("Inbox"), 1))
        assertEquals("Couldn't search any folder: $refused.", serverSearchSummary(0, skipped("Inbox", "Receipts"), 2))
        assertEquals(
            "Couldn't search any folder on iCloud.",
            serverSearchSummary(0, listOf(SkippedFolder("Inbox", refused), SkippedFolder("Travel", "it no longer exists on iCloud")), 2),
        )
    }
}
