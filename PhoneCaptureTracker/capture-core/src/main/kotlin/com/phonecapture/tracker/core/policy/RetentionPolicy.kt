package com.phonecapture.tracker.core.policy

import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.model.VerificationStatus

/** 0 days means "keep forever". */
data class RetentionSettings(
    val localRetentionDays: Int,
    val cloudRetentionDays: Int,
    val deleteLocalAfterVerifiedUpload: Boolean,
    val cloudSyncEnabled: Boolean,
)

data class RetentionItem(
    val captureId: String,
    val capturedAtUtcMillis: Long,
    val hasLocalImage: Boolean,
    val hasLocalThumbnail: Boolean,
    val uploadStatus: UploadStatus,
    val verificationStatus: VerificationStatus,
    val hasCloudCopy: Boolean,
)

data class RetentionPlan(
    /** Delete only the full-size local image; keep the small encrypted thumbnail for the gallery. */
    val deleteLocalImageOnly: Set<String>,
    /** Delete the local image and thumbnail. */
    val deleteLocalEverything: Set<String>,
    /** Delete the cloud object and its metadata document. */
    val deleteCloud: Set<String>,
) {
    val isEmpty: Boolean get() = deleteLocalImageOnly.isEmpty() && deleteLocalEverything.isEmpty() && deleteCloud.isEmpty()
}

/**
 * Decides what the periodic clean-up may delete. Guarantees:
 *  - With cloud sync on, a local file is never deleted until a *verified* cloud copy exists, so the
 *    only copy of a capture is never removed by an automatic rule.
 *  - "Delete after verified upload" only runs when the user enabled it.
 */
object RetentionPolicy {
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun plan(nowUtcMillis: Long, items: List<RetentionItem>, settings: RetentionSettings): RetentionPlan {
        val imageOnly = mutableSetOf<String>()
        val everything = mutableSetOf<String>()
        val cloud = mutableSetOf<String>()

        for (item in items) {
            val ageMillis = nowUtcMillis - item.capturedAtUtcMillis
            val safelyInCloud = item.hasCloudCopy &&
                item.uploadStatus == UploadStatus.UPLOADED &&
                item.verificationStatus == VerificationStatus.VERIFIED
            val hasLocal = item.hasLocalImage || item.hasLocalThumbnail

            val localExpired = settings.localRetentionDays > 0 && ageMillis >= settings.localRetentionDays * DAY_MILLIS
            if (hasLocal && localExpired && (!settings.cloudSyncEnabled || safelyInCloud)) {
                everything += item.captureId
            } else if (item.hasLocalImage && settings.deleteLocalAfterVerifiedUpload && safelyInCloud) {
                imageOnly += item.captureId
            }

            val cloudExpired = settings.cloudRetentionDays > 0 && ageMillis >= settings.cloudRetentionDays * DAY_MILLIS
            if (item.hasCloudCopy && cloudExpired) cloud += item.captureId
        }
        return RetentionPlan(imageOnly, everything, cloud)
    }
}
