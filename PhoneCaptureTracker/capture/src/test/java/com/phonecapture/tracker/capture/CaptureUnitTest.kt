package com.phonecapture.tracker.capture

import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.phonecapture.tracker.capture.camera.CameraErrorMapper
import com.phonecapture.tracker.capture.cloud.CloudUploader
import com.phonecapture.tracker.capture.data.settings.CaptureSettings
import com.phonecapture.tracker.capture.schedule.CaptureCycleRunner
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.policy.UploadErrorKind
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for the Android module (run with ./gradlew :capture:testDebugUnitTest). */
class CaptureUnitTest {

    @Test
    fun cameraErrorsMapToHonestReasons() {
        assertEquals(FailureReason.CAMERA_BUSY, CameraErrorMapper.fromStateError(CameraState.ERROR_CAMERA_IN_USE))
        assertEquals(FailureReason.CAMERA_BUSY, CameraErrorMapper.fromStateError(CameraState.ERROR_MAX_CAMERAS_IN_USE))
        assertEquals(FailureReason.CAMERA_DISABLED, CameraErrorMapper.fromStateError(CameraState.ERROR_CAMERA_DISABLED))
        assertEquals(FailureReason.CAMERA_ERROR, CameraErrorMapper.fromStateError(CameraState.ERROR_CAMERA_FATAL_ERROR))
        assertEquals(FailureReason.CAMERA_BUSY, CameraErrorMapper.fromImageCaptureError(ImageCapture.ERROR_CAMERA_CLOSED))
        assertEquals(FailureReason.CAMERA_UNAVAILABLE, CameraErrorMapper.fromImageCaptureError(ImageCapture.ERROR_INVALID_CAMERA))
        assertEquals(FailureReason.CAMERA_ERROR, CameraErrorMapper.fromImageCaptureError(ImageCapture.ERROR_CAPTURE_FAILED))
    }

    @Test
    fun settingsAreSanitised() {
        val s = CaptureSettings(jpegQuality = 400, maxImageEdgePx = 10, localRetentionDays = -3, cloudQuotaBytes = 1).sanitized()
        assertEquals(100, s.jpegQuality)
        assertEquals(720, s.maxImageEdgePx)
        assertEquals(0, s.localRetentionDays)
        assertTrue(s.cloudQuotaBytes >= 100L * 1024 * 1024)
    }

    @Test
    fun defaultsArePrivacyFriendly() {
        val d = CaptureSettings()
        assertEquals(85, d.jpegQuality)
        assertFalse(d.cloudSyncEnabled)
        assertFalse(d.allowMobileDataUpload)
        assertFalse(d.deleteLocalAfterVerifiedUpload)
        assertFalse(d.onboardingCompleted)
    }

    @Test
    fun enabledCategoriesAreAttemptedInOrderFrontRearScreen() {
        assertEquals(
            listOf(CaptureCategory.FRONT, CaptureCategory.REAR, CaptureCategory.SCREENSHOT),
            CaptureCycleRunner.enabledCategories(CaptureSettings()),
        )
        assertEquals(
            listOf(CaptureCategory.SCREENSHOT),
            CaptureCycleRunner.enabledCategories(CaptureSettings(captureFront = false, captureRear = false)),
        )
        assertEquals("front camera, rear camera and screenshot", CaptureCycleRunner.describe(CaptureCategory.entries))
    }

    @Test
    fun uploadErrorsAreClassified() {
        assertEquals(UploadErrorKind.NETWORK, CloudUploader.classify(IOException("reset")))
        assertEquals(UploadErrorKind.NETWORK, CloudUploader.classify(FirebaseNetworkException("offline")))
        assertEquals(
            UploadErrorKind.PERMISSION_DENIED,
            CloudUploader.classify(FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)),
        )
        assertEquals(
            UploadErrorKind.QUOTA_EXCEEDED,
            CloudUploader.classify(FirebaseFirestoreException("full", FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED)),
        )
        assertEquals(UploadErrorKind.UNKNOWN, CloudUploader.classify(IllegalArgumentException("x")))
    }
}
