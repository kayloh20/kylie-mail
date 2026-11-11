package com.kayloh20.kyliemail.ui.mail

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.filing.FolderSuggestion
import com.kayloh20.kyliemail.ui.theme.KylieMailTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The move picker's suggestions load into space kept for them, without moving the folder list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi", application = Application::class)
@OptIn(ExperimentalTestApi::class)
class FolderPickerTest {
    @get:Rule val compose = createComposeRule()

    private fun folder(id: Long, name: String, role: FolderRole = FolderRole.CUSTOM) = FolderEntity(
        id = id, fullName = name, displayName = name, role = role, depth = 0, selectable = true, sortOrder = role.sortOrder,
    )

    private val folders = listOf(
        folder(1, "INBOX", FolderRole.INBOX),
        folder(4, "Archive", FolderRole.ARCHIVE),
        folder(7, "Receipts"),
        folder(8, "Travel"),
        folder(10, "Cheer Squad"),
    )
    private val ready = PickerSuggestions.Ready(
        listOf(
            FolderSuggestion(10, "Cheer Squad", "Cheer Squad", 0.71, "You've filed 23 emails from Madison here"),
            FolderSuggestion(8, "Travel", "Travel", 0.4, "Subject looks like Travel"),
        ),
    )

    private var suggestions by mutableStateOf<PickerSuggestions>(PickerSuggestions.Loading)
    private val picked = mutableListOf<Long>()

    private fun show() {
        compose.setContent {
            KylieMailTheme(darkTheme = false) {
                FolderPickerDialog(
                    title = "Move message to…",
                    folders = folders,
                    recentFullNames = emptyList(),
                    excludeId = 1,
                    onPick = { picked += it },
                    onDismiss = {},
                    suggestions = suggestions,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun top(text: String): Offset =
        compose.onNodeWithText(text).fetchSemanticsNode().positionInWindow

    private fun press(key: Key) {
        compose.onNode(hasSetTextAction()).requestFocus().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun pressEnter() = press(Key.Enter)

    @Test
    fun folderListStaysPutWhenSuggestionsArrive() {
        show()
        compose.onNodeWithContentDescription("Loading suggestions").assertExists()
        val before = top("All folders") to top("Receipts")

        suggestions = ready
        compose.waitForIdle()
        compose.onNodeWithText("You've filed 23 emails from Madison here").assertExists()
        assertEquals(before, top("All folders") to top("Receipts"))

        suggestions = PickerSuggestions.Ready(emptyList())
        compose.waitForIdle()
        compose.onNodeWithText("No suggestions for this one yet. Smart filing learns from every move.").assertExists()
        assertEquals(before, top("All folders") to top("Receipts"))
    }

    @Test
    fun enterWhileLoadingPicksTheBestSuggestionOnceItArrives() {
        show()
        pressEnter()
        compose.waitForIdle()
        assertTrue(picked.isEmpty())

        suggestions = ready
        compose.waitForIdle()
        assertEquals(listOf(10L), picked)
    }

    @Test
    fun enterWhileLoadingWithNoSuggestionsFilesNowhere() {
        show()
        pressEnter()
        suggestions = PickerSuggestions.Ready(emptyList())
        compose.waitForIdle()
        assertTrue(picked.isEmpty())
        // The highlight lands on the first folder, so the next Enter is a deliberate choice.
        pressEnter()
        compose.waitForIdle()
        assertEquals(listOf(4L), picked)
    }

    @Test
    fun arrowsWalkFromSuggestionsIntoFolders() {
        suggestions = ready
        show()
        press(Key.DirectionDown) // Travel, the second suggestion
        press(Key.DirectionDown) // Archive, first in the list
        pressEnter()
        compose.waitForIdle()
        assertEquals(listOf(4L), picked)
    }

    @Test
    fun typingHidesSuggestions() {
        suggestions = ready
        show()
        compose.onNodeWithText("Suggested").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("r")
        compose.waitForIdle()
        compose.onNodeWithText("Suggested").assertDoesNotExist()
    }
}
