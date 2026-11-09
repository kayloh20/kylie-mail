package com.kayloh20.kyliemail.data.mail

import com.kayloh20.kyliemail.data.db.FolderEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSearchTest {
    private var nextId = 1L
    private fun folder(fullName: String, role: FolderRole = FolderRole.CUSTOM, selectable: Boolean = true) = FolderEntity(
        id = nextId++,
        fullName = fullName,
        displayName = FolderRole.displayName(role, fullName.substringAfterLast('/')),
        role = role,
        depth = fullName.count { it == '/' },
        selectable = selectable,
        sortOrder = role.sortOrder,
    )

    private val inbox = folder("INBOX", FolderRole.INBOX)
    private val archive = folder("Archive", FolderRole.ARCHIVE)
    private val receipts = folder("Receipts")
    private val workReceipts = folder("Work/Receipts")
    private val travel = folder("Travel Plans")
    private val recipes = folder("Recipes")
    private val hidden = folder("Hidden", selectable = false)
    private val all = listOf(inbox, archive, receipts, workReceipts, travel, recipes, hidden)

    private fun names(result: List<FolderEntity>) = result.map { it.fullName }

    @Test fun emptyQueryPutsRecentFirstThenFolderOrder() {
        val result = FolderSearch.rank(all, "", recentFullNames = listOf("Travel Plans", "Recipes"))
        assertEquals(listOf("Travel Plans", "Recipes", "INBOX", "Archive", "Receipts", "Work/Receipts"), names(result))
    }

    @Test fun prefixBeatsContainsAndShorterNamesWin() {
        assertEquals(listOf("Recipes", "Receipts", "Work/Receipts"), names(FolderSearch.rank(all, "rec")))
    }

    @Test fun wordStartsMatch() {
        assertEquals(listOf("Travel Plans"), names(FolderSearch.rank(all, "plans")))
    }

    @Test fun pathMatchesFindNestedFolders() {
        assertEquals(listOf("Work/Receipts"), names(FolderSearch.rank(all, "work")))
    }

    @Test fun lettersInOrderMatch() {
        assertEquals(listOf("Receipts", "Work/Receipts"), names(FolderSearch.rank(all, "rcpt")))
    }

    @Test fun recentBreaksTiesBetweenEqualMatches() {
        val result = FolderSearch.rank(all, "receipts", recentFullNames = listOf("Work/Receipts"))
        assertEquals(listOf("Work/Receipts", "Receipts"), names(result))
    }

    @Test fun excludedAndUnselectableFoldersAreLeftOut() {
        val result = FolderSearch.rank(all, "", excludeId = inbox.id)
        assertEquals(false, result.any { it.fullName == "INBOX" || it.fullName == "Hidden" })
    }

    @Test fun specialFoldersMatchByFriendlyName() {
        assertEquals(listOf("INBOX"), names(FolderSearch.rank(all, "inbox")))
    }
}
