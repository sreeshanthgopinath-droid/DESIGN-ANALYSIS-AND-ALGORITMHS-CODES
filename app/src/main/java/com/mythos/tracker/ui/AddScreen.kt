package com.mythos.tracker.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.day.FileStore
import com.mythos.tracker.work.Scheduler
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Things you add yourself: diary entries, call recordings and any other files. */
@Composable
fun AddScreen() {
    val context = LocalContext.current
    val dao = remember { TrackerDatabase.get(context).events() }
    val recent by dao.recentManual().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    fun importAll(uris: List<Uri>, category: String) {
        if (uris.isEmpty()) return
        status = "Adding ${uris.size} file(s)…"
        scope.launch {
            val names = uris.mapNotNull { FileStore.import(context, it, category) }
            status = if (names.isEmpty()) "Could not add the file(s)." else "Added: " + names.joinToString(", ")
            Scheduler.requestSync(context)
        }
    }

    val pickRecordings = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        importAll(it, "recording")
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        importAll(it, "auto")
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diary", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Write about your day") },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = text.isNotBlank(), onClick = {
            val body = text.trim()
            val now = System.currentTimeMillis()
            scope.launch {
                dao.insertAll(listOf(EventEntity(kind = "diary", occurredAtMs = now, sourceKey = "diary:$now",
                    payloadJson = JSONObject().put("text", body).toString())))
                text = ""
                status = "Diary entry saved."
                Scheduler.requestSync(context)
            }
        }) { Text("Save diary entry") }

        HorizontalDivider()
        Text("Add files", style = MaterialTheme.typography.titleLarge)
        Text(
            "Call recordings are placed on the day and time in their file name (for example Call_20261110_153012.m4a). " +
                "Other files use the time they were last changed.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { pickRecordings.launch(arrayOf("audio/*")) }) { Text("Add call recordings") }
        OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) { Text("Add diary or other files") }
        Text("You can also use Share → \"Save to Mythos\" from any app.", style = MaterialTheme.typography.bodySmall)
        if (status.isNotBlank()) Text(status)

        HorizontalDivider()
        Text("Recently added", style = MaterialTheme.typography.titleMedium)
        if (recent.isEmpty()) Text("Nothing yet.")
        val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
        recent.forEach { e ->
            val p = runCatching { JSONObject(e.payloadJson) }.getOrElse { JSONObject() }
            val title = when (e.kind) {
                "diary" -> "Diary"
                "file" -> (if (p.optString("category") == "recording") "Recording: " else "File: ") + p.optString("name")
                else -> p.optString("title").ifBlank { "Shared note" }
            }
            Text("${fmt.format(Date(e.occurredAtMs))} · $title", style = MaterialTheme.typography.labelLarge)
            val body = p.optString("text")
            if (body.isNotBlank()) Text(body.take(300), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
    }
}
