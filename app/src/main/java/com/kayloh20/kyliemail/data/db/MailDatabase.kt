package com.kayloh20.kyliemail.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FolderEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MailDatabase : RoomDatabase() {
    abstract fun folders(): FolderDao
    abstract fun messages(): MessageDao

    companion object {
        fun create(context: Context): MailDatabase =
            Room.databaseBuilder(context, MailDatabase::class.java, "mail.db").build()
    }
}
