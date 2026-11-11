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

    /** Inserts or replaces the event with the same kind + sourceKey (used to update watch time). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY occurred_at_ms ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<EventEntity>

    @Query("UPDATE events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM events WHERE occurred_at_ms >= :start AND occurred_at_ms < :end ORDER BY occurred_at_ms ASC")
    suspend fun between(start: Long, end: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun page(afterId: Long, limit: Int): List<EventEntity>

    @Query("SELECT * FROM events WHERE kind IN ('diary', 'file', 'shared_note') ORDER BY occurred_at_ms DESC LIMIT 40")
    fun recentManual(): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM events WHERE synced = 0")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM events")
    fun totalCount(): Flow<Int>
}
