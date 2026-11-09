package com.kayloh20.kyliemail.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Remembers which senders' remote images (which can track when you open mail) load without asking. */
class ImageTrust(context: Context) {
    private val prefs = context.getSharedPreferences("image_trust", Context.MODE_PRIVATE)
    private val _senders = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val senders: StateFlow<Set<String>> = _senders.asStateFlow()

    fun trust(address: String) {
        val normalized = address.trim().lowercase()
        if (normalized.isEmpty()) return
        val updated = _senders.value + normalized
        prefs.edit { putStringSet(KEY, updated) }
        _senders.value = updated
    }

    fun clear() {
        prefs.edit { clear() }
        _senders.value = emptySet()
    }

    private companion object {
        const val KEY = "senders"
    }
}
