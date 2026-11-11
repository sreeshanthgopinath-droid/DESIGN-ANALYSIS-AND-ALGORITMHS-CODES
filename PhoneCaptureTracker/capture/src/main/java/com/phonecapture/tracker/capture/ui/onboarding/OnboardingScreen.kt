package com.phonecapture.tracker.capture.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.security.VaultState
import com.phonecapture.tracker.capture.ui.account.RecoveryCodeDialog
import com.phonecapture.tracker.capture.ui.components.SectionCard
import com.phonecapture.tracker.capture.ui.components.rememberCaptureGraph
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit, informed consent before anything is captured: what is captured, how often, where it
 * is stored, the privacy of other people, and how to stop. Nothing starts automatically afterwards.
 */
@Composable
fun OnboardingScreen(modifier: Modifier = Modifier) {
    val graph = rememberCaptureGraph()
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var understood by rememberSaveable { mutableStateOf(false) }
    var ownPhone by rememberSaveable { mutableStateOf(false) }
    var permissionsAsked by rememberSaveable { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val vault by graph.vault.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsAsked = true
    }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PhoneCaptureTracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Step ${step + 1} of 3", color = MaterialTheme.colorScheme.onSurfaceVariant)

        when (step) {
            0 -> {
                SectionCard(title = "What this app does") {
                    Bullet("Every 30 minutes, while you have capture switched on, it tries to take one front-camera photo, one rear-camera photo and one screenshot of the entire screen.")
                    Bullet("A notification is shown the whole time, and Android shows its own camera and screen-sharing indicators. You can pause or stop at any time.")
                    Bullet("Images are encrypted (AES-256-GCM) on this phone. Optional cloud backup uploads only encrypted files to your own Firebase account.")
                    Bullet("No audio or video is recorded. No location, contacts or device identifiers are collected. Nothing is shared with third parties.")
                    Bullet("Android limits background camera and screen capture. Some attempts will fail, especially when the phone is locked. Failures are recorded honestly; nothing is faked.")
                }
                SectionCard(title = "Other people's privacy") {
                    Text(
                        "Photos and screenshots can capture other people, their faces, messages and private information. " +
                            "Use this app only on your own phone, tell people around you, and follow the laws where you live. " +
                            "Never install it on someone else's phone.",
                        color = CaptureColors.Failure,
                    )
                }
                CheckRow("I understand what is captured and how it is stored.", understood) { understood = it }
                CheckRow("This is my own phone and I will respect other people's privacy.", ownPhone) { ownPhone = it }
                Button(onClick = { step = 1 }, enabled = understood && ownPhone) { Text("Continue") }
            }
            1 -> {
                SectionCard(title = "Permissions") {
                    Bullet("Camera – required for the front and rear photos.")
                    Bullet("Notifications – shows that capture is active and lets you stop it.")
                    Bullet("Screen sharing is approved separately in Android's own dialog each time you start it.")
                    Bullet("No microphone or location permission is requested.")
                }
                Button(onClick = {
                    val permissions = buildList {
                        add(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                }) { Text("Grant permissions") }
                val cameraOk = graph.deviceState.hasCameraPermission()
                if (permissionsAsked) {
                    Text(
                        if (cameraOk) "Camera permission granted." else "Camera permission was not granted. You can allow it later in Settings.",
                        color = if (cameraOk) CaptureColors.Success else CaptureColors.Pending,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { step = 0 }) { Text("Back") }
                    Button(onClick = { step = 2 }) { Text("Continue") }
                }
            }
            else -> {
                SectionCard(title = "Encryption key & recovery code") {
                    Text(
                        "A random encryption key is created on this phone and protected by the Android Keystore. " +
                            "You also get a recovery code: write it down. It is the only way to decrypt cloud backups " +
                            "on a new phone.",
                    )
                    when (vault) {
                        is VaultState.Ready -> Text("Encryption key ready.", color = CaptureColors.Success)
                        else -> Unit
                    }
                    error?.let { Text(it, color = CaptureColors.Failure) }
                }
                if (vault !is VaultState.Ready) {
                    Button(onClick = {
                        scope.launch {
                            try {
                                recoveryCode = withContext(Dispatchers.Default) { graph.vault.setUpNewVault() }
                            } catch (e: Exception) {
                                error = "Could not create the key: ${e.message}"
                            }
                        }
                    }) { Text("Create my encryption key") }
                    OutlinedButton(onClick = { finish(graph) }) {
                        Text("I have a recovery code – restore later in Settings")
                    }
                } else {
                    Button(onClick = { finish(graph) }) { Text("Finish") }
                }
                OutlinedButton(onClick = { step = 1 }) { Text("Back") }
            }
        }
    }

    recoveryCode?.let { code ->
        RecoveryCodeDialog(code = code, onDone = {
            recoveryCode = null
            finish(graph)
        })
    }
}

private fun finish(graph: CaptureGraph) {
    graph.settings.update { it.copy(onboardingCompleted = true) }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•  ")
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text)
    }
}
