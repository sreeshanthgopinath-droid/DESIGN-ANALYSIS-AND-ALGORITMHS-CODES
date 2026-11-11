package com.phonecapture.tracker.capture

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.phonecapture.tracker.capture.camera.CameraCaptureController
import com.phonecapture.tracker.capture.camera.CaptureOutcome
import com.phonecapture.tracker.capture.camera.FrontCameraController
import com.phonecapture.tracker.capture.camera.JpegProcessor
import com.phonecapture.tracker.capture.camera.RearCameraController
import com.phonecapture.tracker.capture.cloud.UploadWorker
import com.phonecapture.tracker.capture.projection.ProjectionStatus
import com.phonecapture.tracker.core.model.FailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Camera, MediaProjection, permission and upload tests on a real device or emulator.
 * These accept either a real image or an honest, documented failure – never a fake success.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCaptureInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val camera: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val acceptableCameraFailures = setOf(
        FailureReason.CAMERA_UNAVAILABLE,
        FailureReason.CAMERA_BUSY,
        FailureReason.CAMERA_DISABLED,
        FailureReason.CAPTURE_TIMEOUT,
    )

    private fun assertRealPhotoOrHonestFailure(controller: CameraCaptureController) = runBlocking {
        val owner = withContext(Dispatchers.Main) { TestLifecycleOwner(Lifecycle.State.RESUMED) }
        when (val outcome = controller.capture(owner, jpegQuality = 85, maxEdgePx = 2560)) {
            is CaptureOutcome.Captured -> {
                val bitmap = BitmapFactory.decodeByteArray(outcome.jpeg, 0, outcome.jpeg.size)
                assertNotNull("Captured JPEG must decode", bitmap)
                assertTrue(outcome.width in 1..2560 && outcome.height in 1..2560)
                assertTrue(outcome.thumbnail.isNotEmpty())
            }
            is CaptureOutcome.NotCaptured -> {
                Log.w("PCT-Test", "${controller.category} not captured: ${outcome.reason} – ${outcome.detail}")
                assertTrue("Unexpected reason ${outcome.reason}", outcome.reason in acceptableCameraFailures)
            }
        }
    }

    @Test
    fun frontCameraCapturesOrReportsWhy() {
        val front = FrontCameraController(context, JpegProcessor()) { true }
        assumeTrue("No front camera on this device", runBlocking { front.isAvailable() })
        assertRealPhotoOrHonestFailure(front)
    }

    @Test
    fun rearCameraCapturesOrReportsWhy() {
        val rear = RearCameraController(context, JpegProcessor()) { true }
        assumeTrue("No rear camera on this device", runBlocking { rear.isAvailable() })
        assertRealPhotoOrHonestFailure(rear)
    }

    @Test
    fun missingCameraPermissionIsReportedNotFaked() = runBlocking {
        val front = FrontCameraController(context, JpegProcessor()) { false } // simulate a revoked permission
        val owner = withContext(Dispatchers.Main) { TestLifecycleOwner(Lifecycle.State.RESUMED) }
        val outcome = front.capture(owner, 85, 2560)
        assertEquals(FailureReason.CAMERA_PERMISSION_DENIED, (outcome as CaptureOutcome.NotCaptured).reason)
    }

    @Test
    fun screenshotWithoutConsentReportsProjectionNotActive() = runBlocking {
        val projection = CaptureGraph.get(context).projection
        assumeTrue(projection.status.value !is ProjectionStatus.Active)
        val outcome = projection.captureScreenshot(85, 2560)
        val reason = (outcome as CaptureOutcome.NotCaptured).reason
        assertTrue(reason == FailureReason.PROJECTION_NOT_ACTIVE || reason == FailureReason.PROJECTION_STOPPED)
    }

    @Test
    fun uploadWorkerDoesNothingInLocalOnlyMode() = runBlocking {
        val graph = CaptureGraph.get(context)
        graph.settings.update { it.copy(cloudSyncEnabled = false) }
        val worker = TestListenableWorkerBuilder<UploadWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
