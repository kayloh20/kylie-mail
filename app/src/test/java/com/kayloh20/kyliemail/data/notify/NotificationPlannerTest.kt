package com.kayloh20.kyliemail.data.notify

import com.kayloh20.kyliemail.data.notify.NotificationPlanner.Baseline
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPlannerTest {
    @Test fun firstCheckRecordsTheInboxWithoutAnnouncingOldMail() {
        val plan = NotificationPlanner.plan(previous = null, uidValidity = 7, highestUid = 500, unreadUids = listOf(498L, 499L, 500L))
        assertEquals(emptyList<Long>(), plan.notifyUids)
        assertEquals(Baseline(7, 500), plan.newBaseline)
    }

    @Test fun onlyUnreadMailNewerThanTheLastCheckIsAnnounced() {
        val plan = NotificationPlanner.plan(Baseline(7, 500), 7, 503, unreadUids = listOf(499L, 501L, 503L))
        assertEquals(listOf(503L, 501L), plan.notifyUids)
        assertEquals(Baseline(7, 503), plan.newBaseline)
    }

    @Test fun rebuiltInboxResetsWithoutAnnouncing() {
        val plan = NotificationPlanner.plan(Baseline(7, 500), uidValidity = 8, highestUid = 20, unreadUids = listOf(19L, 20L))
        assertEquals(emptyList<Long>(), plan.notifyUids)
        assertEquals(Baseline(8, 20), plan.newBaseline)
    }

    @Test fun baselineNeverMovesBackwards() {
        // A message restored to the inbox after a failed move has an older UID; it shouldn't lower the mark.
        val plan = NotificationPlanner.plan(Baseline(7, 500), 7, 450, unreadUids = listOf(440L))
        assertEquals(emptyList<Long>(), plan.notifyUids)
        assertEquals(Baseline(7, 500), plan.newBaseline)
    }
}
