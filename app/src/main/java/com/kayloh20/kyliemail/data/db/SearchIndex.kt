package com.kayloh20.kyliemail.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** SQL triggers that keep [MessageSearchEntity] in step with the messages table. */
object SearchIndex {
    private val triggers = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS message_search_insert AFTER INSERT ON messages BEGIN
            INSERT INTO message_search(rowid, subject, sender, recipients, body)
            VALUES (new.id, new.subject, new.fromName || ' ' || new.fromAddress, new.toList || ' ' || new.ccList, coalesce(new.bodyText, ''));
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS message_search_body AFTER UPDATE OF bodyText ON messages BEGIN
            UPDATE message_search SET body = coalesce(new.bodyText, '') WHERE rowid = new.id;
        END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS message_search_delete AFTER DELETE ON messages BEGIN
            DELETE FROM message_search WHERE rowid = old.id;
        END
        """,
    )

    fun createTriggers(db: SupportSQLiteDatabase) {
        triggers.forEach { db.execSQL(it.trimIndent()) }
    }

    /** Indexes every message already cached, for databases created before search existed. */
    fun rebuild(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM message_search")
        db.execSQL(
            """
            INSERT INTO message_search(rowid, subject, sender, recipients, body)
            SELECT id, subject, fromName || ' ' || fromAddress, toList || ' ' || ccList, coalesce(bodyText, '') FROM messages
            """.trimIndent(),
        )
    }
}
