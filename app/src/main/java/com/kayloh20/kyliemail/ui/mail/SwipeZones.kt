package com.kayloh20.kyliemail.ui.mail

import com.kayloh20.kyliemail.data.settings.SwipeAction

/** The four swipe actions: short and long, in each direction. */
data class SwipeConfig(
    val right: SwipeAction,
    val longRight: SwipeAction,
    val left: SwipeAction,
    val longLeft: SwipeAction,
) {
    val rightEnabled get() = right != SwipeAction.NONE || longRight != SwipeAction.NONE
    val leftEnabled get() = left != SwipeAction.NONE || longLeft != SwipeAction.NONE
}

/** Which zone a drag is in. Kept pure so the distances can be tested. */
enum class SwipeZone { NONE, SHORT, LONG }

object SwipeZones {
    /** Past this share of the row's width, letting go performs the short action. */
    const val SHORT = 0.22f

    /** Past this share, the long action. */
    const val LONG = 0.6f

    /**
     * The zone for a drag of [fraction] of the row's width (positive is right). With no long action,
     * the whole far end belongs to the short one, and vice versa.
     */
    fun zone(fraction: Float, config: SwipeConfig): SwipeZone {
        val distance = kotlin.math.abs(fraction)
        val (short, long) = if (fraction >= 0) config.right to config.longRight else config.left to config.longLeft
        return when {
            distance < SHORT -> SwipeZone.NONE
            distance >= LONG && long != SwipeAction.NONE -> SwipeZone.LONG
            short != SwipeAction.NONE -> SwipeZone.SHORT
            long != SwipeAction.NONE && distance >= LONG -> SwipeZone.LONG
            else -> SwipeZone.NONE
        }
    }

    /** What letting go at [fraction] would do. */
    fun action(fraction: Float, config: SwipeConfig): SwipeAction {
        val right = fraction >= 0
        return when (zone(fraction, config)) {
            SwipeZone.NONE -> SwipeAction.NONE
            SwipeZone.SHORT -> if (right) config.right else config.left
            SwipeZone.LONG -> if (right) config.longRight else config.longLeft
        }
    }
}
