package com.phonecapture.tracker.capture.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Metadata only – images never go into the database. Enum columns are stored as their names
 * using Room's built-in enum support. The file lives in app-private storage, which Android
 * encrypts with file-based encryption; the images themselves are additionally AES-256-GCM encrypted.
 */
@Database(
    entities = [CaptureRecordEntity::class, DiagnosticLogEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun captureRecordDao(): CaptureRecordDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao

    companion object {
        /** Distinct name so the module can live inside another app (e.g. Mythos Tracker). */
        const val NAME = "phone_capture_tracker.db"

        fun build(context: Context): CaptureDatabase =
            Room.databaseBuilder(context.applicationContext, CaptureDatabase::class.java, NAME).build()

        fun inMemory(context: Context): CaptureDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, CaptureDatabase::class.java).build()
    }
}
