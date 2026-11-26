package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.StickerEntity
import com.example.data.model.StickerPackEntity

@Database(
    entities = [StickerPackEntity::class, StickerEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StickerDatabase : RoomDatabase() {
    abstract fun stickerDao(): StickerDao

    companion object {
        @Volatile
        private var instance: StickerDatabase? = null

        fun getInstance(context: Context): StickerDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StickerDatabase::class.java,
                    "whatstick.db"
                ).build().also { instance = it }
            }
        }
    }
}
