package com.kayloh20.kyliemail.data.sync

import com.kayloh20.kyliemail.data.db.LocalFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPlannerTest {
    @Test fun initialRangeTakesTheNewestWindow() {
        assertEquals(801..1000, SyncPlanner.initialSequenceRange(1000, 200))
        assertEquals(1..50, SyncPlanner.initialSequenceRange(50, 200))
        assertNull(SyncPlanner.initialSequenceRange(0, 200))
    }

    @Test fun uidFetchWithNothingNewYieldsNoNewMessages() {
        // "101:*" with a highest UID of 100 makes the server return UID 100.
        assertEquals(emptyList<Long>(), SyncPlanner.newUids(listOf(100L), highestKnownUid = 100))
    }

    @Test fun newUidsAreSortedAndDeduplicated() {
        assertEquals(listOf(101L, 102L, 105L), SyncPlanner.newUids(listOf(105L, 101L, 99L, 102L, 101L), 100))
    }

    @Test fun vanishedFindsMessagesDeletedOnTheServer() {
        assertEquals(listOf(2L, 4L), SyncPlanner.vanished(listOf(1L, 2L, 3L, 4L), setOf(1L, 3L, 5L)))
    }

    @Test fun changedFlagsOnlyReportsDifferences() {
        val local = listOf(
            LocalFlags(uid = 1, isSeen = false, isFlagged = false, isAnswered = false),
            LocalFlags(uid = 2, isSeen = true, isFlagged = false, isAnswered = false),
            LocalFlags(uid = 3, isSeen = true, isFlagged = true, isAnswered = false),
        )
        val server = mapOf(
            1L to ServerFlags(isSeen = true, isFlagged = false, isAnswered = false),
            2L to ServerFlags(isSeen = true, isFlagged = false, isAnswered = false),
            // UID 3 is gone; deletion is handled separately.
        )
        assertEquals(mapOf(1L to ServerFlags(true, false, false)), SyncPlanner.changedFlags(local, server))
    }
}
