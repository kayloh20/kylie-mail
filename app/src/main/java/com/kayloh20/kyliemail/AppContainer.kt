package com.kayloh20.kyliemail

import android.content.Context
import com.kayloh20.kyliemail.data.MailRepository
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
    val connector = ImapConnector()
    val inlineImages = InlineImageStore(context.filesDir)
    val imageTrust = ImageTrust(context)
    val syncEngine = SyncEngine(database, credentials, connector, inlineImages)
    val syncScheduler = SyncScheduler(context)
    val repository = MailRepository(database, credentials, syncEngine, syncScheduler, inlineImages, imageTrust)
}
