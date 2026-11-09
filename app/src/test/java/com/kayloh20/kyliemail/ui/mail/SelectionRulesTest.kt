package com.kayloh20.kyliemail.ui.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionRulesTest {
    private val order = listOf(10L, 20L, 30L, 40L, 50L)

    @Test fun plainClickSelectsOnlyThatMessage() {
        val result = SelectionRules.click(Selection(setOf(10L, 20L), 10, 20), order, 30, ctrl = false, shift = false)
        assertEquals(Selection.of(30), result)
    }

    @Test fun ctrlClickTogglesAndMovesTheAnchor() {
        val added = SelectionRules.click(Selection.of(10), order, 30, ctrl = true, shift = false)
        assertEquals(setOf(10L, 30L), added.ids)
        assertEquals(30L, added.anchor)
        val removed = SelectionRules.click(added, order, 10, ctrl = true, shift = false)
        assertEquals(setOf(30L), removed.ids)
    }

    @Test fun shiftClickSelectsTheRangeFromTheAnchorInEitherDirection() {
        assertEquals(setOf(20L, 30L, 40L), SelectionRules.click(Selection.of(20), order, 40, ctrl = false, shift = true).ids)
        assertEquals(setOf(20L, 30L, 40L), SelectionRules.click(Selection.of(40), order, 20, ctrl = false, shift = true).ids)
    }

    @Test fun shiftClickKeepsTheAnchorSoTheRangeCanBeAdjusted() {
        val first = SelectionRules.click(Selection.of(20), order, 50, ctrl = false, shift = true)
        val narrowed = SelectionRules.click(first, order, 30, ctrl = false, shift = true)
        assertEquals(setOf(20L, 30L), narrowed.ids)
    }

    @Test fun ctrlShiftClickAddsARangeToTheSelection() {
        val start = Selection(setOf(10L, 40L), anchor = 40, cursor = 40)
        assertEquals(setOf(10L, 40L, 50L), SelectionRules.click(start, order, 50, ctrl = true, shift = true).ids)
    }

    @Test fun arrowKeysMoveAndShiftArrowsExtend() {
        assertEquals(Selection.of(10), SelectionRules.move(Selection(), order, 1, extend = false))
        assertEquals(Selection.of(30), SelectionRules.move(Selection.of(20), order, 1, extend = false))
        assertEquals(Selection.of(50), SelectionRules.move(Selection.of(50), order, 1, extend = false))
        val extended = SelectionRules.move(SelectionRules.move(Selection.of(20), order, 1, extend = true), order, 1, extend = true)
        assertEquals(setOf(20L, 30L, 40L), extended.ids)
        assertEquals(40L, extended.cursor)
    }

    @Test fun afterRemovalSelectsTheNextMessageOrThePreviousAtTheEnd() {
        assertEquals(Selection.of(40), SelectionRules.afterRemoval(order, setOf(20L, 30L)))
        assertEquals(Selection.of(30), SelectionRules.afterRemoval(order, setOf(40L, 50L)))
        assertEquals(Selection(), SelectionRules.afterRemoval(order, order.toSet()))
    }

    @Test fun selectAllAndOrdering() {
        assertEquals(order.toSet(), SelectionRules.selectAll(Selection(), order).ids)
        assertEquals(listOf(10L, 40L), SelectionRules.ordered(Selection(setOf(40L, 10L)), order))
    }
}
