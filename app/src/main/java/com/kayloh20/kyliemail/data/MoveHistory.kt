package com.kayloh20.kyliemail.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The folders mail was most recently moved to, newest first, so the move picker can offer them. */
class MoveHistory(context: Context) {
    private val prefs = context.getSharedPreferences("move_history", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun record(folderFullName: String) {
        val updated = (listOf(folderFullName) + _recent.value.filter { it != folderFullName }).take(MAX)
        prefs.edit { putString(KEY, updated.joinToString(SEPARATOR)) }
        _recent.value = updated
    }

    fun clear() {
        prefs.edit { clear() }
        _recent.value = emptyList()
    }

    private fun load(): List<String> =
        prefs.getString(KEY, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    private companion object {
        const val KEY = "recent"
        const val SEPARATOR = "\u0000"
        const val MAX = 8
    }
}
