package com.phonecapture.tracker.capture.projection

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.service.CaptureNotifications
import com.phonecapture.tracker.core.model.DiagnosticType

/**
 * Foreground service of type mediaProjection that keeps the approved screen-sharing session alive.
 * Android shows its own screen-sharing indicator in addition to this service's notification.
 *
 * START_NOT_STICKY: a consent token can be used only once, so after process death the session
 * cannot (and must not) be resumed automatically.
 */
class ProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = CaptureGraph.get(this)
        if (intent?.action != ACTION_START) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        if (data == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            // Must be in the foreground with type mediaProjection BEFORE obtaining the projection.
            ServiceCompat.startForeground(
                this,
                CaptureNotifications.ID_SCREEN_SHARING,
                graph.notifications.screenSharingNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (e: Exception) {
            graph.diagnostics.log(
                DiagnosticType.FOREGROUND_SERVICE_UNAVAILABLE,
                "Android did not allow the screen-sharing service to start",
                e.toString(),
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!graph.projection.startSession(resultCode, data)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CaptureGraph.get(this).projection.onServiceDestroyed()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.phonecapture.tracker.capture.projection.START"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** Only valid right after the user approved the system dialog (app in the foreground). */
        internal fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
