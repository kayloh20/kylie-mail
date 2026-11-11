package com.kayloh20.kyliemail.ui.mail

import com.kayloh20.kyliemail.data.settings.SwipeAction.ARCHIVE
import com.kayloh20.kyliemail.data.settings.SwipeAction.DELETE
import com.kayloh20.kyliemail.data.settings.SwipeAction.MOVE
import com.kayloh20.kyliemail.data.settings.SwipeAction.NONE
import com.kayloh20.kyliemail.data.settings.SwipeAction.READ
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeZonesTest {
    private val spark = SwipeConfig(right = READ, longRight = MOVE, left = DELETE, longLeft = MOVE)

    @Test fun shortAndLongInBothDirections() {
        assertEquals(NONE, SwipeZones.action(0.1f, spark))
        assertEquals(READ, SwipeZones.action(0.3f, spark))
        assertEquals(MOVE, SwipeZones.action(0.7f, spark))
        assertEquals(NONE, SwipeZones.action(-0.1f, spark))
        assertEquals(DELETE, SwipeZones.action(-0.4f, spark))
        assertEquals(MOVE, SwipeZones.action(-0.9f, spark))
    }

    @Test fun withoutALongActionTheShortOneCoversTheFarEnd() {
        val config = spark.copy(longRight = NONE)
        assertEquals(READ, SwipeZones.action(0.95f, config))
    }

    @Test fun withOnlyALongActionTheShortZoneDoesNothing() {
        val config = spark.copy(left = NONE, longLeft = ARCHIVE)
        assertEquals(NONE, SwipeZones.action(-0.4f, config))
        assertEquals(ARCHIVE, SwipeZones.action(-0.7f, config))
    }

    @Test fun aDirectionWithNothingSetIsOff() {
        val config = spark.copy(left = NONE, longLeft = NONE)
        assertEquals(false, config.leftEnabled)
        assertEquals(NONE, SwipeZones.action(-0.9f, config))
    }

    @Test fun zonesAreReportedForTheTick() {
        assertEquals(SwipeZone.SHORT, SwipeZones.zone(0.5f, spark))
        assertEquals(SwipeZone.LONG, SwipeZones.zone(0.6f, spark))
    }
}
