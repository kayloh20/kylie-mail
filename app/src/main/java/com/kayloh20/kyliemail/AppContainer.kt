package com.kayloh20.kyliemail

import android.content.Context
import com.kayloh20.kyliemail.data.MailActions
import com.kayloh20.kyliemail.data.MailRepository
import com.kayloh20.kyliemail.data.MoveHistory
import com.kayloh20.kyliemail.data.filing.FilingEngine
import com.kayloh20.kyliemail.data.compose.Outbox
import com.kayloh20.kyliemail.data.offline.KeepAllMail
import com.kayloh20.kyliemail.data.MailAttachments
import com.kayloh20.kyliemail.data.mail.AttachmentFiles
import com.kayloh20.kyliemail.data.filing.FolderSuggestion
import com.kayloh20.kyliemail.data.notify.MailNotifier
import com.kayloh20.kyliemail.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.kayloh20.kyliemail.data.account.CredentialStore
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.ImageTrust
import com.kayloh20.kyliemail.data.mail.ImapConnector
import com.kayloh20.kyliemail.data.mail.InlineImageStore
import com.kayloh20.kyliemail.data.sync.SyncEngine
import com.kayloh20.kyliemail.data.sync.SyncScheduler

/** Builds and holds the app's long-lived objects. */
class AppContainer(context: Context) {
    val database = MailDatabase.create(context)
    val credentials = CredentialStore(context)
    val settings = AppSettings(context)
    val connector = ImapConnector()
    val inlineImages = InlineImageStore(context.filesDir)
    val imageTrust = ImageTrust(context)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notifier: MailNotifier = MailNotifier(context, database) { messageId -> filingSuggestionFor(messageId) }
    val syncEngine = SyncEngine(database, credentials, connector, inlineImages).apply {
        inboxListener = notifier::onInboxSynced
    }
    val syncScheduler = SyncScheduler(context)
    val moveHistory = MoveHistory(context)
    val filing = FilingEngine(database, credentials, syncEngine, appScope)
    val actions = MailActions(database, syncEngine, appScope, moveHistory, filing, onMessageHandled = notifier::cancel)
    /** The confident suggestion for a new message, for its notification, when suggestions are on. */
    private suspend fun filingSuggestionFor(messageId: Long): FolderSuggestion? =
        if (!settings.filingSuggestions.value) null else filing.suggest(listOf(messageId), limit = 1).firstOrNull()?.takeIf { it.confident }

    val attachments = MailAttachments(database, syncEngine, AttachmentFiles(context.cacheDir, context.filesDir))
    val keepAllMail = KeepAllMail(context, database, syncEngine)

    val outbox = Outbox(context, database, credentials, settings, syncEngine, notifier, attachments, appScope)

    val repository = MailRepository(
        database, credentials, syncEngine, syncScheduler, inlineImages, imageTrust, actions, notifier, moveHistory, attachments.files,
    )
}
