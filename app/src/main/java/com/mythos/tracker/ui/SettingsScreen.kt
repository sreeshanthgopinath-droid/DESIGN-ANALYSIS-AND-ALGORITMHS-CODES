package com.mythos.tracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mythos.tracker.LocationService
import com.mythos.tracker.Permissions
import com.mythos.tracker.Prefs
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val dao = remember { TrackerDatabase.get(context).events() }
    val total by dao.totalCount().collectAsState(initial = 0)
    val pending by dao.pendingCount().collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000); tick++
        }
    }
    val status = remember(tick) { Permissions.snapshot(context) }

    var message by remember { mutableStateOf("") }
    var collecting by remember { mutableStateOf(false) }
    var collectStarted by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tick) {
        if (collecting) {
            if (prefs.lastCollectMs >= collectStarted) {
                collecting = false
                message = "Done. Found: ${prefs.lastCollectSummary}"
            } else if (prefs.collectProgress.isNotBlank()) {
                message = prefs.collectProgress
            }
        }
    }

    var paymentApps by remember { mutableStateOf(prefs.paymentApps) }
    var mediaApps by remember { mutableStateOf(prefs.mediaApps) }
    var serverUrl by remember { mutableStateOf(prefs.serverUrl) }
    var token by remember { mutableStateOf(prefs.token) }

    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = "Exporting…"
            val n = withContext(Dispatchers.IO) { exportAll(context, uri) }
            message = if (n >= 0) "Exported $n records." else "Export failed."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        val lastLoc = prefs.lastLocationMs
        Text("Stored on this phone: $total records")
        Text("Last location reading: ${if (lastLoc == 0L) "none yet" else fmt(lastLoc)}")
        Text("Last background check: ${if (prefs.lastCollectMs == 0L) "never" else fmt(prefs.lastCollectMs)}")
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)

        HorizontalDivider()
        Text("What Mythos can read", style = MaterialTheme.typography.titleMedium)
        val restricted = !status.sms || !status.usage || !status.notificationAccess || !status.accessibility
        if (restricted) {
            Text(
                "If Allow does nothing or says \"Restricted setting\": tap Open settings, then ⋮ (top right) → " +
                    "Allow restricted settings, confirm, come back and tap Allow again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Perm("1. App usage, to the second (screen time)", status.usage) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        Perm("2. YouTube videos + 4. PhonePe payments (notification access)", status.notificationAccess) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        Perm("3. Chrome sites and Google/YouTube searches (Accessibility → Mythos Tracker → On)", status.accessibility) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        Perm("4. Bank and UPI SMS (backup for payments)", status.sms) { multi.launch(Permissions.sms) }
        Perm("7. Photos and videos", status.media) { multi.launch(Permissions.mediaRequest) }
        Perm("8. Location", status.location) { multi.launch(Permissions.location) }
        if (status.location) {
            Perm("8. Location all the time", status.backgroundLocation) {
                single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Perm("Tracking notification (keeps 15-minute location running)", status.postNotifications) {
                single.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        Perm("Call history", status.callLog) { multi.launch(Permissions.callLog) }
        Perm("Unrestricted battery", status.batteryUnrestricted) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
        }

        HorizontalDivider()
        Button(onClick = {
            collecting = true
            collectStarted = System.currentTimeMillis()
            message = "Checking everything now…"
            LocationService.start(context)
            Scheduler.collectNow(context)
        }) { Text(if (collecting) "Checking… (tap to restart)" else "Check everything now") }
        OutlinedButton(onClick = {
            export.launch("mythos-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json")
        }) { Text("Export all data (JSON)") }

        HorizontalDivider()
        Text("Apps", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = paymentApps, onValueChange = { paymentApps = it },
            label = { Text("Payment apps (package names)") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = mediaApps, onValueChange = { mediaApps = it },
            label = { Text("Video and music apps (package names)") }, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            prefs.paymentApps = paymentApps.trim()
            prefs.mediaApps = mediaApps.trim()
            message = "Saved."
        }) { Text("Save apps") }

        HorizontalDivider()
        Text("Backup server (optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Everything works on the phone without this. Fill it in only if you deploy the Mythos server.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Waiting to upload: $pending · Last upload: ${if (prefs.lastSyncMs == 0L) "never" else fmt(prefs.lastSyncMs)}")
        OutlinedTextField(
            value = serverUrl, onValueChange = { serverUrl = it },
            label = { Text("Server URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, label = { Text("Access token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = {
            val url = serverUrl.trim().trimEnd('/')
            message = when {
                url.isEmpty() && token.isBlank() -> {
                    prefs.serverUrl = ""; prefs.token = ""; "Backup server turned off."
                }
                !url.startsWith("https://") && !url.startsWith("http://") -> "Server URL must start with https://"
                token.isBlank() -> "Enter the access token."
                else -> {
                    prefs.serverUrl = url; prefs.token = token.trim(); Scheduler.requestSync(context)
                    "Saved. Uploading in the background."
                }
            }
        }) { Text("Save server") }
    }
}

@Composable
private fun Perm(label: String, granted: Boolean, onRequest: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(if (granted) "✓ $label" else "✗ $label",
            color = if (granted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        if (!granted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRequest) { Text("Allow") }
                TextButton(onClick = { openAppSettings(context) }) { Text("Open settings") }
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun fmt(t: Long) = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(t))

private suspend fun exportAll(context: Context, uri: Uri): Int {
    val dao = TrackerDatabase.get(context).events()
    var n = 0
    return runCatching {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.write("[\n")
            var after = 0L
            var first = true
            while (true) {
                val page = dao.page(after, 1000)
                if (page.isEmpty()) break
                for (e in page) {
                    val o = JSONObject().put("kind", e.kind).put("occurred_at_ms", e.occurredAtMs)
                        .put("source_key", e.sourceKey)
                        .put("payload", runCatching { JSONObject(e.payloadJson) }.getOrElse { JSONObject() })
                    if (!first) w.write(",\n")
                    w.write(o.toString())
                    first = false
                    n++
                }
                after = page.last().id
            }
            w.write("\n]\n")
        } ?: return -1
        n
    }.getOrDefault(-1)
}
