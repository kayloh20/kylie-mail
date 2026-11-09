package com.kayloh20.kyliemail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.kayloh20.kyliemail.data.settings.ThemeMode
import com.kayloh20.kyliemail.ui.settings.SettingsContent
import com.kayloh20.kyliemail.ui.settings.FilingSettings
import com.kayloh20.kyliemail.ui.settings.TrainingContent
import com.kayloh20.kyliemail.data.filing.FolderSuggestion
import com.kayloh20.kyliemail.data.filing.TrainingState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.db.MessageSummary
import com.kayloh20.kyliemail.data.mail.FolderRole
import com.kayloh20.kyliemail.data.mail.InlineImageStore
import com.kayloh20.kyliemail.data.sync.SyncState
import com.kayloh20.kyliemail.ui.mail.FolderPane
import com.kayloh20.kyliemail.ui.mail.MessageDetailPane
import com.kayloh20.kyliemail.ui.mail.MessageListPane
import com.kayloh20.kyliemail.ui.mail.PaneDivider
import com.kayloh20.kyliemail.ui.theme.KylieMailTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

/** Renders the main screen with sample mail to build/screenshots, to check the look without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-xhdpi", application = android.app.Application::class)
class ScreenshotTest {
    private val now = System.currentTimeMillis()

    private fun folder(id: Long, name: String, role: FolderRole, unread: Int = 0, depth: Int = 0) = FolderEntity(
        id = id, fullName = name, displayName = FolderRole.displayName(role, name.substringAfterLast('/')),
        role = role, depth = depth, selectable = true, sortOrder = role.sortOrder, unreadCount = unread,
    )

    private val folders = listOf(
        folder(1, "INBOX", FolderRole.INBOX, unread = 3),
        folder(2, "Drafts", FolderRole.DRAFTS),
        folder(3, "Sent Messages", FolderRole.SENT),
        folder(4, "Archive", FolderRole.ARCHIVE),
        folder(5, "Junk", FolderRole.JUNK),
        folder(6, "Deleted Messages", FolderRole.TRASH),
        folder(7, "Receipts", FolderRole.CUSTOM, unread = 1),
        folder(8, "Travel", FolderRole.CUSTOM),
        folder(9, "Travel/Japan 2027", FolderRole.CUSTOM, depth = 1),
        folder(10, "Cheer Squad", FolderRole.CUSTOM),
    ).map { it.copy(totalCount = (it.id * 137 % 900).toInt() + 40) }

    private val suggestions = listOf(
        FolderSuggestion(10, "Cheer Squad", "Cheer Squad", 0.71, "You've filed 23 emails from Madison Clarke here"),
        FolderSuggestion(4, "Archive", "Archive", 0.38, "4 emails from Madison Clarke are here"),
    )

    private fun summary(id: Long, from: String, subject: String, preview: String, minutesAgo: Long, seen: Boolean, flagged: Boolean = false, attachments: Boolean = false) =
        MessageSummary(id, id, subject, from, "", preview, now - minutesAgo * 60_000, seen, flagged, attachments)

    private val messages = listOf(
        summary(1, "Sephora", "Your order has shipped ✨", "Good news! Your order #48213 is on its way and should arrive Thursday.", 12, seen = false),
        summary(2, "Madison Clarke", "Cheer practice moved to 4pm", "Hey! Coach said the gym is booked so we're moving practice to 4. Bring water!!", 45, seen = false, flagged = true),
        summary(3, "Apple", "Your receipt from Apple", "Receipt for iCloud+ with 200 GB storage. Billed to Visa ending 4421.", 180, seen = true, attachments = true),
        summary(4, "Japan Airlines", "Booking confirmed: SFO → HND", "Thank you for flying with JAL. Your confirmation code is QX7PLM.", 60 * 26, seen = true),
        summary(5, "GitHub", "[kylie-mail] Build passed", "The Build workflow run for main succeeded in 4m 12s.", 60 * 30, seen = false),
        summary(6, "Starbucks", "Holiday cups are back ☕", "Your favorite red cups return this week. Plus double Stars on Friday.", 60 * 50, seen = true),
    )

    private val open = MessageEntity(
        id = 2, folderId = 1, uid = 2, messageIdHeader = null,
        subject = "Cheer practice moved to 4pm", fromName = "Madison Clarke", fromAddress = "madison@example.com",
        toList = "Kylie <kylie@icloud.com>", ccList = "", sentAt = now - 45 * 60_000, receivedAt = now - 45 * 60_000,
        isSeen = true, isFlagged = true, isAnswered = false, hasAttachments = false,
        bodyText = "Hey!\n\nCoach said the gym is booked until 3:30, so we're moving practice to 4pm today. " +
            "Bring water and your new shoes, we're running the pyramid again.\n\nGo team!!\nMadison",
        bodyLoaded = true,
    )

    @Test
    fun mailScreenDark() = mailScreen("build/screenshots/mail-dark.png", dark = true)

    @Test
    fun mailScreenLight() = mailScreen("build/screenshots/mail-light.png", dark = false)

    @Test
    fun settingsDark() = settings("build/screenshots/settings-dark.png", dark = true)

    @Test
    fun settingsLight() = settings("build/screenshots/settings-light.png", dark = false)

    private fun settings(path: String, dark: Boolean) {
        captureRoboImage(path) {
            KylieMailTheme(darkTheme = dark) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    SettingsContent(
                        themeMode = if (dark) ThemeMode.DARK else ThemeMode.SYSTEM,
                        onThemeModeChange = {},
                        accountEmail = "kylie@icloud.com",
                        onSignOut = {},
                        versionName = "0.1.0",
                        onDismiss = {},
                        filing = FilingSettings(true, {}, "Learned from 2,340 emails in 9 folders, and keeps learning as you file.", {}),
                    )
                }
            }
        }
    }

    @Test
    fun trainingPickerDark() = training("build/screenshots/training-picker-dark.png", true, TrainingState.Idle)

    @Test
    fun trainingRunningLight() =
        training("build/screenshots/training-running-light.png", false, TrainingState.Running("Receipts", 3, 9, 1240))

    private fun training(path: String, dark: Boolean, state: TrainingState) {
        captureRoboImage(path) {
            KylieMailTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                    TrainingContent(
                        folders = folders,
                        trainedNames = setOf("Receipts", "Cheer Squad"),
                        state = state,
                        onStart = {},
                        onStop = {},
                        onClose = {},
                    )
                }
            }
        }
    }

    private fun mailScreen(path: String, dark: Boolean) {
        val store = InlineImageStore(Files.createTempDirectory("inline").toFile())
        captureRoboImage(path) {
            KylieMailTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxSize()) {
                        FolderPane(
                            folders = folders,
                            selectedFolderId = 1,
                            onSelect = {},
                            modifier = Modifier.width(248.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
                        )
                        VerticalDivider()
                        MessageListPane(
                            title = "Inbox",
                            messages = messages,
                            selectedIds = setOf(2L),
                            cursorId = 2,
                            syncState = SyncState(),
                            listState = rememberLazyListState(),
                            onClick = { _, _, _ -> },
                            onContextMenu = {},
                            contextMenu = { _, _ -> },
                            onRefresh = {},
                            onSignOut = {},
                            onOpenFolders = null,
                            onOpenSettings = {},
                            modifier = Modifier.width(400.dp).fillMaxHeight(),
                        )
                        PaneDivider(onDrag = {})
                        MessageDetailPane(
                            message = open,
                            selectionCount = 1,
                            bodyError = null,
                            remoteImagesAllowed = false,
                            inlineImages = store,
                            onRetryBody = {},
                            onLoadRemoteImages = {},
                            onTrustSender = {},
                            canArchive = true,
                            onArchive = {},
                            onDelete = {},
                            onToggleRead = {},
                            onToggleFlag = {},
                            onMove = {},
                            onClearSelection = {},
                            suggestions = suggestions,
                            filingTrained = true,
                            onFile = {},
                            onTrainFiling = {},
                            onBack = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}
