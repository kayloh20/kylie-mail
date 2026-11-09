package com.kayloh20.kyliemail.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FolderEntity::class, MessageEntity::class, PendingAction::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun messages(): MessageDao
    abstract fun actions(): ActionDao

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
