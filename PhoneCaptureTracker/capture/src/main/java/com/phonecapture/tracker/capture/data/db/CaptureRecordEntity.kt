package com.phonecapture.tracker.capture.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.model.VerificationStatus

/**
 * One capture attempt (successful, failed, skipped or missed). All timestamps are UTC epoch
 * milliseconds; screens convert to local time.
 *
 * The unique index on (slot_index, category) makes duplicate attempts for the same 30-minute slot
 * impossible, even if two triggers race.
 */
@Entity(
    tableName = "capture_records",
    indices = [
        Index(value = ["slot_index", "category"], unique = true),
        Index(value = ["captured_at_utc"]),
        Index(value = ["upload_status"]),
    ],
)
data class CaptureRecordEntity(
    @PrimaryKey @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "slot_index") val slotIndex: Long,
    @ColumnInfo(name = "slot_start_utc") val slotStartUtc: Long,
    /** When the attempt actually happened (or, for a missed slot, the slot start). */
    @ColumnInfo(name = "captured_at_utc") val capturedAtUtc: Long,
    @ColumnInfo(name = "category") val category: CaptureCategory,
    @ColumnInfo(name = "capture_status") val captureStatus: CaptureStatus,
    @ColumnInfo(name = "upload_status") val uploadStatus: UploadStatus,
    @ColumnInfo(name = "verification_status") val verificationStatus: VerificationStatus = VerificationStatus.NOT_VERIFIED,
    /** Encrypted full-size image on the phone. */
    @ColumnInfo(name = "local_path") val localPath: String? = null,
    /** Encrypted thumbnail on the phone. */
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    /** Object path in Firebase Storage once uploaded. */
    @ColumnInfo(name = "cloud_path") val cloudPath: String? = null,
    /** Size of the JPEG before encryption. */
    @ColumnInfo(name = "image_size_bytes") val imageSizeBytes: Long = 0,
    /** Size of the encrypted file (what is stored locally and uploaded). */
    @ColumnInfo(name = "stored_size_bytes") val storedSizeBytes: Long = 0,
    @ColumnInfo(name = "width_px") val widthPx: Int? = null,
    @ColumnInfo(name = "height_px") val heightPx: Int? = null,
    @ColumnInfo(name = "battery_percent") val batteryPercent: Int? = null,
    @ColumnInfo(name = "failure_reason") val failureReason: FailureReason? = null,
    @ColumnInfo(name = "failure_detail") val failureDetail: String? = null,
    /** Extra information about a successful capture, e.g. "screen looked blacked out (secure app)". */
    @ColumnInfo(name = "note") val note: String? = null,
    @ColumnInfo(name = "key_id") val keyId: String? = null,
    @ColumnInfo(name = "ciphertext_sha256") val ciphertextSha256: String? = null,
    @ColumnInfo(name = "upload_attempts") val uploadAttempts: Int = 0,
    @ColumnInfo(name = "upload_progress") val uploadProgress: Int = 0,
    @ColumnInfo(name = "upload_error") val uploadError: String? = null,
    @ColumnInfo(name = "uploaded_at_utc") val uploadedAtUtc: Long? = null,
    /** Full-size local image removed (retention or "delete after verified upload"). */
    @ColumnInfo(name = "local_image_deleted") val localImageDeleted: Boolean = false,
    @ColumnInfo(name = "cloud_deleted") val cloudDeleted: Boolean = false,
    /** User asked to delete the cloud copy while offline; the clean-up worker finishes it. */
    @ColumnInfo(name = "cloud_delete_pending") val cloudDeletePending: Boolean = false,
)
