package com.kayloh20.kyliemail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import com.kayloh20.kyliemail.data.settings.ThemeMode
import com.kayloh20.kyliemail.ui.settings.SettingsContent
import com.kayloh20.kyliemail.ui.settings.FilingSettings
import com.kayloh20.kyliemail.ui.settings.SendingSettings
import com.kayloh20.kyliemail.ui.mail.WritingActions
import com.kayloh20.kyliemail.ui.mail.FileActions
import com.kayloh20.kyliemail.ui.mail.TouchActions
import com.kayloh20.kyliemail.ui.mail.SearchUi
import com.kayloh20.kyliemail.ui.mail.SearchState
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.kayloh20.kyliemail.ui.mail.RemoteImagesBanner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import com.kayloh20.kyliemail.ui.settings.SwipeSettings
import com.kayloh20.kyliemail.ui.settings.KeepAllSettings
import com.kayloh20.kyliemail.ui.settings.NotificationSettings
import com.kayloh20.kyliemail.data.offline.KeepAllProgress
import com.kayloh20.kyliemail.data.settings.SwipeAction
import androidx.compose.runtime.Composable
import com.kayloh20.kyliemail.data.db.AttachmentEntity
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
    @Config(qualifiers = "w1280dp-h1900dp-xhdpi")
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
                        sending = SendingSettings("Kylie", {}, "Kylie\nCheer captain 📣", {}),
                        swipes = SwipeSettings(SwipeAction.READ, {}, SwipeAction.DELETE, {}),
                        notifications = NotificationSettings(true, {}, {}),
                        keepAll = KeepAllSettings(
                            enabled = true,
                            onEnabledChange = {},
                            wifiOnly = true,
                            onWifiOnlyChange = {},
                            progress = KeepAllProgress(searchable = 12_340, total = 18_200, running = true, bytesOnDevice = 182L * 1024 * 1024),
                            onRemove = {},
                        ),
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

    private fun touch(selecting: Boolean, swipes: Boolean, count: Int = 0) = TouchActions(
        swipeEnabled = swipes,
        swipes = com.kayloh20.kyliemail.ui.mail.SwipeConfig(SwipeAction.READ, SwipeAction.MOVE, SwipeAction.DELETE, SwipeAction.MOVE),
        onSwipe = { _, _ -> },
        onLongPress = {},
        selecting = selecting,
        selectionCount = count,
        canArchive = true,
        onClearSelection = {},
        onArchive = {},
        onDelete = {},
        onMove = {},
        onToggleRead = {},
        onToggleFlag = {},
    )

    @Composable
    private fun phoneList(selecting: Boolean, modifier: Modifier) = MessageListPane(
        title = "Inbox",
        messages = messages,
        selectedIds = if (selecting) setOf(2L, 4L) else emptySet(),
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
        touch = touch(selecting = selecting, swipes = true, count = 2),
        modifier = modifier,
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phonePortrait() = captureRoboImage("build/screenshots/phone-portrait.png") {
        KylieMailTheme(darkTheme = false) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { phoneList(false, Modifier.fillMaxSize()) }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phonePickingMessages() = captureRoboImage("build/screenshots/phone-selecting.png") {
        KylieMailTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { phoneList(true, Modifier.fillMaxSize()) }
        }
    }

    /** Searching every folder on a phone, with results labelled by folder. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneSearch() = captureRoboImage("build/screenshots/phone-search.png") {
        KylieMailTheme(darkTheme = false) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                MessageListPane(
                    title = "Inbox",
                    messages = listOf(
                        summary(2, "Madison Clarke", "Cheer practice moved to 4pm", "Hey! Coach said the gym is booked so we're moving practice to 4.", 45, seen = true),
                        summary(7, "Coach Rivera", "Cheer competition schedule", "Buses leave at 6am sharp. Bring your uniform and the good snacks.", 60 * 48, seen = true),
                        summary(8, "Madison Clarke", "Re: Cheer fundraiser", "We raised \$1,240!! Thank you all so much.", 60 * 24 * 9, seen = true),
                    ).mapIndexed { i, m -> m.copy(folderId = listOf(1L, 10L, 4L)[i]) },
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
                    touch = touch(selecting = false, swipes = true),
                    search = SearchUi(
                        state = SearchState(text = "cheer"),
                        folderName = "Inbox",
                        resultCount = 3,
                        folderNames = folders.associate { it.id to it.displayName },
                        focusRequester = remember { FocusRequester() },
                        onOpen = {}, onTextChange = {}, onClose = {}, onAllFoldersChange = {},
                        onSearchServer = {}, onFocusChange = {}, onSubmit = {},
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** Reading on a phone: one toolbar row, a collapsed header, and the slim images banner. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun phoneReading() {
        val store = InlineImageStore(Files.createTempDirectory("inline").toFile())
        val newsletter = open.copy(
            subject = "Thirst for Adventure: November 11 @ 7:00PM",
            fromName = "Thirst for Adventure - The Goblin Gazette",
            fromAddress = "thirst-for-adventure-comedy@mail.beehiiv.com",
            toList = "kylie@icloud.com",
            isFlagged = false,
        )
        captureRoboImage("build/screenshots/phone-reading.png") {
            KylieMailTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize()) {
                        MessageDetailPane(
                            message = newsletter,
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
                            suggestions = listOf(FolderSuggestion(11, "Events", "Events", 0.8, "You've filed 9 emails from this sender here")),
                            filingTrained = true,
                            onFile = {},
                            onTrainFiling = {},
                            writing = WritingActions(false, {}, {}, {}, {}),
                            files = FileActions(emptyList(), emptySet(), {}, {}, {}),
                            onBack = {},
                            modifier = Modifier.fillMaxWidth().height(330.dp),
                        )
                        // The banner normally appears once the email tries to load a remote image.
                        RemoteImagesBanner("thirst@mail.beehiiv.com", compact = true, edge = 16.dp, onLoad = {}, onTrust = {})
                    }
                }
            }
        }
    }

    /** About the width of an S26 Ultra on its side: two panes, not three. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun phoneLandscape() {
        val store = InlineImageStore(Files.createTempDirectory("inline").toFile())
        captureRoboImage("build/screenshots/phone-landscape.png") {
            KylieMailTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxSize()) {
                        phoneList(false, Modifier.width(360.dp).fillMaxHeight())
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
                            writing = WritingActions(false, {}, {}, {}, {}),
                            files = FileActions(emptyList(), emptySet(), {}, {}, {}),
                            onBack = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
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
                            onNewMessage = {},
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
                            onNewMessage = {},
                            touch = touch(selecting = false, swipes = false),
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
                            writing = WritingActions(false, {}, {}, {}, {}),
                            files = FileActions(
                                attachments = listOf(
                                    AttachmentEntity(1, 2, "2", "Practice schedule.pdf", "application/pdf", 248_000, false),
                                    AttachmentEntity(2, 2, "3", "team-photo.jpg", "image/jpeg", 2_202_009, false),
                                ),
                                busy = setOf(2L),
                                onOpen = {},
                                onSave = {},
                                onOpenInBody = {},
                            ),
                            onBack = null,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}
