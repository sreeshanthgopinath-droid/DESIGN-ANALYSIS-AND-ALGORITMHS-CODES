package com.mythos.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    /** Duplicates (same kind + sourceKey) are silently ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<EventEntity>): List<Long>

    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY occurred_at_ms ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<EventEntity>

    @Query("UPDATE events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM events WHERE synced = 0")
    fun pendingCount(): Flow<Int>
}
