package com.phonecapture.tracker.capture.camera

import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import com.phonecapture.tracker.core.model.FailureReason

/** Maps CameraX error codes to user-facing failure reasons. The raw code is kept in the detail text. */
object CameraErrorMapper {

    fun fromImageCaptureError(code: Int): FailureReason = when (code) {
        // The camera was closed underneath us, typically because another app took it over.
        ImageCapture.ERROR_CAMERA_CLOSED -> FailureReason.CAMERA_BUSY
        ImageCapture.ERROR_INVALID_CAMERA -> FailureReason.CAMERA_UNAVAILABLE
        ImageCapture.ERROR_CAPTURE_FAILED -> FailureReason.CAMERA_ERROR
        ImageCapture.ERROR_FILE_IO -> FailureReason.CAMERA_ERROR
        else -> FailureReason.CAMERA_ERROR
    }

    fun fromStateError(code: Int): FailureReason = when (code) {
        CameraState.ERROR_CAMERA_IN_USE,
        CameraState.ERROR_MAX_CAMERAS_IN_USE,
        -> FailureReason.CAMERA_BUSY
        // Also what Android reports when a background app lacks while-in-use camera access,
        // or when the user turned off the camera privacy toggle.
        CameraState.ERROR_CAMERA_DISABLED,
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
        -> FailureReason.CAMERA_DISABLED
        else -> FailureReason.CAMERA_ERROR
    }

    fun stateErrorName(code: Int): String = when (code) {
        CameraState.ERROR_MAX_CAMERAS_IN_USE -> "MAX_CAMERAS_IN_USE"
        CameraState.ERROR_CAMERA_IN_USE -> "CAMERA_IN_USE"
        CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> "OTHER_RECOVERABLE_ERROR"
        CameraState.ERROR_STREAM_CONFIG -> "STREAM_CONFIG"
        CameraState.ERROR_CAMERA_DISABLED -> "CAMERA_DISABLED"
        CameraState.ERROR_CAMERA_FATAL_ERROR -> "CAMERA_FATAL_ERROR"
        CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> "DO_NOT_DISTURB_MODE_ENABLED"
        else -> "UNKNOWN($code)"
    }
}
