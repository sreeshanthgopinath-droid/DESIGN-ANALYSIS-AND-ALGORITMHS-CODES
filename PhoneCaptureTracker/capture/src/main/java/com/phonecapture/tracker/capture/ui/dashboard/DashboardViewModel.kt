package com.phonecapture.tracker.capture.ui.dashboard

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.cloud.CloudUser
import com.phonecapture.tracker.capture.cloud.UploadProgress
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.db.DiagnosticLogEntity
import com.phonecapture.tracker.capture.data.settings.CaptureSettings
import com.phonecapture.tracker.capture.data.settings.ScheduleSnapshot
import com.phonecapture.tracker.capture.data.toAttemptInfo
import com.phonecapture.tracker.capture.projection.ProjectionStatus
import com.phonecapture.tracker.capture.security.VaultState
import com.phonecapture.tracker.capture.service.CameraCaptureService
import com.phonecapture.tracker.capture.service.CommandResult
import com.phonecapture.tracker.core.policy.NetworkKind
import com.phonecapture.tracker.core.stats.DaySummaries
import com.phonecapture.tracker.core.stats.DaySummary
import com.phonecapture.tracker.core.text.DayShareFormatter
import com.phonecapture.tracker.core.time.DayWindow
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DeviceSnapshot(
    val batteryPercent: Int? = null,
    val cameraPermission: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val powerSaveMode: Boolean = false,
    val freeLocalBytes: Long = 0,
    val network: NetworkKind = NetworkKind.NONE,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(private val graph: CaptureGraph) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val started = SharingStarted.WhileSubscribed(5_000)

    private val _date = MutableStateFlow(LocalDate.now(zone))
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    val schedule: StateFlow<ScheduleSnapshot> = graph.scheduleStore.snapshot
    val serviceRunning: StateFlow<Boolean> = CameraCaptureService.isRunning
    val projection: StateFlow<ProjectionStatus> = graph.projection.status
    val settings: StateFlow<CaptureSettings> = graph.settings.settings
    val vault: StateFlow<VaultState> = graph.vault.state
    val cloudUser: StateFlow<CloudUser?> = graph.firebase.user
    val cloudConfigured: Boolean = graph.firebase.isConfigured
    val uploadProgress: StateFlow<UploadProgress?> = graph.uploadProgress.progress

    val dayRecords: StateFlow<List<CaptureRecordEntity>> = _date
        .flatMapLatest { graph.repository.observeDay(DayWindow.of(it, zone)) }
        .stateIn(viewModelScope, started, emptyList())

    val daySummary: StateFlow<DaySummary> = dayRecords
        .map { records -> DaySummaries.summarize(records.map { it.toAttemptInfo() }) }
        .stateIn(viewModelScope, started, DaySummary.EMPTY)

    val lastSuccess: StateFlow<CaptureRecordEntity?> =
        graph.repository.observeLastSuccess().stateIn(viewModelScope, started, null)
    val localBytes: StateFlow<Long> = graph.repository.observeLocalBytes().stateIn(viewModelScope, started, 0L)
    val cloudBytes: StateFlow<Long> = graph.repository.observeCloudBytes().stateIn(viewModelScope, started, 0L)
    val pendingUploads: StateFlow<Int> = graph.repository.observePendingUploads().stateIn(viewModelScope, started, 0)
    val failedUploads: StateFlow<Int> = graph.repository.observeFailedUploads().stateIn(viewModelScope, started, 0)
    val latestProblem: StateFlow<DiagnosticLogEntity?> =
        graph.diagnostics.observeLatestProblem().stateIn(viewModelScope, started, null)

    private val _device = MutableStateFlow(DeviceSnapshot())
    val device: StateFlow<DeviceSnapshot> = _device.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshDevice()
    }

    fun refreshDevice() {
        val d = graph.deviceState
        _device.value = DeviceSnapshot(
            batteryPercent = d.batteryPercent(),
            cameraPermission = d.hasCameraPermission(),
            notificationsAllowed = d.canPostNotifications(),
            exactAlarmsAllowed = d.canScheduleExactAlarms(),
            ignoringBatteryOptimizations = d.isIgnoringBatteryOptimizations(),
            powerSaveMode = d.isPowerSaveMode(),
            freeLocalBytes = d.freeLocalBytes(),
            network = d.networkKind(),
        )
    }

    /** Called when the app becomes visible: re-attach the service if Android had stopped it. */
    fun onAppVisible() {
        refreshDevice()
        report(graph.controller.ensureServiceRunningIfScheduled(), successMessage = null)
    }

    fun previousDay() {
        _date.value = _date.value.minusDays(1)
    }

    fun nextDay() {
        val today = LocalDate.now(zone)
        if (_date.value.isBefore(today)) _date.value = _date.value.plusDays(1)
    }

    fun start() = report(graph.controller.start(), "Capture started. The first capture runs now.")
    fun pause() = report(graph.controller.pause(), "Capture paused.")
    fun resume() = report(graph.controller.resume(), "Capture resumed.")

    fun stop() {
        graph.controller.stop()
        _message.value = "Capture stopped."
    }

    fun stopScreenSharing() {
        graph.controller.stopScreenSharing()
        _message.value = "Screen sharing stopped."
    }

    fun screenCaptureIntent(): Intent = graph.projection.createConsentIntent()

    fun onScreenCaptureResult(resultCode: Int, data: Intent?) {
        val started = graph.projection.onConsentResult(resultCode, data)
        graph.controller.refreshNotification()
        _message.value = if (started) "Screen sharing approved." else "Screen sharing was not approved."
    }

    fun retryFailedUploads() {
        viewModelScope.launch {
            val count = graph.repository.dao.resetFailedUploads()
            graph.uploads.enqueueNow(replace = true)
            _message.value = if (count == 0) "There are no failed uploads." else "$count upload(s) queued again."
        }
    }

    fun shareText(): String =
        DayShareFormatter.format(_date.value, zone, dayRecords.value.map { it.toAttemptInfo() }, graph.schedule)

    fun consumeMessage() {
        _message.value = null
    }

    private fun report(result: CommandResult, successMessage: String?) {
        _message.value = when (result) {
            CommandResult.Ok -> successMessage
            is CommandResult.Blocked -> result.message
        }
        refreshDevice()
    }
}
