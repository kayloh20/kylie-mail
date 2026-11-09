package com.kayloh20.kyliemail.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FolderEntity::class, MessageEntity::class, PendingAction::class, FilingExampleEntity::class, TrainedFolder::class, DraftEntity::class, AttachmentEntity::class, DraftAttachmentEntity::class],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7, spec = MailDatabase.ReloadBodies::class),
    ],
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun messages(): MessageDao
    abstract fun actions(): ActionDao
    abstract fun filing(): FilingDao
    abstract fun drafts(): DraftDao
    abstract fun attachments(): AttachmentDao

    /** Bodies cached before version 7 dropped text after Apple Mail's in-body attachments; read them again. */
    class ReloadBodies : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE messages SET bodyLoaded = 0, bodyText = NULL, bodyHtml = NULL")
        }
    }

    companion object {
        fun create(context: Context): MailDatabase =
            Room.databaseBuilder(context, MailDatabase::class.java, "mail.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Bodies cached before version 2 were saved without their inline images, so fetch them again. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE messages SET bodyLoaded = 0, bodyText = NULL, bodyHtml = NULL")
            }
        }
    }
}
