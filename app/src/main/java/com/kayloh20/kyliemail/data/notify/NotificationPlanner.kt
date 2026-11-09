package com.kayloh20.kyliemail.data.notify

/** Which inbox messages deserve a new-mail notification. Kept pure so it can be tested. */
object NotificationPlanner {
    data class Baseline(val uidValidity: Long, val highestUid: Long)

    data class Plan(val newBaseline: Baseline, val notifyUids: List<Long>)

    /**
     * Only mail that arrived after the last check is announced. The first check after sign-in, or
     * after iCloud rebuilds the inbox, just records where things stand, so old mail isn't announced.
     */
    fun plan(previous: Baseline?, uidValidity: Long, highestUid: Long, unreadUids: Collection<Long>): Plan {
        val baseline = Baseline(uidValidity, maxOf(highestUid, previous?.highestUid ?: 0))
        if (previous == null || previous.uidValidity != uidValidity) {
            return Plan(Baseline(uidValidity, highestUid), emptyList())
        }
        val fresh = unreadUids.filter { it > previous.highestUid }.sortedDescending()
        return Plan(baseline, fresh)
    }
}
