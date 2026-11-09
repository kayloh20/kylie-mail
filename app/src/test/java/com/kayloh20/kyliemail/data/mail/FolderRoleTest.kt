package com.kayloh20.kyliemail.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRoleTest {
    @Test fun inboxIsDetectedRegardlessOfCase() {
        assertEquals(FolderRole.INBOX, FolderRole.detect("INBOX", emptyList()))
        assertEquals(FolderRole.INBOX, FolderRole.detect("Inbox", emptyList()))
    }

    @Test fun specialUseAttributesWin() {
        assertEquals(FolderRole.SENT, FolderRole.detect("Outbox Copies", listOf("\\HasNoChildren", "\\Sent")))
        assertEquals(FolderRole.TRASH, FolderRole.detect("Bin", listOf("\\Trash")))
    }

    @Test fun icloudNamesAreRecognisedWithoutAttributes() {
        assertEquals(FolderRole.SENT, FolderRole.detect("Sent Messages", emptyList()))
        assertEquals(FolderRole.TRASH, FolderRole.detect("Deleted Messages", emptyList()))
        assertEquals(FolderRole.JUNK, FolderRole.detect("Junk", emptyList()))
        assertEquals(FolderRole.ARCHIVE, FolderRole.detect("Archive", emptyList()))
        assertEquals(FolderRole.DRAFTS, FolderRole.detect("Drafts", emptyList()))
    }

    @Test fun otherFoldersAreCustomAndKeepTheirName() {
        val role = FolderRole.detect("Receipts", emptyList())
        assertEquals(FolderRole.CUSTOM, role)
        assertEquals("Receipts", FolderRole.displayName(role, "Receipts"))
        assertEquals("Sent", FolderRole.displayName(FolderRole.SENT, "Sent Messages"))
    }
}
