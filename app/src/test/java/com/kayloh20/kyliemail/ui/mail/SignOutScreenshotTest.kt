package com.kayloh20.kyliemail.ui.mail

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kayloh20.kyliemail.ui.theme.KylieMailTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sign out confirmation, to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
@OptIn(ExperimentalRoborazziApi::class)
class SignOutScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun signOutDark() {
        compose.setContent { KylieMailTheme(darkTheme = true) { SignOutDialog({}, {}) } }
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshots/sign-out-dark.png")
    }
}
