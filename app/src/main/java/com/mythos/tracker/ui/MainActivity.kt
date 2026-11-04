package com.mythos.tracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.mythos.tracker.Permissions
import com.mythos.tracker.Prefs
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.net.SyncClient
import com.mythos.tracker.work.Scheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startTab = intent?.getIntExtra("tab", 0) ?: 0
        setContent {
            MaterialTheme {
                TrackerScreen(startTab)
            }
        }
    }
}

@Composable
private fun TrackerScreen(startTab: Int) {
    var tab by remember { mutableIntStateOf(startTab) }
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            listOf("Home", "Diary", "Sources").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> HomeTab()
            1 -> DiaryTab()
            else -> SourcesTab()
        }
    }
}

@Composable
private fun HomeTab() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val dao = remember { TrackerDatabase.get(context).events() }
    val pending by dao.pendingCount().collectAsState(initial = 0)

    // Re-read permission state every time the app returns to the foreground.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Refresh every 2 seconds so results appear while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    val status = remember(tick) { Permissions.snapshot(context) }

    var serverUrl by remember { mutableStateOf(prefs.serverUrl) }
    var token by remember { mutableStateOf(prefs.token) }
    var notifPackages by remember { mutableStateOf(prefs.notificationPackages) }
    var recFolders by remember { mutableStateOf(prefs.recordingFolders) }
    var message by remember { mutableStateOf("") }
    var collecting by remember { mutableStateOf(false) }
    var collectStarted by remember { mutableLongStateOf(0L) }

    // Finish the "Collecting…" state when the background run has written its result.
    LaunchedEffect(tick) {
        if (collecting) {
            if (prefs.lastCollectMs >= collectStarted) {
                collecting = false
                message = "Collection finished. ${prefs.lastCollectSummary}"
            } else if (System.currentTimeMillis() - collectStarted > 600_000) {
                collecting = false
                message = "Collection did not finish. Tap Collect now again."
            } else if (prefs.collectProgress.isNotBlank()) {
                message = prefs.collectProgress
            }
        }
    }

    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }
    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mythos Tracker", style = MaterialTheme.typography.headlineMedium)

        Text("Pending events: $pending")
        Text("Last collection: ${formatTime(prefs.lastCollectMs)}")
        if (prefs.lastCollectSummary.isNotBlank()) Text("Found: ${prefs.lastCollectSummary}")
        Text("Last sync: ${formatTime(prefs.lastSyncMs)}")
        if (prefs.lastSyncResult.isNotBlank()) Text(prefs.lastSyncResult)

        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }

        HorizontalDivider()

        Text("Server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL, e.g. https://mythos-mind-xxxx.run.app") },
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
        OutlinedTextField(
            value = notifPackages,
            onValueChange = { notifPackages = it },
            label = { Text("Apps to record notifications and now-playing from (package names)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = recFolders,
            onValueChange = { recFolders = it },
            label = { Text("Upload audio files in folders containing") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            val url = serverUrl.trim().trimEnd('/')
            when {
                !(url.startsWith("https://") || url.startsWith("http://")) ->
                    message = "Server URL must start with https:// . Paste the full address from the deploy step."
                token.isBlank() ->
                    message = "Enter the access token from the deploy step."
                else -> {
                    prefs.serverUrl = url
                    prefs.token = token.trim()
                    prefs.notificationPackages = notifPackages.trim()
                    prefs.recordingFolders = recFolders.trim()
                    serverUrl = url
                    Scheduler.requestSync(context)
                    message = "Saved. Sending any pending data to the server now."
                }
            }
        }) {
            Text("Save and sync")
        }

        HorizontalDivider()

        Text("Permissions", style = MaterialTheme.typography.titleMedium)
        if (!status.sms || !status.usage || !status.notificationAccess) {
            Text(
                "If Allow does nothing or says \"Restricted setting\": tap Open settings, then ⋮ (top right) → " +
                    "Allow restricted settings, confirm, come back and tap Allow again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        PermissionRow("Location", status.location) {
            multiLauncher.launch(Permissions.location)
        }
        if (status.location) {
            PermissionRow("Location all the time (needed for background tracking)", status.backgroundLocation) {
                singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        PermissionRow("Photos and videos (metadata only)", status.media) {
            multiLauncher.launch(Permissions.mediaRequest)
        }
        PermissionRow("Audio files (call and voice recordings)", status.audio) {
            multiLauncher.launch(Permissions.audio)
        }
        PermissionRow("Call history", status.callLog) {
            multiLauncher.launch(Permissions.callLog)
        }
        PermissionRow("Bank and UPI messages (SMS)", status.sms) {
            multiLauncher.launch(Permissions.sms)
        }
        PermissionRow("App usage access", status.usage) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        PermissionRow("Notification access: YouTube, PhonePe, Essential Space", status.notificationAccess) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            PermissionRow("Nightly diary reminder (notifications)", status.postNotifications) {
                singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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

        Button(onClick = {
            collecting = true
            collectStarted = System.currentTimeMillis()
            message = "Collecting now. This can take up to a minute."
            Scheduler.collectNow(context)
        }) {
            Text(if (collecting) "Collecting… (tap to restart)" else "Collect now")
        }
        OutlinedButton(onClick = {
            message = if (prefs.serverUrl.isBlank()) {
                "No server yet. Fill in the Server URL and token above, then tap Save and sync."
            } else {
                "Sync started."
            }
            Scheduler.requestSync(context)
        }) {
            Text("Sync now")
        }
        Text(
            "To send a note or file to Mythos from another app, use Share and choose \"Save to Mythos\".",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DiaryTab() {
    val context = LocalContext.current
    val dao = remember { TrackerDatabase.get(context).events() }
    val entries by dao.recentDiary().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diary", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("How was your day? What mattered?") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = text.isNotBlank(),
            onClick = {
                val body = text.trim()
                val now = System.currentTimeMillis()
                scope.launch {
                    dao.insertAll(
                        listOf(
                            EventEntity(
                                kind = "diary",
                                occurredAtMs = now,
                                sourceKey = "diary:$now",
                                payloadJson = JSONObject().put("text", body).toString(),
                            ),
                        ),
                    )
                    Scheduler.requestSync(context)
                    text = ""
                    saved = "Entry saved."
                }
            },
        ) { Text("Save entry") }
        if (saved.isNotBlank()) Text(saved)

        HorizontalDivider()
        Text("Recent entries", style = MaterialTheme.typography.titleMedium)
        if (entries.isEmpty()) Text("Nothing yet.")
        entries.forEach { e ->
            Text(formatTime(e.occurredAtMs), style = MaterialTheme.typography.labelMedium)
            Text(runCatching { JSONObject(e.payloadJson).optString("text") }.getOrDefault(""))
            HorizontalDivider()
        }
    }
}

@Composable
private fun SourcesTab() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("Loading…") }
    var rows by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(reload) {
        message = "Loading…"
        rows = emptyList()
        if (prefs.serverUrl.isBlank()) {
            message = "No server yet. Enter the server URL and token on the Home tab first."
            return@LaunchedEffect
        }
        val result = SyncClient(prefs).status()
        result.onSuccess { body ->
            val json = JSONObject(body)
            val arr = json.getJSONArray("sources")
            rows = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val last = s.optLong("last_event_ms", 0L)
                "${s.getString("source")}: ${s.getString("state")} · ${s.getInt("count")} items" +
                    if (last > 0) " · last ${formatTime(last)}" else ""
            }
            message = when {
                !json.optBoolean("google_configured") -> "Google pickup: not configured on the server"
                json.optBoolean("google_connected") -> "Google pickup: connected"
                else -> "Google pickup: not connected (open the server page in a browser)"
            }
        }.onFailure {
            message = "Could not reach the server: ${it.message}"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Sources on the server", style = MaterialTheme.typography.headlineMedium)
        Text(message)
        rows.forEach {
            Text(it)
            HorizontalDivider()
        }
        OutlinedButton(onClick = { reload++ }) { Text("Refresh") }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(if (granted) "✓ $label" else label)
        if (!granted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRequest) { Text("Allow") }
                // Android ignores "Allow" once a permission was refused twice; this always works.
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

private fun formatTime(ms: Long): String =
    if (ms == 0L) "never" else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
