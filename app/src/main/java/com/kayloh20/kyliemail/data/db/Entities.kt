package com.kayloh20.kyliemail.data.db

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
)
