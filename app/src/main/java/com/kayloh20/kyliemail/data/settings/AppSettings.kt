package com.kayloh20.kyliemail.data.settings

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What swiping a message does on a touch screen. */
enum class SwipeAction(val label: String) {
    NONE("Nothing"),
    READ("Mark read or unread"),
    FLAG("Flag or unflag"),
    ARCHIVE("Archive"),
    MOVE("Move to…"),
    DELETE("Delete"),
}

/** Device preferences that survive sign-out. */
class AppSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME, null)?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: ThemeMode.SYSTEM,
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _filingSuggestions = MutableStateFlow(prefs.getBoolean(KEY_FILING, true))

    /** Whether smart filing suggests folders. */
    val filingSuggestions: StateFlow<Boolean> = _filingSuggestions.asStateFlow()

    fun setFilingSuggestions(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_FILING, enabled) }
        _filingSuggestions.value = enabled
    }

    private val _displayName = MutableStateFlow(prefs.getString(KEY_NAME, "").orEmpty())

    /** The name shown next to the address in sent mail. */
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    fun setDisplayName(name: String) {
        prefs.edit { putString(KEY_NAME, name) }
        _displayName.value = name
    }

    private val _signature = MutableStateFlow(prefs.getString(KEY_SIGNATURE, "").orEmpty())

    /** Plain text added below new messages and replies, after the standard "-- " separator. */
    val signature: StateFlow<String> = _signature.asStateFlow()

    fun setSignature(text: String) {
        prefs.edit { putString(KEY_SIGNATURE, text) }
        _signature.value = text
    }

    private fun swipe(key: String, default: SwipeAction) =
        prefs.getString(key, null)?.let { saved -> SwipeAction.entries.firstOrNull { it.name == saved } } ?: default

    // Apple Mail's defaults: swipe right to mark read or unread, swipe left to delete.
    private val _swipeRight = MutableStateFlow(swipe(KEY_SWIPE_RIGHT, SwipeAction.READ))
    val swipeRight: StateFlow<SwipeAction> = _swipeRight.asStateFlow()

    private val _swipeLeft = MutableStateFlow(swipe(KEY_SWIPE_LEFT, SwipeAction.DELETE))
    val swipeLeft: StateFlow<SwipeAction> = _swipeLeft.asStateFlow()

    fun setSwipeRight(action: SwipeAction) {
        prefs.edit { putString(KEY_SWIPE_RIGHT, action.name) }
        _swipeRight.value = action
    }

    fun setSwipeLeft(action: SwipeAction) {
        prefs.edit { putString(KEY_SWIPE_LEFT, action.name) }
        _swipeLeft.value = action
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
        applyThemeMode()
    }

    /**
     * Tells Android which mode the app uses (Android 12 and later), so the window, status bar icons
     * and email darkening all follow the choice, not only the app's own colors.
     */
    fun applyThemeMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = context.getSystemService(UiModeManager::class.java) ?: return
        val mode = when (_themeMode.value) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        manager.setApplicationNightMode(mode)
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_FILING = "filing_suggestions"
        const val KEY_NAME = "display_name"
        const val KEY_SIGNATURE = "signature"
        const val KEY_SWIPE_RIGHT = "swipe_right"
        const val KEY_SWIPE_LEFT = "swipe_left"
    }
}
