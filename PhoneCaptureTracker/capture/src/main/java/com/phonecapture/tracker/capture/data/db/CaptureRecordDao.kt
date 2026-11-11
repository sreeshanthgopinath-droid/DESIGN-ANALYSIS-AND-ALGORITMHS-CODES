package com.phonecapture.tracker.capture.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.phonecapture.tracker.core.model.CaptureCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureRecordDao {

    /** @return the row id, or -1 when a record for the same slot and category already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: CaptureRecordEntity): Long

    @Update
    suspend fun update(record: CaptureRecordEntity)

    @Query("SELECT * FROM capture_records WHERE capture_id = :captureId")
    suspend fun get(captureId: String): CaptureRecordEntity?

    @Query("SELECT * FROM capture_records WHERE capture_id = :captureId")
    fun observe(captureId: String): Flow<CaptureRecordEntity?>

    @Query("SELECT EXISTS(SELECT 1 FROM capture_records WHERE slot_index = :slot)")
    suspend fun isSlotRecorded(slot: Long): Boolean

    @Query("SELECT MAX(slot_index) FROM capture_records")
    suspend fun latestSlot(): Long?

    @Query(
        "SELECT * FROM capture_records WHERE captured_at_utc >= :startUtc AND captured_at_utc < :endUtc " +
            "ORDER BY captured_at_utc ASC",
    )
    fun observeBetween(startUtc: Long, endUtc: Long): Flow<List<CaptureRecordEntity>>

    @Query(
        "SELECT * FROM capture_records WHERE captured_at_utc >= :startUtc AND captured_at_utc < :endUtc " +
            "ORDER BY captured_at_utc ASC",
    )
    suspend fun between(startUtc: Long, endUtc: Long): List<CaptureRecordEntity>

    @Query(
        "SELECT * FROM capture_records WHERE category = :category AND capture_status = 'SUCCESS' " +
            "AND captured_at_utc >= :startUtc AND captured_at_utc < :endUtc " +
            "AND (thumbnail_path IS NOT NULL OR (cloud_path IS NOT NULL AND cloud_deleted = 0)) " +
            "ORDER BY captured_at_utc DESC",
    )
    fun observeGallery(category: CaptureCategory, startUtc: Long, endUtc: Long): Flow<List<CaptureRecordEntity>>

    @Query(
        "SELECT captured_at_utc FROM capture_records WHERE category = :category AND capture_status = 'SUCCESS' " +
            "AND captured_at_utc >= :startUtc AND captured_at_utc < :endUtc",
    )
    fun observeSuccessTimes(category: CaptureCategory, startUtc: Long, endUtc: Long): Flow<List<Long>>

    @Query("SELECT * FROM capture_records ORDER BY captured_at_utc DESC, category ASC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CaptureRecordEntity>>

    @Query(
        "SELECT * FROM capture_records WHERE upload_status = 'UPLOAD_PENDING' AND local_path IS NOT NULL " +
            "AND local_image_deleted = 0 ORDER BY captured_at_utc ASC LIMIT :limit",
    )
    suspend fun pendingUploads(limit: Int): List<CaptureRecordEntity>

    @Query("SELECT COUNT(*) FROM capture_records WHERE upload_status = 'UPLOAD_PENDING'")
    fun observePendingUploadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM capture_records WHERE upload_status = 'UPLOAD_FAILED'")
    fun observeFailedUploadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM capture_records WHERE upload_status = 'UPLOAD_PENDING'")
    suspend fun pendingUploadCount(): Int

    @Query(
        "UPDATE capture_records SET upload_status = 'UPLOAD_PENDING', upload_attempts = 0, upload_error = NULL " +
            "WHERE upload_status = 'UPLOAD_FAILED' AND local_path IS NOT NULL AND local_image_deleted = 0",
    )
    suspend fun resetFailedUploads(): Int

    /** Queues earlier local-only captures once the user turns cloud sync on. */
    @Query(
        "UPDATE capture_records SET upload_status = 'UPLOAD_PENDING' WHERE upload_status = 'NOT_QUEUED' " +
            "AND capture_status = 'SUCCESS' AND local_path IS NOT NULL AND local_image_deleted = 0",
    )
    suspend fun queueLocalOnlyCaptures(): Int

    @Query("UPDATE capture_records SET upload_progress = :progress WHERE capture_id = :captureId")
    suspend fun setUploadProgress(captureId: String, progress: Int)

    @Query("SELECT * FROM capture_records WHERE capture_status = 'SUCCESS' ORDER BY captured_at_utc DESC LIMIT 1")
    fun observeLastSuccess(): Flow<CaptureRecordEntity?>

    @Query(
        "SELECT COALESCE(SUM(stored_size_bytes), 0) FROM capture_records " +
            "WHERE local_path IS NOT NULL AND local_image_deleted = 0",
    )
    fun observeLocalImageBytes(): Flow<Long>

    @Query(
        "SELECT COALESCE(SUM(stored_size_bytes), 0) FROM capture_records " +
            "WHERE upload_status = 'UPLOADED' AND cloud_deleted = 0",
    )
    fun observeCloudBytes(): Flow<Long>

    @Query(
        "SELECT COALESCE(SUM(stored_size_bytes), 0) FROM capture_records " +
            "WHERE upload_status = 'UPLOADED' AND cloud_deleted = 0",
    )
    suspend fun cloudBytes(): Long

    @Query(
        "SELECT * FROM capture_records WHERE capture_status = 'SUCCESS' AND (local_path IS NOT NULL " +
            "OR thumbnail_path IS NOT NULL OR (cloud_path IS NOT NULL AND cloud_deleted = 0))",
    )
    suspend fun retentionCandidates(): List<CaptureRecordEntity>

    @Query("SELECT * FROM capture_records WHERE cloud_delete_pending = 1")
    suspend fun pendingCloudDeletes(): List<CaptureRecordEntity>

    @Query("SELECT * FROM capture_records WHERE cloud_path IS NOT NULL AND cloud_deleted = 0")
    suspend fun withCloudCopies(): List<CaptureRecordEntity>

    @Query("UPDATE capture_records SET upload_status = 'NOT_QUEUED' WHERE upload_status = 'UPLOAD_PENDING'")
    suspend fun unqueuePendingUploads(): Int

    @Query("DELETE FROM capture_records WHERE capture_id = :captureId")
    suspend fun delete(captureId: String)

    @Query("DELETE FROM capture_records")
    suspend fun deleteAll()
}
