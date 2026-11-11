package com.phonecapture.tracker.capture.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.projection.ProjectionStatus
import com.phonecapture.tracker.capture.schedule.CaptureCycleRunner
import com.phonecapture.tracker.capture.schedule.TickResult
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.ScheduleState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service of type `camera` that owns the 30-minute schedule.
 *
 * - It must be started while the app is visible (Android's while-in-use rule for camera). Once
 *   running, it may use the camera while the app is in the background; whether that also works
 *   with the screen off or locked depends on the device and is verified on the phone, not assumed.
 * - Ticks come from an exact alarm (wakes the device) and from an in-service timer (while awake).
 *   Both funnel into [CaptureCycleRunner.tick], which ignores duplicates.
 * - A persistent notification with Pause/Resume/Stop is shown for the whole lifetime.
 */
class CameraCaptureService : LifecycleService() {

    private lateinit var graph: CaptureGraph
    private var timerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        graph = CaptureGraph.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        if (graph.scheduleStore.current.state == ScheduleState.STOPPED) {
            // e.g. a sticky restart after the user had stopped capture. Satisfy the
            // startForegroundService() contract if possible, then leave quietly.
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    CaptureNotifications.ID_SCHEDULE,
                    graph.notifications.scheduleNotification(currentNotice()),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                )
            }
            shutDown()
            return START_NOT_STICKY
        }
        if (!enterForeground()) return START_NOT_STICKY
        running.value = true
        graph.notifications.cancelServiceInterrupted()

        when (action) {
            null -> {
                graph.diagnostics.log(DiagnosticType.PROCESS_INTERRUPTED, "Android restarted the capture service after stopping the app")
                ensureTimer()
                requestTick()
            }
            ACTION_START, ACTION_TICK -> {
                ensureTimer()
                requestTick()
            }
            else -> refreshNotification() // ACTION_REFRESH (pause, settings or projection changes)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        timerJob?.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            CaptureNotifications.ID_SCHEDULE,
            graph.notifications.scheduleNotification(currentNotice()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException, or SecurityException when the camera permission
        // was revoked or the start came from the background (Android 14+ while-in-use rules).
        graph.diagnostics.log(
            DiagnosticType.FOREGROUND_SERVICE_UNAVAILABLE,
            "Android did not allow the camera capture service to run",
            "${e.javaClass.simpleName}: ${e.message}",
        )
        graph.notifications.showServiceInterrupted("Android did not allow the capture service to run in the background.")
        running.value = false
        stopSelf()
        false
    }

    private fun requestTick() {
        lifecycleScope.launch {
            graph.cycleWakeLock.acquire()
            try {
                refreshNotification(cycleRunning = true)
                when (val result = graph.cycleRunner.tick(this@CameraCaptureService)) {
                    is TickResult.Done -> graph.alarms.schedule(result.nextFireAtMillis)
                    TickResult.NotRunning -> graph.alarms.cancel()
                    TickResult.AlreadyRunning -> Unit
                }
            } finally {
                graph.cycleWakeLock.release()
                refreshNotification()
            }
        }
    }

    /** Secondary trigger while the CPU is awake; the alarm covers deep sleep. */
    private fun ensureTimer() {
        if (timerJob?.isActive == true) return
        timerJob = lifecycleScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val next = graph.scheduleStore.current.nextAttemptUtc ?: graph.schedule.nextSlotStart(now)
                delay((next - now).coerceIn(5_000L, graph.schedule.intervalMillis))
                if (graph.scheduleStore.current.state == ScheduleState.RUNNING && !graph.cycleRunner.isCycleRunning) {
                    requestTick()
                }
            }
        }
    }

    private fun refreshNotification(cycleRunning: Boolean = false) {
        graph.notifications.updateSchedule(currentNotice(cycleRunning))
    }

    private fun currentNotice(cycleRunning: Boolean = false): ScheduleNotice {
        val snapshot = graph.scheduleStore.current
        return ScheduleNotice(
            state = snapshot.state,
            nextAttemptUtc = snapshot.nextAttemptUtc,
            lastCycleUtc = snapshot.lastCycleUtc,
            screenSharingActive = graph.projection.status.value is ProjectionStatus.Active,
            cycleRunning = cycleRunning,
            categoriesDescription = CaptureCycleRunner.describe(CaptureCycleRunner.enabledCategories(graph.settings.current)),
        )
    }

    private fun shutDown() {
        timerJob?.cancel()
        graph.alarms.cancel()
        running.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.phonecapture.tracker.capture.action.START"
        const val ACTION_TICK = "com.phonecapture.tracker.capture.action.TICK"
        const val ACTION_REFRESH = "com.phonecapture.tracker.capture.action.REFRESH"
        const val ACTION_STOP = "com.phonecapture.tracker.capture.action.STOP"

        private val running = MutableStateFlow(false)

        /** True while the service is in the foreground in this process. */
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        /** Only call while the app is visible (user tapped Start/Resume or opened the app). */
        fun startFromForeground(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        /** Sends a command to the running service. Returns false if Android refused the start. */
        fun send(context: Context, action: String): Boolean = try {
            context.startService(intent(context, action)) != null
        } catch (e: IllegalStateException) {
            false
        } catch (e: SecurityException) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraCaptureService::class.java))
        }

        private fun intent(context: Context, action: String) =
            Intent(context, CameraCaptureService::class.java).setAction(action)
    }
}
