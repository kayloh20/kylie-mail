package com.kayloh20.kyliemail.ui.mail

/**
 * Which messages are selected. [anchor] is where a Shift range starts; [cursor] is where the
 * keyboard is. When exactly one message is selected it is the one shown in the reading pane.
 */
data class Selection(
    val ids: Set<Long> = emptySet(),
    val anchor: Long? = null,
    val cursor: Long? = null,
) {
    val single: Long? get() = ids.singleOrNull()

    companion object {
        fun of(id: Long) = Selection(setOf(id), id, id)
    }
}

/** Desktop-style selection rules, kept pure so they can be tested. [order] is the list as shown. */
object SelectionRules {
    fun click(current: Selection, order: List<Long>, id: Long, ctrl: Boolean, shift: Boolean): Selection {
        if (shift) {
            val from = current.anchor?.let(order::indexOf)?.takeIf { it >= 0 }
            val to = order.indexOf(id)
            if (from != null && to >= 0) {
                val range = order.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
                return Selection(if (ctrl) current.ids + range else range, current.anchor, id)
            }
        }
        if (ctrl) {
            val ids = if (id in current.ids) current.ids - id else current.ids + id
            return Selection(ids, anchor = id, cursor = id)
        }
        return Selection.of(id)
    }

    fun move(current: Selection, order: List<Long>, delta: Int, extend: Boolean): Selection {
        if (order.isEmpty()) return current
        val cursor = current.cursor?.let(order::indexOf)?.takeIf { it >= 0 }
        val nextIndex = if (cursor == null) 0 else (cursor + delta).coerceIn(0, order.lastIndex)
        val next = order[nextIndex]
        val anchor = current.anchor?.let(order::indexOf)?.takeIf { it >= 0 }
        if (!extend || anchor == null) return Selection.of(next)
        return Selection(order.subList(minOf(anchor, nextIndex), maxOf(anchor, nextIndex) + 1).toSet(), current.anchor, next)
    }

    fun selectAll(current: Selection, order: List<Long>): Selection =
        if (order.isEmpty()) Selection() else Selection(order.toSet(), order.first(), current.cursor ?: order.first())

    /** After removing messages, select the one that followed them, or the one before if they were last. */
    fun afterRemoval(order: List<Long>, removed: Set<Long>): Selection {
        val indices = order.withIndex().filter { it.value in removed }.map { it.index }
        if (indices.isEmpty()) return Selection()
        val after = order.drop(indices.max() + 1).firstOrNull { it !in removed }
        val before = order.take(indices.min()).lastOrNull { it !in removed }
        return (after ?: before)?.let(Selection::of) ?: Selection()
    }

    /** The selected ids in list order. */
    fun ordered(current: Selection, order: List<Long>): List<Long> {
        val inOrder = order.filter { it in current.ids }
        return inOrder + (current.ids - inOrder.toSet())
    }
}
