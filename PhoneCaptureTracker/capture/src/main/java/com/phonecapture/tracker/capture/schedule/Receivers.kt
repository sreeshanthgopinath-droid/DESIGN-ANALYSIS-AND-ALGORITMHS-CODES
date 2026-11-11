package com.phonecapture.tracker.capture.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.service.CameraCaptureService
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.ScheduleState

/** Delivers the 30-minute alarm to the running capture service. */
class CaptureAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val graph = CaptureGraph.get(context)
        if (graph.scheduleStore.current.state != ScheduleState.RUNNING) return

        if (CameraCaptureService.isRunning.value) {
            graph.cycleWakeLock.acquire() // bridge the gap until the service takes over
            if (!CameraCaptureService.send(context, CameraCaptureService.ACTION_TICK)) {
                graph.cycleWakeLock.release()
            }
        } else {
            // The process was restarted just for this alarm: the camera service is gone and Android
            // does not allow camera access to be started from the background. Report, don't fake.
            graph.diagnostics.log(
                DiagnosticType.PROCESS_INTERRUPTED,
                "A scheduled attempt could not run because Android had stopped the capture service",
                "Missed attempts are recorded when the schedule resumes.",
            )
            graph.notifications.showServiceInterrupted("Android stopped the capture service.")
        }
    }

    companion object {
        const val ACTION_ALARM = "com.phonecapture.tracker.capture.action.ALARM"
    }
}

/**
 * After a reboot or app update the camera service cannot be restarted automatically (Android 15+
 * forbids starting camera foreground services from BOOT_COMPLETED). The user is told to open the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "The phone restarted while capture was on."
            Intent.ACTION_MY_PACKAGE_REPLACED -> "The app was updated while capture was on."
            else -> return
        }
        val graph = CaptureGraph.get(context)
        if (graph.scheduleStore.current.state == ScheduleState.STOPPED) return
        graph.diagnostics.log(
            DiagnosticType.PROCESS_INTERRUPTED,
            reason,
            "Android does not allow camera services to start in the background. Open the app to resume.",
        )
        graph.notifications.showServiceInterrupted(reason)
    }
}
