package com.phonecapture.tracker.core.model

/** The three independent capture streams. */
enum class CaptureCategory(
    val displayName: String,
    val shortName: String,
    /** Folder name used in cloud storage: PhoneCaptureTracker/<uid>/<yyyy>/<MM>/<dd>/<cloudFolder>/ */
    val cloudFolder: String,
    /** Tag used inside file names. */
    val fileTag: String,
) {
    FRONT("Front camera", "Front", "Front", "front"),
    REAR("Rear camera", "Rear", "Rear", "rear"),
    SCREENSHOT("Screenshot", "Screen", "Screenshots", "screen"),
}

/**
 * Outcome of one capture attempt. A failed or skipped attempt is never turned into SUCCESS.
 *
 * SUCCESS – an image was captured, encrypted and saved.
 * FAILED  – the attempt was made (or was due) and did not produce an image.
 * SKIPPED – the attempt was intentionally not made (e.g. screen off/locked for a screenshot).
 */
enum class CaptureStatus { SUCCESS, FAILED, SKIPPED }

/**
 * Cloud state of a capture. Kept separate from [CaptureStatus] so an upload problem can never
 * change the truth about whether the capture itself succeeded.
 *
 * NOT_QUEUED – local only (cloud sync off, not signed in, or nothing to upload).
 */
enum class UploadStatus { NOT_QUEUED, UPLOAD_PENDING, UPLOADED, UPLOAD_FAILED }

/** Result of comparing the uploaded object (size + MD5) with the local encrypted file. */
enum class VerificationStatus { NOT_VERIFIED, VERIFIED, MISMATCH }

/** Life-cycle of the 30-minute schedule as chosen by the user. */
enum class ScheduleState { STOPPED, RUNNING, PAUSED }

/** Minimal, platform-independent view of one attempt, used for statistics and text export. */
data class AttemptInfo(
    val captureId: String,
    val slotIndex: Long,
    val category: CaptureCategory,
    val captureStatus: CaptureStatus,
    val uploadStatus: UploadStatus,
    val capturedAtUtcMillis: Long,
    val storedSizeBytes: Long,
    val failureReason: FailureReason?,
)
