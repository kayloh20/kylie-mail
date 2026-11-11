package com.kayloh20.kyliemail.ui.mail

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.kayloh20.kyliemail.data.db.MessageSummary
import com.kayloh20.kyliemail.data.settings.SwipeAction
import com.kayloh20.kyliemail.data.sync.SyncState
import com.kayloh20.kyliemail.ui.theme.KylieMailTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Swiping to mark read changes the message, which recomposes the row. The action must still fire once
 * per swipe, not flip read and unread while the row springs back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class SwipeOnceTest {
    @get:Rule val compose = createComposeRule()

    private fun touch(onSwipe: (Long, SwipeAction) -> Unit) = TouchActions(
        swipeEnabled = true,
        swipes = SwipeConfig(right = SwipeAction.READ, longRight = SwipeAction.MOVE, left = SwipeAction.FLAG, longLeft = SwipeAction.NONE),
        onSwipe = onSwipe, onLongPress = {}, selecting = false, selectionCount = 0, canArchive = true,
        onClearSelection = {}, onArchive = {}, onDelete = {}, onMove = {}, onToggleRead = {}, onToggleFlag = {},
    )

    private fun runSwipe(swipe: androidx.compose.ui.test.TouchInjectionScope.() -> Unit): List<SwipeAction> {
        val fired = mutableListOf<SwipeAction>()
        compose.setContent {
            var message by remember {
                mutableStateOf(MessageSummary(1, 1, "Cheer practice moved", "Madison", "m@example.com", "Hey!", 0, false, false, false))
            }
            KylieMailTheme(darkTheme = false) {
                MessageListPane(
                    title = "Inbox",
                    messages = listOf(message),
                    selectedIds = emptySet(),
                    cursorId = null,
                    syncState = SyncState(),
                    listState = rememberLazyListState(),
                    onClick = { _, _, _ -> },
                    onContextMenu = {},
                    contextMenu = { _, _ -> },
                    onRefresh = {},
                    onSignOut = {},
                    onOpenFolders = {},
                    onOpenSettings = {},
                    onNewMessage = {},
                    touch = touch { _, action ->
                        fired += action
                        // What the app does: the message changes right away.
                        message = when (action) {
                            SwipeAction.READ -> message.copy(isSeen = !message.isSeen)
                            SwipeAction.FLAG -> message.copy(isFlagged = !message.isFlagged)
                            else -> message
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithText("Cheer practice moved").performTouchInput(swipe)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        return fired
    }

    @Test fun shortSwipeRightMarksReadExactlyOnce() {
        // About 40% of the row: past the short threshold, short of the long one.
        assertEquals(listOf(SwipeAction.READ), runSwipe { swipeRight(startX = left + 20f, endX = left + 20f + width * 0.4f) })
    }

    @Test fun longSwipeRightMovesExactlyOnce() {
        assertEquals(listOf(SwipeAction.MOVE), runSwipe { swipeRight() })
    }

    @Test fun aTinySwipeDoesNothing() {
        assertEquals(emptyList<SwipeAction>(), runSwipe { swipeRight(startX = left + 20f, endX = left + 20f + width * 0.1f) })
    }

    @Test fun withNoLongActionTheShortOneCoversTheFullSwipe() {
        assertEquals(listOf(SwipeAction.FLAG), runSwipe { swipeLeft() })
    }
}
