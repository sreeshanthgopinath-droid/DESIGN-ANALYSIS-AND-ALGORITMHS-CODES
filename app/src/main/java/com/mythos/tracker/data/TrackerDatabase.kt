package com.mythos.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EventEntity::class], version = 1, exportSchema = false)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        @Volatile
        private var instance: TrackerDatabase? = null

        fun get(context: Context): TrackerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TrackerDatabase::class.java,
                    "mythos_tracker.db",
                ).build().also { instance = it }
            }
    }
}
