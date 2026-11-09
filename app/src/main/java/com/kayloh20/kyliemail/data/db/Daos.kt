package com.kayloh20.kyliemail.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kayloh20.kyliemail.data.mail.FolderRole
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE selectable = 1 ORDER BY sortOrder, fullName COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE role = :role LIMIT 1")
    suspend fun getByRole(role: FolderRole): FolderEntity?

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("UPDATE folders SET unreadCount = :unread, totalCount = :total WHERE id = :id")
    suspend fun setCounts(id: Long, unread: Int, total: Int)

    @Query("UPDATE folders SET unreadCount = MAX(0, unreadCount + :delta) WHERE id = :id")
    suspend fun adjustUnread(id: Long, delta: Int)

    @Query("UPDATE folders SET highestUid = :uid WHERE fullName = :fullName AND highestUid > :uid")
    suspend fun lowerHighestUid(fullName: String, uid: Long)

    @Query("SELECT * FROM folders WHERE fullName = :fullName")
    suspend fun getByName(fullName: String): FolderEntity?

    @Query("DELETE FROM folders WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface MessageDao {
    @Query(
        """
        SELECT id, uid, subject, fromName, fromAddress, preview, receivedAt, isSeen, isFlagged, hasAttachments
        FROM messages WHERE folderId = :folderId ORDER BY receivedAt DESC, uid DESC
        """,
    )
    fun observeSummaries(folderId: Long): Flow<List<MessageSummary>>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observe(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT id FROM messages")
    suspend fun allIds(): List<Long>

    @Query("SELECT uid, isSeen, isFlagged, isAnswered FROM messages WHERE folderId = :folderId")
    suspend fun localFlags(folderId: Long): List<LocalFlags>

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND bodyLoaded = 0 ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun withoutBody(folderId: Long, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MessageEntity>

    @Query("UPDATE messages SET isSeen = :seen, isFlagged = :flagged, isAnswered = :answered WHERE folderId = :folderId AND uid = :uid")
    suspend fun updateFlags(folderId: Long, uid: Long, seen: Boolean, flagged: Boolean, answered: Boolean)

    @Query("UPDATE messages SET bodyText = :text, bodyHtml = :html, preview = :preview, bodyLoaded = 1 WHERE id = :id")
    suspend fun setBody(id: Long, text: String?, html: String?, preview: String)

    @Query("DELETE FROM messages WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun deleteUids(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun deleteAllInFolder(folderId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE messages SET isSeen = :seen WHERE id = :id")
    suspend fun setSeen(id: Long, seen: Boolean)

    @Query("UPDATE messages SET isFlagged = :flagged WHERE id = :id")
    suspend fun setFlagged(id: Long, flagged: Boolean)

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND isSeen = 0 AND uid > :afterUid ORDER BY uid DESC")
    suspend fun unreadAfter(folderId: Long, afterUid: Long): List<MessageEntity>

    @Query("SELECT id, isSeen FROM messages WHERE id IN (:ids)")
    suspend fun seenStates(ids: List<Long>): List<SeenState>
}

data class SeenState(val id: Long, val isSeen: Boolean)

@Dao
interface ActionDao {
    @Insert
    suspend fun insert(action: PendingAction): Long

    @Query("SELECT * FROM pending_actions WHERE notBefore <= :now ORDER BY id")
    suspend fun ready(now: Long): List<PendingAction>

    @Query("DELETE FROM pending_actions WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>): Int

    @Query("SELECT uid FROM pending_actions WHERE folderName = :folderName")
    suspend fun pendingUids(folderName: String): List<Long>

    @Query("DELETE FROM pending_actions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_actions SET attempts = attempts + 1 WHERE id = :id")
    suspend fun countAttempt(id: Long)
}
