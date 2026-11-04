package com.mythos.tracker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mythos.tracker.Permissions
import com.mythos.tracker.Prefs
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.work.Scheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TrackerScreen()
            }
        }
    }
}

@Composable
private fun TrackerScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val dao = remember { TrackerDatabase.get(context).events() }
    val pending by dao.pendingCount().collectAsState(initial = 0)

    // Re-read permission state every time the app returns to the foreground
    // (the user may have just changed it in system settings).
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val status = remember(tick) { Permissions.snapshot(context) }

    var serverUrl by remember { mutableStateOf(prefs.serverUrl) }
    var token by remember { mutableStateOf(prefs.token) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }
    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mythos Tracker", style = MaterialTheme.typography.headlineMedium)

        Text("Pending events: $pending")
        Text("Last collection: ${formatTime(prefs.lastCollectMs)}")
        Text("Last sync: ${formatTime(prefs.lastSyncMs)}")
        if (prefs.lastSyncResult.isNotBlank()) Text(prefs.lastSyncResult)

        HorizontalDivider()

        Text("Server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL, e.g. http://100.x.y.z:8787") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Access token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            prefs.serverUrl = serverUrl
            prefs.token = token
            Scheduler.requestSync(context)
        }) {
            Text("Save and sync")
        }

        HorizontalDivider()

        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        PermissionRow("Location", status.location) {
            locationLauncher.launch(Permissions.location)
        }
        if (status.location) {
            PermissionRow("Location all the time (needed for background tracking)", status.backgroundLocation) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        PermissionRow("Photos and videos (metadata only)", status.media) {
            mediaLauncher.launch(Permissions.mediaRequest)
        }
        PermissionRow("App usage access (opens system settings)", status.usage) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        PermissionRow("Unrestricted battery (keeps collection alive)", status.batteryUnrestricted) {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }

        HorizontalDivider()

        Button(onClick = { Scheduler.collectNow(context) }) {
            Text("Collect now")
        }
        OutlinedButton(onClick = { Scheduler.requestSync(context) }) {
            Text("Sync now")
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(if (granted) "✓ $label" else label, modifier = Modifier.weight(1f))
        if (!granted) {
            TextButton(onClick = onRequest) { Text("Allow") }
        }
    }
}

private fun formatTime(ms: Long): String =
    if (ms == 0L) "never" else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
