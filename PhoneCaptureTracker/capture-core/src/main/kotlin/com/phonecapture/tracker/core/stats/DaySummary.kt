package com.phonecapture.tracker.core.stats

import com.phonecapture.tracker.core.model.AttemptInfo
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.UploadStatus

data class DaySummary(
    /** Every recorded attempt for the day, including failed, skipped and missed ones. */
    val scheduledAttempts: Int,
    val successful: Int,
    val failed: Int,
    val skipped: Int,
    val frontCount: Int,
    val rearCount: Int,
    val screenshotCount: Int,
    val uploaded: Int,
    val pendingUploads: Int,
    val failedUploads: Int,
    val storedBytes: Long,
    val lastSuccessUtcMillis: Long?,
) {
    companion object {
        val EMPTY = DaySummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null)
    }
}

object DaySummaries {
    fun summarize(attempts: List<AttemptInfo>): DaySummary {
        val successes = attempts.filter { it.captureStatus == CaptureStatus.SUCCESS }
        return DaySummary(
            scheduledAttempts = attempts.size,
            successful = successes.size,
            failed = attempts.count { it.captureStatus == CaptureStatus.FAILED },
            skipped = attempts.count { it.captureStatus == CaptureStatus.SKIPPED },
            frontCount = successes.count { it.category == CaptureCategory.FRONT },
            rearCount = successes.count { it.category == CaptureCategory.REAR },
            screenshotCount = successes.count { it.category == CaptureCategory.SCREENSHOT },
            uploaded = attempts.count { it.uploadStatus == UploadStatus.UPLOADED },
            pendingUploads = attempts.count { it.uploadStatus == UploadStatus.UPLOAD_PENDING },
            failedUploads = attempts.count { it.uploadStatus == UploadStatus.UPLOAD_FAILED },
            storedBytes = successes.sumOf { it.storedSizeBytes },
            lastSuccessUtcMillis = successes.maxOfOrNull { it.capturedAtUtcMillis },
        )
    }
}
