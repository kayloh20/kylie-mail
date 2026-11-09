package com.kayloh20.kyliemail.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kayloh20.kyliemail.data.mail.FolderRole

@Entity(
    tableName = "folders",
    indices = [Index(value = ["fullName"], unique = true)],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fullName: String,
    val displayName: String,
    val role: FolderRole,
    val depth: Int,
    val selectable: Boolean,
    val sortOrder: Int,
    val unreadCount: Int = 0,
    val totalCount: Int = 0,
    val uidValidity: Long = 0,
    val highestUid: Long = 0,
    val lastSyncedAt: Long = 0,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["folderId", "uid"], unique = true),
        Index(value = ["folderId", "receivedAt"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val uid: Long,
    val messageIdHeader: String?,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val toList: String,
    val ccList: String,
    val sentAt: Long,
    val receivedAt: Long,
    val isSeen: Boolean,
    val isFlagged: Boolean,
    val isAnswered: Boolean,
    val hasAttachments: Boolean,
    val preview: String = "",
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val bodyLoaded: Boolean = false,
)

/** The columns the message list needs, without the bodies. */
data class MessageSummary(
    val id: Long,
    val uid: Long,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val preview: String,
    val receivedAt: Long,
    val isSeen: Boolean,
    val isFlagged: Boolean,
    val hasAttachments: Boolean,
    val folderId: Long = 0,
)

data class LocalFlags(
    val uid: Long,
    val isSeen: Boolean,
    val isFlagged: Boolean,
    val isAnswered: Boolean,
)

enum class ActionType { MARK_READ, MARK_UNREAD, FLAG, UNFLAG, MOVE, DELETE_PERMANENTLY }

/**
 * A change made on the device that still has to reach the server. The local database already
 * shows the change; this row is replayed against iCloud until it succeeds.
 */
@Entity(tableName = "pending_actions")
data class PendingAction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ActionType,
    val folderName: String,
    val uidValidity: Long,
    val uid: Long,
    val targetFolderName: String? = null,
    val attempts: Int = 0,
    val createdAt: Long,
    /** Held back until this time so it can still be undone. */
    @ColumnInfo(defaultValue = "0") val notBefore: Long = 0,
)

enum class ExampleSource { TRAINING, MOVE }

/** One filed email the smart filing model learns from: headers only, never the body. */
@Entity(
    tableName = "filing_examples",
    indices = [Index(value = ["folderName", "uid"], unique = true), Index(value = ["folderName"])],
)
data class FilingExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderName: String,
    /** The server UID for examples from training; null for moves made in the app. */
    val uid: Long?,
    val fromAddress: String,
    val fromName: String,
    /** Recipient addresses, comma separated. */
    val recipients: String,
    val subject: String,
    val source: ExampleSource,
    val createdAt: Long,
)

/** A folder the user chose to train on, and how far training has read it. */
@Entity(tableName = "trained_folders")
data class TrainedFolder(
    @PrimaryKey val fullName: String,
    val uidValidity: Long,
    val highestUid: Long,
    val trainedAt: Long,
)

enum class DraftState {
    /** Open in a compose window. */
    EDITING,

    /** Closed with content: waiting to be saved to the iCloud Drafts folder. */
    SAVING,

    /** Sent with Send: waiting for the undo window to pass, or for a connection. */
    QUEUED,

    /** Being handed to the mail server right now. */
    SENDING,

    /** Accepted by the server: only the copy in Sent and the reply flag are left to do. Never resent. */
    SENT,

    /** The server refused it; the user needs to fix something. */
    FAILED,

    /** Discarded: the copy in iCloud Drafts it replaced still has to be deleted. */
    DISCARDING,
}

/** A message being written, from the first keystroke until it is sent or saved to iCloud Drafts. */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val state: DraftState = DraftState.EDITING,
    val toList: String = "",
    val ccList: String = "",
    val bccList: String = "",
    val subject: String = "",
    val body: String = "",
    /** Threading headers for replies. */
    val inReplyTo: String? = null,
    val references: String? = null,
    /** The message being replied to, to flag it as answered once sent. */
    val replyFolder: String? = null,
    val replyUid: Long? = null,
    /** A copy in the iCloud Drafts folder that this draft replaces. */
    val serverDraftFolder: String? = null,
    val serverDraftUid: Long? = null,
    /** True when forwarding a message whose attachments are not included. */
    val missingAttachments: Boolean = false,
    /** Set once the Message-ID is chosen, so a retried send is recognisable in Sent. */
    val messageId: String? = null,
    val sendAfter: Long = 0,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = 0,
)

/** A file in a received message. The content is downloaded only when opened or saved. */
@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["messageId"])],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    /** IMAP section number, such as "2" or "1.3". */
    val path: String,
    val filename: String,
    val mimeType: String,
    val size: Long,
    /** Shown inside the message text (Apple Mail style) as well as in the list. */
    val inBody: Boolean,
)

/** A file attached to a draft, copied into the app so it can still be sent if the original moves. */
@Entity(
    tableName = "draft_attachments",
    foreignKeys = [ForeignKey(entity = DraftEntity::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["draftId"])],
)
data class DraftAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val draftId: Long,
    val filename: String,
    val mimeType: String,
    val size: Long,
    /** Absolute path of the app's copy. */
    val path: String,
)

/**
 * The full-text search index: one row per cached message, keyed by the message's id. SQL triggers
 * (see [SearchIndex]) keep it in step with the messages table, so it can't drift.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=1"])
@Entity(tableName = "message_search")
data class MessageSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val subject: String,
    val sender: String,
    val recipients: String,
    val body: String,
)
