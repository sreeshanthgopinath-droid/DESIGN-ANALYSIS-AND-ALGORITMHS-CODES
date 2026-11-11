package com.phonecapture.tracker.capture.ui.dashboard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonecapture.tracker.capture.cloud.CloudUser
import com.phonecapture.tracker.capture.cloud.UploadProgress
import com.phonecapture.tracker.capture.data.settings.CaptureSettings
import com.phonecapture.tracker.capture.projection.ProjectionStatus
import com.phonecapture.tracker.capture.ui.components.DayNavigator
import com.phonecapture.tracker.capture.ui.components.EncryptedThumbnail
import com.phonecapture.tracker.capture.ui.components.RecordingDot
import com.phonecapture.tracker.capture.ui.components.SectionCard
import com.phonecapture.tracker.capture.ui.components.StatGrid
import com.phonecapture.tracker.capture.ui.components.StatusRow
import com.phonecapture.tracker.capture.ui.components.rememberCaptureGraph
import com.phonecapture.tracker.capture.ui.components.shareText
import com.phonecapture.tracker.capture.ui.components.startActivitySafely
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.ScheduleState
import com.phonecapture.tracker.core.policy.CloudQuotaState
import com.phonecapture.tracker.core.policy.LocalStorageState
import com.phonecapture.tracker.core.policy.StoragePolicy
import com.phonecapture.tracker.core.text.Formatters
import java.time.ZoneId

@Composable
fun DashboardScreen(
    onOpenGallery: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = rememberCaptureGraph()
    val vm: DashboardViewModel = viewModel { DashboardViewModel(graph) }
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()

    val date by vm.date.collectAsStateWithLifecycle()
    val schedule by vm.schedule.collectAsStateWithLifecycle()
    val serviceRunning by vm.serviceRunning.collectAsStateWithLifecycle()
    val projection by vm.projection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val summary by vm.daySummary.collectAsStateWithLifecycle()
    val records by vm.dayRecords.collectAsStateWithLifecycle()
    val lastSuccess by vm.lastSuccess.collectAsStateWithLifecycle()
    val localBytes by vm.localBytes.collectAsStateWithLifecycle()
    val cloudBytes by vm.cloudBytes.collectAsStateWithLifecycle()
    val pending by vm.pendingUploads.collectAsStateWithLifecycle()
    val failedUploads by vm.failedUploads.collectAsStateWithLifecycle()
    val problem by vm.latestProblem.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val cloudUser by vm.cloudUser.collectAsStateWithLifecycle()
    val progress by vm.uploadProgress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.onAppVisible()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            onShowMessage(it)
            vm.consumeMessage()
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onScreenCaptureResult(result.resultCode, result.data)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refreshDevice()
    }

    val successes = records.filter { it.captureStatus == CaptureStatus.SUCCESS }.sortedByDescending { it.capturedAtUtc }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DayNavigator(date, vm::previousDay, vm::nextDay) }

        item {
            CaptureBanner(
                state = schedule.state,
                serviceRunning = serviceRunning,
                nextAttempt = schedule.nextAttemptUtc?.let { Formatters.shortTime(it, zone) },
                screenSharing = projection is ProjectionStatus.Active,
            )
        }

        item {
            SectionCard {
                StatGrid(
                    listOf(
                        summary.scheduledAttempts.toString() to "Scheduled",
                        summary.successful.toString() to "Successful",
                        summary.failed.toString() to "Failed",
                        summary.frontCount.toString() to "Front",
                        summary.rearCount.toString() to "Rear",
                        summary.screenshotCount.toString() to "Screenshots",
                        summary.uploaded.toString() to "Uploaded",
                        summary.pendingUploads.toString() to "Pending upload",
                        summary.skipped.toString() to "Skipped",
                    ),
                )
            }
        }

        item {
            SectionCard(title = "Controls") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (schedule.state) {
                        ScheduleState.STOPPED -> Button(onClick = vm::start) { Text("Start capture") }
                        ScheduleState.RUNNING -> {
                            if (serviceRunning) {
                                OutlinedButton(onClick = vm::pause) { Text("Pause") }
                            } else {
                                Button(onClick = vm::start) { Text("Resume capture") }
                            }
                            OutlinedButton(onClick = vm::stop) { Text("Stop") }
                        }
                        ScheduleState.PAUSED -> {
                            Button(onClick = vm::resume) { Text("Resume") }
                            OutlinedButton(onClick = vm::stop) { Text("Stop") }
                        }
                    }
                }
                if (settings.captureScreen) {
                    if (projection is ProjectionStatus.Active) {
                        OutlinedButton(onClick = vm::stopScreenSharing) { Text("Stop screen sharing") }
                    } else {
                        FilledTonalButton(onClick = { consentLauncher.launch(vm.screenCaptureIntent()) }) {
                            Text("Start screen sharing")
                        }
                    }
                    Text(
                        "Android asks for permission for each screen-sharing session and ends it when the phone locks " +
                            "(Android 15 QPR1 and later). Screenshots then fail until you start sharing again here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (failedUploads > 0) {
                    OutlinedButton(onClick = vm::retryFailedUploads) { Text("Retry failed uploads ($failedUploads)") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenGallery) { Text("Gallery") }
                    OutlinedButton(onClick = onOpenHistory) { Text("History") }
                    OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
        }

        item {
            SectionCard(title = "Status") {
                StatusRow("Capture service", serviceText(schedule.state, serviceRunning), serviceColor(schedule.state, serviceRunning))
                StatusRow("Screen sharing", projectionText(projection, zone), projectionColor(projection))
                StatusRow(
                    "Camera permission",
                    if (device.cameraPermission) "Granted" else "Not granted",
                    if (device.cameraPermission) CaptureColors.Success else CaptureColors.Failure,
                    actionLabel = if (device.cameraPermission) null else "Grant",
                    onAction = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                )
                StatusRow("Cloud sync", cloudText(vm.cloudConfigured, settings, cloudUser, progress, pending))
                UploadProgressBar(progress)
                StatusRow("Next scheduled attempt", if (schedule.state == ScheduleState.RUNNING) schedule.nextAttemptUtc?.let { Formatters.shortTime(it, zone) } ?: "Now" else "—")
                StatusRow(
                    "Last successful capture",
                    lastSuccess?.let { "${Formatters.dateTime(it.capturedAtUtc, zone)} · ${it.category.shortName}" } ?: "None yet",
                )
                StatusRow(
                    "Battery",
                    (device.batteryPercent?.let { "$it%" } ?: "Unknown") + if (device.powerSaveMode) " · Battery saver on" else "",
                )
                val localState = StoragePolicy.localState(device.freeLocalBytes)
                StatusRow(
                    "Local storage used",
                    "${Formatters.bytes(localBytes)} · ${Formatters.bytes(device.freeLocalBytes)} free",
                    if (localState == LocalStorageState.OK) Color.Unspecified else CaptureColors.Failure,
                )
                val quotaState = StoragePolicy.cloudState(cloudBytes, settings.cloudQuotaBytes)
                StatusRow(
                    "Cloud storage used",
                    "${Formatters.bytes(cloudBytes)} of ${Formatters.bytes(settings.cloudQuotaBytes)}" + when (quotaState) {
                        CloudQuotaState.OK -> ""
                        CloudQuotaState.WARNING -> " · nearly full"
                        CloudQuotaState.EXHAUSTED -> " · limit reached, uploads paused"
                    },
                    if (quotaState == CloudQuotaState.OK) Color.Unspecified else CaptureColors.Pending,
                )
                if (!device.exactAlarmsAllowed) {
                    StatusRow(
                        "Exact timing",
                        "Not allowed – attempts may drift while the phone sleeps",
                        CaptureColors.Pending,
                        actionLabel = "Allow",
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.startActivitySafely(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                                )
                            }
                        },
                    )
                }
                if (!device.notificationsAllowed) {
                    StatusRow(
                        "Notifications",
                        "Blocked – the capture indicator cannot be shown",
                        CaptureColors.Failure,
                        actionLabel = "Open",
                        onAction = {
                            context.startActivitySafely(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        },
                    )
                }
            }
        }

        problem?.let { p ->
            item {
                SectionCard(title = "Latest problem") {
                    Text(p.type.label, fontWeight = FontWeight.Medium, color = CaptureColors.Failure)
                    Text("${Formatters.dateTime(p.timestampUtc, zone)} — ${p.message}")
                    p.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = onOpenDiagnostics) { Text("Open diagnostics") }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Captures (${successes.size})", style = MaterialTheme.typography.titleLarge)
                if (successes.isEmpty()) {
                    Text("No images captured on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(successes, key = { it.captureId }) { record ->
                            EncryptedThumbnail(record, Modifier.size(110.dp).clickable(onClick = onOpenGallery))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { context.shareText(vm.shareText(), "Share day as text") }) {
                        Text("Share day as text")
                    }
                    OutlinedButton(onClick = onOpenDiagnostics) { Text("Diagnostics") }
                }
            }
        }
    }
}

@Composable
private fun CaptureBanner(state: ScheduleState, serviceRunning: Boolean, nextAttempt: String?, screenSharing: Boolean) {
    val active = state == ScheduleState.RUNNING && serviceRunning
    val (title, subtitle) = when {
        active -> "Capturing every 30 minutes" to listOfNotNull(
            nextAttempt?.let { "Next attempt $it" },
            if (screenSharing) "screen sharing on" else "screen sharing off",
        ).joinToString(" · ")
        state == ScheduleState.RUNNING -> "Interrupted" to "Android stopped the capture service. Tap Resume capture."
        state == ScheduleState.PAUSED -> "Paused" to "No captures are taken until you resume."
        else -> "Not capturing" to "Tap Start capture to begin the 30-minute schedule."
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordingDot(active = active, size = 16.dp)
            Column(Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun serviceText(state: ScheduleState, running: Boolean): String = when (state) {
    ScheduleState.RUNNING -> if (running) "Running" else "Interrupted (open the app to resume)"
    ScheduleState.PAUSED -> "Paused"
    ScheduleState.STOPPED -> "Stopped"
}

private fun serviceColor(state: ScheduleState, running: Boolean) = when {
    state == ScheduleState.RUNNING && running -> CaptureColors.Success
    state == ScheduleState.RUNNING -> CaptureColors.Failure
    state == ScheduleState.PAUSED -> CaptureColors.Pending
    else -> CaptureColors.Skipped
}

private fun projectionText(status: ProjectionStatus, zone: ZoneId): String = when (status) {
    is ProjectionStatus.Active -> "Active since ${Formatters.shortTime(status.sinceUtc, zone)} (${status.widthPx}×${status.heightPx})"
    is ProjectionStatus.Ended -> "Ended ${Formatters.shortTime(status.atUtc, zone)}: ${status.detail}"
    ProjectionStatus.NotStarted -> "Not active"
}

private fun projectionColor(status: ProjectionStatus) = when (status) {
    is ProjectionStatus.Active -> CaptureColors.Success
    is ProjectionStatus.Ended -> CaptureColors.Failure
    ProjectionStatus.NotStarted -> CaptureColors.Skipped
}

private fun cloudText(
    configured: Boolean,
    settings: CaptureSettings,
    user: CloudUser?,
    progress: UploadProgress?,
    pending: Int,
): String = when {
    !configured -> "Local-only mode (Firebase not set up in this build)"
    !settings.cloudSyncEnabled -> "Off"
    user == null -> "On, but not signed in – uploads are waiting"
    progress != null -> "Uploading ${progress.percent}% · ${progress.remaining} left"
    pending > 0 -> "On · $pending waiting (${if (settings.allowMobileDataUpload) "any network" else "Wi-Fi only"})"
    else -> "On · up to date"
}

/** Thin bar under the cloud-sync row while an upload is in progress. */
@Composable
fun UploadProgressBar(progress: UploadProgress?) {
    if (progress != null) {
        LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
    }
}
