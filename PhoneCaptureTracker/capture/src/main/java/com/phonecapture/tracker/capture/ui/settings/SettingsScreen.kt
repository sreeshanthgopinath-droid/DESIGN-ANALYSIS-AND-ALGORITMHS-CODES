@file:OptIn(ExperimentalMaterial3Api::class)

package com.phonecapture.tracker.capture.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonecapture.tracker.capture.ui.account.AccountSection
import com.phonecapture.tracker.capture.ui.account.ConfirmDialog
import com.phonecapture.tracker.capture.ui.account.EncryptionSection
import com.phonecapture.tracker.capture.ui.account.PasswordConfirmDialog
import com.phonecapture.tracker.capture.ui.account.RecoveryCodeDialog
import com.phonecapture.tracker.capture.ui.components.SectionCard
import com.phonecapture.tracker.capture.ui.components.StatusRow
import com.phonecapture.tracker.capture.ui.components.rememberCaptureGraph
import com.phonecapture.tracker.capture.ui.components.startActivitySafely
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.core.text.Formatters
import kotlin.math.roundToInt

private enum class PendingConfirm { DELETE_LOCAL, DELETE_CLOUD, DELETE_ACCOUNT }

@Composable
fun SettingsScreen(onOpenDiagnostics: () -> Unit, onShowMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val graph = rememberCaptureGraph()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(graph) }
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val vault by vm.vault.collectAsStateWithLifecycle()
    val user by vm.cloudUser.collectAsStateWithLifecycle()
    val keyring by vm.keyringState.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val recoveryCode by vm.recoveryCode.collectAsStateWithLifecycle()
    val localBytes by vm.localBytes.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }

    var deviceTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        deviceTick++
        onPauseOrDispose { }
    }
    val device = graph.deviceState
    val cameraGranted = remember(deviceTick) { device.hasCameraPermission() }
    val notificationsAllowed = remember(deviceTick) { device.canPostNotifications() }
    val exactAlarms = remember(deviceTick) { device.canScheduleExactAlarms() }
    val batteryUnrestricted = remember(deviceTick) { device.isIgnoringBatteryOptimizations() }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { deviceTick++ }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { deviceTick++ }

    LaunchedEffect(message) {
        message?.let {
            onShowMessage(it)
            vm.consumeMessage()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

        item {
            SectionCard(title = "What is captured") {
                SwitchRow("Front camera", "One photo every 30 minutes", settings.captureFront) { v -> vm.update { it.copy(captureFront = v) } }
                SwitchRow("Rear camera", "Main rear camera, one photo every 30 minutes", settings.captureRear) { v -> vm.update { it.copy(captureRear = v) } }
                SwitchRow("Entire screen", "Needs an approved screen-sharing session", settings.captureScreen) { v -> vm.update { it.copy(captureScreen = v) } }
                var quality by remember(settings.jpegQuality) { mutableFloatStateOf(settings.jpegQuality.toFloat()) }
                Text("JPEG quality: ${quality.roundToInt()} (85 = balanced)", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    valueRange = 60f..95f,
                    steps = 6,
                    onValueChangeFinished = { vm.update { it.copy(jpegQuality = quality.roundToInt()) } },
                )
                Text("Maximum image size (long edge)", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf(1920, 2560, 3264, 4096), settings.maxImageEdgePx, { "$it px" }) { v ->
                    vm.update { it.copy(maxImageEdgePx = v) }
                }
            }
        }

        item {
            SectionCard(title = "Permissions & reliability") {
                StatusRow(
                    "Camera",
                    if (cameraGranted) "Allowed" else "Not allowed",
                    if (cameraGranted) CaptureColors.Success else CaptureColors.Failure,
                    actionLabel = if (cameraGranted) "App info" else "Allow",
                    onAction = {
                        if (cameraGranted) {
                            context.startActivitySafely(appDetailsIntent(context.packageName))
                        } else {
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                StatusRow(
                    "Notifications",
                    if (notificationsAllowed) "Allowed" else "Blocked – capture status cannot be shown",
                    if (notificationsAllowed) CaptureColors.Success else CaptureColors.Failure,
                    actionLabel = if (notificationsAllowed) null else "Allow",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            context.startActivitySafely(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    },
                )
                StatusRow(
                    "Exact timing (Alarms & reminders)",
                    if (exactAlarms) "Allowed" else "Not allowed – attempts may start late while the phone sleeps",
                    if (exactAlarms) CaptureColors.Success else CaptureColors.Pending,
                    actionLabel = if (exactAlarms || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null else "Allow",
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            context.startActivitySafely(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    },
                )
                StatusRow(
                    "Battery optimisation",
                    if (batteryUnrestricted) "Unrestricted" else "Optimised – Android may stop the schedule",
                    if (batteryUnrestricted) CaptureColors.Success else CaptureColors.Pending,
                    actionLabel = "Open",
                    onAction = { context.startActivitySafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                )
                Text(
                    "Even with every permission, Android can still delay or stop background work (battery saver, " +
                        "low memory, reboots). Missed attempts are recorded, never filled in afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            SectionCard(title = "Cloud backup (Firebase)") {
                if (!vm.cloudConfigured) {
                    Text(
                        "This build has no Firebase configuration, so the app runs in local-only mode. " +
                            "Add app/google-services.json and rebuild to enable encrypted cloud backup (see docs/FIREBASE_SETUP.md).",
                    )
                } else {
                    AccountSection(
                        user = user,
                        keyringState = keyring,
                        busy = busy,
                        onSignIn = vm::signIn,
                        onRegister = vm::register,
                        onResetPassword = vm::resetPassword,
                        onSignOut = vm::signOut,
                        onCheckKeyring = vm::syncKeyringNow,
                    )
                    SwitchRow("Upload captures", "Encrypted on this phone before upload", settings.cloudSyncEnabled) { v ->
                        vm.update { it.copy(cloudSyncEnabled = v) }
                    }
                    SwitchRow("Allow mobile data", "Off = Wi-Fi / unmetered networks only", settings.allowMobileDataUpload, enabled = settings.cloudSyncEnabled) { v ->
                        vm.update { it.copy(allowMobileDataUpload = v) }
                    }
                    SwitchRow(
                        "Free phone space after verified upload",
                        "Deletes the full-size phone copy once the cloud copy is verified (thumbnail kept)",
                        settings.deleteLocalAfterVerifiedUpload,
                        enabled = settings.cloudSyncEnabled,
                    ) { v -> vm.update { it.copy(deleteLocalAfterVerifiedUpload = v) } }
                    Text("Cloud storage limit (warnings at 80%)", style = MaterialTheme.typography.labelLarge)
                    val gb = 1024L * 1024 * 1024
                    ChoiceChips(listOf(1 * gb, 5 * gb, 10 * gb, 50 * gb, 100 * gb), settings.cloudQuotaBytes, { Formatters.bytes(it) }) { v ->
                        vm.update { it.copy(cloudQuotaBytes = v) }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Encryption & recovery") {
                EncryptionSection(
                    vault = vault,
                    signedIn = user != null,
                    busy = busy,
                    onCreate = vm::setUpEncryption,
                    onRegenerate = vm::regenerateRecoveryCode,
                    onRestoreFromCloud = vm::restoreFromCloud,
                    onUnlockLocal = vm::restoreFromLocalKeyring,
                )
            }
        }

        item {
            SectionCard(title = "Storage & retention") {
                Text("Encrypted images on this phone: ${Formatters.bytes(localBytes)}")
                val retention = listOf(0, 7, 30, 90, 365)
                val label: (Int) -> String = { if (it == 0) "Keep forever" else "$it days" }
                Text("Keep on this phone", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(retention, settings.localRetentionDays, label) { v -> vm.update { it.copy(localRetentionDays = v) } }
                Text("Keep in the cloud", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(retention, settings.cloudRetentionDays, label) { v -> vm.update { it.copy(cloudRetentionDays = v) } }
                Text(
                    "While cloud backup is on, a phone copy is only removed after its cloud copy has been verified.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = vm::applyRetentionNow, enabled = !busy) { Text("Run clean-up now") }
            }
        }

        item {
            SectionCard(title = "Privacy & data") {
                Text(
                    "Photos and screenshots can show other people and their private information. Only use this app " +
                        "on your own phone, tell people around you, and follow the law where you live. Nothing is " +
                        "shared with third parties; there is no hidden capture mode.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { confirm = PendingConfirm.DELETE_LOCAL }, enabled = !busy) {
                    Text("Delete all data on this phone")
                }
                if (vm.cloudConfigured && user != null) {
                    OutlinedButton(onClick = { confirm = PendingConfirm.DELETE_CLOUD }, enabled = !busy) {
                        Text("Delete all cloud data")
                    }
                    OutlinedButton(onClick = { confirm = PendingConfirm.DELETE_ACCOUNT }, enabled = !busy) {
                        Text("Delete cloud account")
                    }
                }
            }
        }

        item {
            SectionCard(title = "Diagnostics") {
                Text("Every failure, interruption and upload problem is logged here with a reason.")
                OutlinedButton(onClick = onOpenDiagnostics) { Text("Open diagnostic log") }
            }
        }
    }

    recoveryCode?.let { code -> RecoveryCodeDialog(code = code, onDone = vm::dismissRecoveryCode) }

    when (confirm) {
        PendingConfirm.DELETE_LOCAL -> ConfirmDialog(
            title = "Delete all data on this phone?",
            message = "All encrypted images, capture records and diagnostic logs on this phone are deleted. " +
                "Cloud copies are not affected.",
            confirmLabel = "Delete",
            onConfirm = {
                confirm = null
                vm.deleteAllLocalData()
            },
            onDismiss = { confirm = null },
        )
        PendingConfirm.DELETE_CLOUD -> ConfirmDialog(
            title = "Delete all cloud data?",
            message = "Every uploaded image, its metadata and your recovery keyring are deleted from Firebase. " +
                "Cloud backup is turned off. This cannot be undone.",
            confirmLabel = "Delete cloud data",
            onConfirm = {
                confirm = null
                vm.deleteAllCloudData()
            },
            onDismiss = { confirm = null },
        )
        PendingConfirm.DELETE_ACCOUNT -> PasswordConfirmDialog(
            title = "Delete cloud account?",
            message = "All cloud data is deleted, then the account itself. Enter your password to confirm.",
            confirmLabel = "Delete account",
            onConfirm = { password ->
                confirm = null
                vm.deleteAccount(password)
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

private fun appDetailsIntent(packageName: String) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
