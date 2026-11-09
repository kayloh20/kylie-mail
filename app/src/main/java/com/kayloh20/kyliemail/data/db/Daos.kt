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

    @Query("SELECT uid, isSeen, isFlagged, isAnswered FROM messages WHERE folderId = :folderId")
    suspend fun localFlags(folderId: Long): List<LocalFlags>

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND bodyLoaded = 0 ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun withoutBody(folderId: Long, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET isSeen = :seen, isFlagged = :flagged, isAnswered = :answered WHERE folderId = :folderId AND uid = :uid")
    suspend fun updateFlags(folderId: Long, uid: Long, seen: Boolean, flagged: Boolean, answered: Boolean)

    @Query("UPDATE messages SET bodyText = :text, bodyHtml = :html, preview = :preview, bodyLoaded = 1 WHERE id = :id")
    suspend fun setBody(id: Long, text: String?, html: String?, preview: String)

    @Query("DELETE FROM messages WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun deleteUids(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun deleteAllInFolder(folderId: Long)
}
