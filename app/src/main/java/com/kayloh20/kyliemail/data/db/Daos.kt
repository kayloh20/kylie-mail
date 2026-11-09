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
        SELECT id, uid, subject, fromName, fromAddress, preview, receivedAt, isSeen, isFlagged, hasAttachments, folderId
        FROM messages WHERE folderId = :folderId ORDER BY receivedAt DESC, uid DESC
        """,
    )
    fun observeSummaries(folderId: Long): Flow<List<MessageSummary>>

    /** Messages matching a full-text [query] (see SearchQuery), newest first, in one folder or all. */
    @Query(
        """
        SELECT m.id, m.uid, m.subject, m.fromName, m.fromAddress, m.preview, m.receivedAt, m.isSeen, m.isFlagged, m.hasAttachments, m.folderId
        FROM messages AS m JOIN message_search ON message_search.rowid = m.id
        WHERE message_search MATCH :query AND (:folderId IS NULL OR m.folderId = :folderId)
        ORDER BY m.receivedAt DESC LIMIT :limit
        """,
    )
    fun search(query: String, folderId: Long?, limit: Int): Flow<List<MessageSummary>>

    @Query(
        """
        SELECT id, uid, subject, fromName, fromAddress, preview, receivedAt, isSeen, isFlagged, hasAttachments, folderId
        FROM messages WHERE id IN (:ids)
        """,
    )
    fun observeSummariesByIds(ids: List<Long>): Flow<List<MessageSummary>>

    @Query("SELECT id FROM messages WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun idsForUids(folderId: Long, uids: List<Long>): List<Long>

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observe(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Query("SELECT id FROM messages")
    suspend fun allIds(): List<Long>

    /** The newest cached messages' flags. Older ones, such as search results, aren't re-checked every sync. */
    @Query("SELECT uid, isSeen, isFlagged, isAnswered FROM messages WHERE folderId = :folderId ORDER BY uid DESC LIMIT :limit")
    suspend fun localFlags(folderId: Long, limit: Int): List<LocalFlags>

    @Query("SELECT * FROM messages WHERE folderId = :folderId AND bodyLoaded = 0 ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun withoutBody(folderId: Long, limit: Int): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MessageEntity>

    @Query("UPDATE messages SET isSeen = :seen, isFlagged = :flagged, isAnswered = :answered WHERE folderId = :folderId AND uid = :uid")
    suspend fun updateFlags(folderId: Long, uid: Long, seen: Boolean, flagged: Boolean, answered: Boolean)

    @Query(
        "UPDATE messages SET bodyText = :text, bodyHtml = :html, preview = :preview, hasAttachments = :hasAttachments, bodyLoaded = 1 WHERE id = :id",
    )
    suspend fun setBody(id: Long, text: String?, html: String?, preview: String, hasAttachments: Boolean)

    @Query("DELETE FROM messages WHERE folderId = :folderId AND uid IN (:uids)")
    suspend fun deleteUids(folderId: Long, uids: List<Long>)

    @Query("DELETE FROM messages WHERE folderId = :folderId")
    suspend fun deleteAllInFolder(folderId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE messages SET isAnswered = 1 WHERE folderId = :folderId AND uid = :uid")
    suspend fun setAnswered(folderId: Long, uid: Long)

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
interface FilingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExamples(examples: List<FilingExampleEntity>): List<Long>

    @Query("SELECT * FROM filing_examples")
    suspend fun allExamples(): List<FilingExampleEntity>

    @Query("SELECT COUNT(*) FROM filing_examples")
    fun observeExampleCount(): Flow<Int>

    @Query("DELETE FROM filing_examples WHERE id IN (:ids)")
    suspend fun deleteExamples(ids: List<Long>)

    @Query("DELETE FROM filing_examples WHERE folderName = :folderName AND source = 'TRAINING'")
    suspend fun deleteTrainingExamples(folderName: String)

    @Query("SELECT * FROM trained_folders")
    suspend fun trainedFolders(): List<TrainedFolder>

    @Query("SELECT * FROM trained_folders")
    fun observeTrainedFolders(): Flow<List<TrainedFolder>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveTrainedFolder(folder: TrainedFolder)

    @Query("DELETE FROM trained_folders WHERE fullName = :fullName")
    suspend fun forgetTrainedFolder(fullName: String)
}

data class Contact(val name: String, val address: String, val count: Int)

@Dao
interface DraftDao {
    @Insert
    suspend fun insert(draft: DraftEntity): Long

    @Update
    suspend fun update(draft: DraftEntity)

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun get(id: Long): DraftEntity?

    @Query("SELECT * FROM drafts WHERE state IN ('SAVING', 'QUEUED', 'SENT', 'DISCARDING') AND sendAfter <= :now ORDER BY id")
    suspend fun ready(now: Long): List<DraftEntity>

    @Query("SELECT COUNT(*) FROM drafts WHERE state IN ('SAVING', 'QUEUED', 'SENT', 'DISCARDING')")
    suspend fun pendingCount(): Int

    /** Sends interrupted part-way, for example by the app being closed. */
    @Query("SELECT * FROM drafts WHERE state = 'SENDING' AND updatedAt < :before")
    suspend fun stuckSending(before: Long): List<DraftEntity>

    /** Moves a draft between states only if it is still in [from], so a send and an undo can't both win. */
    @Query("UPDATE drafts SET state = :to, updatedAt = :now WHERE id = :id AND state = :from")
    suspend fun transition(id: Long, from: DraftState, to: DraftState, now: Long): Int

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun delete(id: Long)

    /** People who have emailed this account, most frequent first, for address suggestions. */
    @Query(
        """
        SELECT fromName AS name, fromAddress AS address, COUNT(*) AS count FROM messages
        WHERE fromAddress != '' AND (fromAddress LIKE :pattern OR fromName LIKE :pattern)
        GROUP BY lower(fromAddress) ORDER BY count DESC LIMIT :limit
        """,
    )
    suspend fun contacts(pattern: String, limit: Int): List<Contact>
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY id")
    fun observeFor(messageId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId ORDER BY id")
    suspend fun forMessage(messageId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: Long): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE messageId = :messageId AND path = :path")
    suspend fun byPath(messageId: Long, path: String): AttachmentEntity?

    @Query("DELETE FROM attachments WHERE messageId = :messageId")
    suspend fun deleteFor(messageId: Long)

    @Insert
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM draft_attachments WHERE draftId = :draftId ORDER BY id")
    fun observeDraft(draftId: Long): Flow<List<DraftAttachmentEntity>>

    @Query("SELECT * FROM draft_attachments WHERE draftId = :draftId ORDER BY id")
    suspend fun forDraft(draftId: Long): List<DraftAttachmentEntity>

    @Insert
    suspend fun insertDraft(attachment: DraftAttachmentEntity): Long

    @Query("DELETE FROM draft_attachments WHERE id = :id")
    suspend fun deleteDraft(id: Long)
}

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
