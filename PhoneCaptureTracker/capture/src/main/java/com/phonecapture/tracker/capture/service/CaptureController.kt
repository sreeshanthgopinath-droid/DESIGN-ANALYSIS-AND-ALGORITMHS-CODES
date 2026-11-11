package com.phonecapture.tracker.capture.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.data.settings.ScheduleStateStore
import com.phonecapture.tracker.capture.data.settings.SettingsRepository
import com.phonecapture.tracker.capture.diagnostics.DiagnosticLogger
import com.phonecapture.tracker.capture.projection.ProjectionController
import com.phonecapture.tracker.capture.schedule.CaptureAlarmScheduler
import com.phonecapture.tracker.capture.security.VaultManager
import com.phonecapture.tracker.capture.security.VaultState
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.ScheduleState
import com.phonecapture.tracker.core.schedule.ScheduleCommand
import com.phonecapture.tracker.core.schedule.SchedulePlanner
import com.phonecapture.tracker.core.schedule.ScheduleStateMachine
import com.phonecapture.tracker.core.schedule.Transition

sealed interface CommandResult {
    data object Ok : CommandResult
    data class Blocked(val message: String) : CommandResult
}

/** User-facing commands. Every change is explicit, logged, and reflected in the notification. */
class CaptureController(
    context: Context,
    private val scheduleStore: ScheduleStateStore,
    private val settings: SettingsRepository,
    private val planner: SchedulePlanner,
    private val alarms: CaptureAlarmScheduler,
    private val projection: ProjectionController,
    private val deviceState: DeviceStateReader,
    private val vault: VaultManager,
    private val diagnostics: DiagnosticLogger,
    private val notifications: CaptureNotifications,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext

    /** Call from the visible app (button tap). */
    fun start(): CommandResult {
        preconditionProblem()?.let { return it }
        val now = clock()
        when (val t = ScheduleStateMachine.apply(scheduleStore.current.state, ScheduleCommand.START)) {
            is Transition.Rejected -> return CommandResult.Blocked(t.reason)
            is Transition.Allowed -> scheduleStore.update { s ->
                if (t.from == ScheduleState.STOPPED) {
                    // Fresh start: time spent stopped is not counted as missed.
                    s.copy(state = ScheduleState.RUNNING, lastHandledSlot = planner.baselineForStart(now), startedAtUtc = now, nextAttemptUtc = null)
                } else {
                    // Re-attaching after Android stopped the service: keep the last handled slot so the
                    // gap is recorded as missed attempts.
                    s.copy(state = ScheduleState.RUNNING)
                }
            }
        }
        return launchService("Capture schedule started")
    }

    fun pause(): CommandResult {
        when (val t = ScheduleStateMachine.apply(scheduleStore.current.state, ScheduleCommand.PAUSE)) {
            is Transition.Rejected -> return CommandResult.Blocked(t.reason)
            is Transition.Allowed -> scheduleStore.update { it.copy(state = ScheduleState.PAUSED, nextAttemptUtc = null) }
        }
        alarms.cancel()
        CameraCaptureService.send(appContext, CameraCaptureService.ACTION_REFRESH)
        diagnostics.log(DiagnosticType.SCHEDULE_CHANGED, "Capture schedule paused")
        return CommandResult.Ok
    }

    fun resume(): CommandResult {
        preconditionProblem()?.let { return it }
        when (val t = ScheduleStateMachine.apply(scheduleStore.current.state, ScheduleCommand.RESUME)) {
            is Transition.Rejected -> return CommandResult.Blocked(t.reason)
            is Transition.Allowed -> scheduleStore.update {
                it.copy(state = ScheduleState.RUNNING, lastHandledSlot = planner.baselineForStart(clock()), nextAttemptUtc = null)
            }
        }
        if (CameraCaptureService.isRunning.value && CameraCaptureService.send(appContext, CameraCaptureService.ACTION_START)) {
            diagnostics.log(DiagnosticType.SCHEDULE_CHANGED, "Capture schedule resumed")
            return CommandResult.Ok
        }
        return launchService("Capture schedule resumed")
    }

    fun stop() {
        scheduleStore.update { it.copy(state = ScheduleState.STOPPED, nextAttemptUtc = null) }
        alarms.cancel()
        projection.stopByUser()
        CameraCaptureService.stop(appContext)
        notifications.cancelServiceInterrupted()
        diagnostics.log(DiagnosticType.SCHEDULE_CHANGED, "Capture schedule stopped")
    }

    fun stopScreenSharing() {
        projection.stopByUser()
        refreshNotification()
    }

    fun refreshNotification() {
        if (CameraCaptureService.isRunning.value) {
            CameraCaptureService.send(appContext, CameraCaptureService.ACTION_REFRESH)
        }
    }

    /**
     * Called when the app becomes visible. If the schedule should be running but Android stopped the
     * service (process killed, reboot), restart it now that starting is allowed again.
     */
    fun ensureServiceRunningIfScheduled(): CommandResult {
        val state = scheduleStore.current.state
        if (state == ScheduleState.STOPPED || CameraCaptureService.isRunning.value) return CommandResult.Ok
        if (!deviceState.hasCameraPermission()) {
            return CommandResult.Blocked("Camera permission was removed, so capture cannot resume.")
        }
        return launchService("Capture service restarted after an interruption")
    }

    private fun preconditionProblem(): CommandResult.Blocked? {
        val s = settings.current
        return when {
            !s.onboardingCompleted -> CommandResult.Blocked("Finish the privacy setup first.")
            !s.anyCategoryEnabled -> CommandResult.Blocked("Turn on at least one capture type in Settings.")
            !deviceState.hasCameraPermission() -> CommandResult.Blocked("Camera permission is required to run the schedule.")
            vault.state.value !is VaultState.Ready ->
                CommandResult.Blocked("Encryption is not ready. Open Settings → Encryption & recovery.")
            else -> null
        }
    }

    private fun launchService(message: String): CommandResult = try {
        CameraCaptureService.startFromForeground(appContext)
        diagnostics.log(DiagnosticType.SCHEDULE_CHANGED, message)
        CommandResult.Ok
    } catch (e: Exception) {
        diagnostics.log(DiagnosticType.FOREGROUND_SERVICE_UNAVAILABLE, "The capture service could not be started", e.toString())
        CommandResult.Blocked("Android did not allow the capture service to start. Open the app and try again.")
    }
}

/** Handles the Pause / Resume / Stop / Stop sharing buttons in the notifications. */
class CaptureControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = CaptureGraph.get(context)
        when (intent.action) {
            ACTION_PAUSE -> graph.controller.pause()
            ACTION_RESUME -> {
                val result = graph.controller.resume()
                if (result is CommandResult.Blocked) graph.notifications.showServiceInterrupted(result.message)
            }
            ACTION_STOP -> graph.controller.stop()
            ACTION_STOP_SHARING -> graph.controller.stopScreenSharing()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.phonecapture.tracker.capture.control.PAUSE"
        const val ACTION_RESUME = "com.phonecapture.tracker.capture.control.RESUME"
        const val ACTION_STOP = "com.phonecapture.tracker.capture.control.STOP"
        const val ACTION_STOP_SHARING = "com.phonecapture.tracker.capture.control.STOP_SHARING"
    }
}
