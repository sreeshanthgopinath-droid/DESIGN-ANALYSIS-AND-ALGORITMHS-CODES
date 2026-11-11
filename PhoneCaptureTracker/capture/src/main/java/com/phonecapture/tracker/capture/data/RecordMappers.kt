package com.phonecapture.tracker.capture.data

import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.core.model.AttemptInfo
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.policy.RetentionItem

val CaptureRecordEntity.hasLocalImage: Boolean
    get() = localPath != null && !localImageDeleted

val CaptureRecordEntity.hasThumbnail: Boolean
    get() = thumbnailPath != null

val CaptureRecordEntity.hasCloudCopy: Boolean
    get() = cloudPath != null && uploadStatus == UploadStatus.UPLOADED && !cloudDeleted

fun CaptureRecordEntity.toAttemptInfo(): AttemptInfo = AttemptInfo(
    captureId = captureId,
    slotIndex = slotIndex,
    category = category,
    captureStatus = captureStatus,
    uploadStatus = uploadStatus,
    capturedAtUtcMillis = capturedAtUtc,
    storedSizeBytes = storedSizeBytes,
    failureReason = failureReason,
)

fun CaptureRecordEntity.toRetentionItem(): RetentionItem = RetentionItem(
    captureId = captureId,
    capturedAtUtcMillis = capturedAtUtc,
    hasLocalImage = hasLocalImage,
    hasLocalThumbnail = hasThumbnail,
    uploadStatus = uploadStatus,
    verificationStatus = verificationStatus,
    hasCloudCopy = hasCloudCopy,
)
