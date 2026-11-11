package com.phonecapture.tracker.capture.camera

import com.phonecapture.tracker.core.model.FailureReason

/** Result of one camera or screen capture, before encryption and storage. */
sealed interface CaptureOutcome {

    /** A real image was produced. [jpeg] and [thumbnail] are plaintext and live only in memory. */
    class Captured(
        val jpeg: ByteArray,
        val thumbnail: ByteArray,
        val width: Int,
        val height: Int,
        val note: String? = null,
    ) : CaptureOutcome

    /** No image. Recorded as FAILED or SKIPPED according to [FailureReason.resultingStatus]. */
    data class NotCaptured(val reason: FailureReason, val detail: String) : CaptureOutcome
}
