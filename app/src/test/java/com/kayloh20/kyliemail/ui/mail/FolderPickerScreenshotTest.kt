package com.kayloh20.kyliemail.ui.mail

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.filing.FolderSuggestion
import com.kayloh20.kyliemail.ui.theme.KylieMailTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The move picker loading and loaded, to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi", application = Application::class)
@OptIn(ExperimentalRoborazziApi::class)
class FolderPickerScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun folder(id: Long, name: String, role: FolderRole = FolderRole.CUSTOM) = FolderEntity(
        id = id, fullName = name, displayName = name, role = role, depth = 0, selectable = true, sortOrder = role.sortOrder,
    )

    private val folders = listOf(
        folder(1, "INBOX", FolderRole.INBOX), folder(4, "Archive", FolderRole.ARCHIVE),
        folder(7, "Receipts"), folder(8, "Travel"), folder(10, "Cheer Squad"), folder(11, "Events"), folder(12, "School"),
    )

    private fun shot(path: String, dark: Boolean, suggestions: PickerSuggestions) {
        compose.setContent {
            KylieMailTheme(darkTheme = dark) {
                FolderPickerDialog("Move message to…", folders, listOf("Receipts"), 1, {}, {}, suggestions)
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage(path)
    }

    @Test
    fun loading() = shot("build/screenshots/move-picker-loading.png", dark = true, PickerSuggestions.Loading)

    @Test
    fun loadedDark() = shot(
        "build/screenshots/move-picker-dark.png", dark = true,
        PickerSuggestions.Ready(
            listOf(
                FolderSuggestion(10, "Cheer Squad", "Cheer Squad", 0.71, "You've filed 23 emails from Madison Clarke here"),
                FolderSuggestion(11, "Events", "Events", 0.42, "Subject looks like Events"),
                FolderSuggestion(4, "Archive", "Archive", 0.2, "4 emails from Madison Clarke are here"),
            ),
        ),
    )

    @Test
    fun loadedLight() = shot(
        "build/screenshots/move-picker-light.png", dark = false,
        PickerSuggestions.Ready(listOf(FolderSuggestion(10, "Cheer Squad", "Cheer Squad", 0.71, "You've filed 23 emails from Madison Clarke here"))),
    )
}
