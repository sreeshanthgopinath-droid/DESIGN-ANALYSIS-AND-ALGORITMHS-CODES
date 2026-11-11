package com.phonecapture.tracker.capture.projection

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import com.phonecapture.tracker.capture.camera.CaptureOutcome
import com.phonecapture.tracker.capture.camera.JpegProcessor
import com.phonecapture.tracker.capture.diagnostics.DiagnosticLogger
import com.phonecapture.tracker.capture.service.CaptureNotifications
import com.phonecapture.tracker.capture.service.DeviceStateReader
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.FailureReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ProjectionStatus {
    /** No session in this app process (never started, or stopped by the user). */
    data object NotStarted : ProjectionStatus

    data class Active(val sinceUtc: Long, val widthPx: Int, val heightPx: Int) : ProjectionStatus

    /** Android ended the session. A new consent is needed; the app never re-prompts on its own. */
    data class Ended(val atUtc: Long, val reason: FailureReason, val detail: String) : ProjectionStatus
}

/**
 * Public entry point for screenshots: consent intent, session state and single-frame capture.
 *
 * The consent dialog is only ever launched by the UI in response to a user tap while the app is
 * visible. When a session ends, this class records it and posts a notification asking the user to
 * open the app; it never relaunches the dialog from the background.
 */
class ProjectionController(
    context: Context,
    deviceState: DeviceStateReader,
    processor: JpegProcessor,
    private val diagnostics: DiagnosticLogger,
    private val notifications: CaptureNotifications,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val _status = MutableStateFlow<ProjectionStatus>(ProjectionStatus.NotStarted)
    val status: StateFlow<ProjectionStatus> = _status.asStateFlow()

    private val screenshot = ScreenshotCapture(appContext, deviceState, processor, ::onSessionEnded)

    /** Intent for the system screen-sharing dialog. On Android 14+ it asks for the entire screen. */
    fun createConsentIntent(): Intent {
        val manager = appContext.getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    /**
     * Handles the dialog result. Must be called from the visible Activity that launched the dialog,
     * because it starts the mediaProjection foreground service.
     */
    fun onConsentResult(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            diagnostics.log(DiagnosticType.CONSENT_REVOKED, "Screen sharing was not approved in the system dialog")
            return false
        }
        return try {
            ProjectionService.start(appContext, resultCode, data)
            true
        } catch (e: Exception) {
            diagnostics.log(
                DiagnosticType.FOREGROUND_SERVICE_UNAVAILABLE,
                "The screen-sharing service could not be started",
                e.toString(),
            )
            false
        }
    }

    /** Called by [ProjectionService] once it is in the foreground. */
    internal fun startSession(resultCode: Int, data: Intent): Boolean =
        screenshot.start(resultCode, data).fold(
            onSuccess = { size ->
                _status.value = ProjectionStatus.Active(clock(), size.width, size.height)
                diagnostics.log(DiagnosticType.PROJECTION_STARTED, "Screen sharing started", "${size.width}×${size.height}")
                notifications.cancelProjectionEnded()
                true
            },
            onFailure = { e ->
                _status.value = ProjectionStatus.Ended(clock(), FailureReason.CONSENT_REVOKED, "Screen sharing could not start: ${e.message}")
                diagnostics.log(DiagnosticType.CONSENT_REVOKED, "Screen sharing could not start", e.toString())
                false
            },
        )

    /** User tapped "Stop screen sharing" (in the app or the notification). */
    fun stopByUser() {
        val wasActive = screenshot.isActive
        screenshot.stop()
        _status.value = ProjectionStatus.NotStarted
        if (wasActive) diagnostics.log(DiagnosticType.SCHEDULE_CHANGED, "Screen sharing stopped by the user")
        appContext.stopService(Intent(appContext, ProjectionService::class.java))
    }

    internal fun onServiceDestroyed() {
        if (screenshot.isActive) {
            screenshot.stop()
            onSessionEnded(FailureReason.PROJECTION_STOPPED, "Android stopped the screen-sharing service.")
        }
    }

    suspend fun captureScreenshot(jpegQuality: Int, maxEdgePx: Int): CaptureOutcome =
        when (val current = _status.value) {
            is ProjectionStatus.Active -> screenshot.capture(jpegQuality, maxEdgePx)
            is ProjectionStatus.Ended -> CaptureOutcome.NotCaptured(
                current.reason,
                current.detail + " Open the app and start screen sharing again.",
            )
            ProjectionStatus.NotStarted -> CaptureOutcome.NotCaptured(
                FailureReason.PROJECTION_NOT_ACTIVE,
                "Screen sharing is not active. Open the app and tap 'Start screen sharing'.",
            )
        }

    private fun onSessionEnded(reason: FailureReason, detail: String) {
        _status.value = ProjectionStatus.Ended(clock(), reason, detail)
        diagnostics.failure(reason, detail)
        notifications.showProjectionEnded(detail)
        appContext.stopService(Intent(appContext, ProjectionService::class.java))
    }
}
