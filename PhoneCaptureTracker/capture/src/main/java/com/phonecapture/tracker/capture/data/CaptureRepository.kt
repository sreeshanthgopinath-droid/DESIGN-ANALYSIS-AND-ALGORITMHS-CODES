package com.phonecapture.tracker.capture.data

import com.phonecapture.tracker.capture.data.db.CaptureRecordDao
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.schedule.CaptureSchedule
import com.phonecapture.tracker.core.stats.DaySummaries
import com.phonecapture.tracker.core.stats.DaySummary
import com.phonecapture.tracker.core.time.DayWindow
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CaptureRepository(
    val dao: CaptureRecordDao,
    private val schedule: CaptureSchedule,
) {
    /** @return false when the slot/category pair was already recorded (duplicate prevented). */
    suspend fun insertAttempt(record: CaptureRecordEntity): Boolean = dao.insert(record) != -1L

    /**
     * Records slots that passed without a cycle. These are never captured late; they are stored as
     * FAILED attempts with the given reason so the history and counts stay truthful.
     */
    suspend fun recordMissedSlots(
        slots: List<Long>,
        categories: List<CaptureCategory>,
        reason: FailureReason,
        detail: String,
    ): Int {
        var inserted = 0
        for (slot in slots) {
            val start = schedule.slotStart(slot)
            for (category in categories) {
                val record = CaptureRecordEntity(
                    captureId = UUID.randomUUID().toString(),
                    slotIndex = slot,
                    slotStartUtc = start,
                    capturedAtUtc = start,
                    category = category,
                    captureStatus = reason.resultingStatus,
                    uploadStatus = UploadStatus.NOT_QUEUED,
                    failureReason = reason,
                    failureDetail = detail,
                )
                if (dao.insert(record) != -1L) inserted++
            }
        }
        return inserted
    }

    fun observeDay(window: DayWindow): Flow<List<CaptureRecordEntity>> =
        dao.observeBetween(window.startUtcMillis, window.endUtcMillisExclusive)

    fun observeDaySummary(window: DayWindow): Flow<DaySummary> =
        observeDay(window).map { records -> DaySummaries.summarize(records.map { it.toAttemptInfo() }) }

    fun observeGallery(category: CaptureCategory, range: LongRange): Flow<List<CaptureRecordEntity>> =
        dao.observeGallery(category, range.first, range.last + 1)

    fun observeRecent(limit: Int = 500): Flow<List<CaptureRecordEntity>> = dao.observeRecent(limit)

    fun observeLastSuccess(): Flow<CaptureRecordEntity?> = dao.observeLastSuccess()
    fun observeLocalBytes(): Flow<Long> = dao.observeLocalImageBytes()
    fun observeCloudBytes(): Flow<Long> = dao.observeCloudBytes()
    fun observePendingUploads(): Flow<Int> = dao.observePendingUploadCount()
    fun observeFailedUploads(): Flow<Int> = dao.observeFailedUploadCount()

    suspend fun get(captureId: String): CaptureRecordEntity? = dao.get(captureId)
    suspend fun update(record: CaptureRecordEntity) = dao.update(record)
}
