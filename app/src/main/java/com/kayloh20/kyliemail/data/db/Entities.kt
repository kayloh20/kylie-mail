package com.kayloh20.kyliemail.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
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
