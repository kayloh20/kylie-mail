package com.kayloh20.kyliemail.data.search

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kayloh20.kyliemail.data.db.FolderEntity
import com.kayloh20.kyliemail.data.db.MailDatabase
import com.kayloh20.kyliemail.data.db.MessageEntity
import com.kayloh20.kyliemail.data.mail.FolderRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real SQLite full-text index, triggers included, against sample mail. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchIndexTest {
    private lateinit var db: MailDatabase
    private var inbox = 0L
    private var receipts = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MailDatabase::class.java)
            .addCallback(MailDatabase.searchTriggers)
            .allowMainThreadQueries()
            .build()
        inbox = db.folders().insert(FolderEntity(fullName = "INBOX", displayName = "Inbox", role = FolderRole.INBOX, depth = 0, selectable = true, sortOrder = 0))
        receipts = db.folders().insert(FolderEntity(fullName = "Receipts", displayName = "Receipts", role = FolderRole.CUSTOM, depth = 0, selectable = true, sortOrder = 100))
        db.messages().insertAll(
            listOf(
                message(1, inbox, "Madison Clarke", "madison@example.com", "Cheer practice moved to 4pm", received = 300),
                message(2, receipts, "Sephora", "orders@sephora.com", "Your order has shipped", received = 200),
                message(3, inbox, "Café Lumière", "hello@cafe.example", "Résumé of the menu", received = 100),
            ),
        )
    }

    @After fun tearDown() = db.close()

    private fun message(uid: Long, folder: Long, name: String, address: String, subject: String, received: Long) = MessageEntity(
        folderId = folder, uid = uid, messageIdHeader = null, subject = subject, fromName = name, fromAddress = address,
        toList = "Kylie <kylie@icloud.com>", ccList = "", sentAt = received, receivedAt = received,
        isSeen = false, isFlagged = false, isAnswered = false, hasAttachments = false,
    )

    private fun search(text: String, folder: Long? = null): List<String> = runBlocking {
        db.messages().search(SearchQuery.parse(text).fts!!, folder, 50).first().map { it.subject }
    }

    @Test fun newMailIsIndexedAsItArrives() {
        assertEquals(listOf("Cheer practice moved to 4pm"), search("cheer"))
        assertEquals(listOf("Your order has shipped"), search("sephora"))
    }

    @Test fun matchesAsYouTypeAcrossAllFolders() {
        assertEquals(listOf("Cheer practice moved to 4pm"), search("madi"))
        assertEquals(listOf("Your order has shipped"), search("ship"))
        assertEquals(3, search("kylie").size)
    }

    @Test fun filtersAndFolders() {
        assertEquals(listOf("Your order has shipped"), search("from:orders"))
        assertEquals(emptyList<String>(), search("subject:sephora"))
        assertEquals(listOf("Cheer practice moved to 4pm", "Résumé of the menu"), search("kylie", folder = inbox))
    }

    @Test fun accentsDontMatter() {
        assertEquals(listOf("Résumé of the menu"), search("resume"))
        assertEquals(listOf("Résumé of the menu"), search("cafe lumiere"))
    }

    @Test fun bodiesAreIndexedWhenTheyDownload() = runBlocking {
        assertEquals(emptyList<String>(), search("pyramid"))
        val id = db.messages().search(SearchQuery.parse("cheer").fts!!, null, 1).first().single().id
        db.messages().setBody(id, "Bring water, we're running the pyramid again.", null, "Bring water", false)
        assertEquals(listOf("Cheer practice moved to 4pm"), search("pyramid"))
        assertEquals(listOf("Cheer practice moved to 4pm"), search("\"running the pyramid\""))
    }

    @Test fun deletedMailLeavesTheIndex() = runBlocking {
        db.messages().deleteUids(receipts, listOf(2L))
        assertEquals(emptyList<String>(), search("sephora"))
        // Deleting a folder deletes its messages, and those leave the index too.
        db.folders().deleteByIds(listOf(inbox))
        assertEquals(emptyList<String>(), search("cheer"))
    }
}
