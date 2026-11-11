package com.phonecapture.tracker.capture.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecapture.tracker.capture.ui.components.TagChip
import com.phonecapture.tracker.capture.ui.components.rememberCaptureGraph
import com.phonecapture.tracker.capture.ui.components.shareText
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.capture.ui.theme.NumberFont
import com.phonecapture.tracker.core.model.Severity
import com.phonecapture.tracker.core.text.Formatters
import java.time.ZoneId
import kotlinx.coroutines.launch

@Composable
fun DiagnosticsScreen(onShowMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val graph = rememberCaptureGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val logsFlow = remember(graph) { graph.diagnostics.observeRecent(500) }
    val logs by logsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { context.shareText(graph.diagnostics.exportText(zone), "Share diagnostic log") }
                }) { Text("Share log") }
                OutlinedButton(onClick = {
                    scope.launch {
                        graph.diagnostics.clear()
                        onShowMessage("Diagnostic log cleared.")
                    }
                }) { Text("Clear log") }
            }
        }
        if (logs.isEmpty()) {
            item { Text("No diagnostic entries.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(logs, key = { it.id }) { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Formatters.dateTime(entry.timestampUtc, zone), fontFamily = NumberFont, style = MaterialTheme.typography.labelMedium)
                    TagChip(
                        entry.severity.name,
                        when (entry.severity) {
                            Severity.INFO -> CaptureColors.Skipped
                            Severity.WARNING -> CaptureColors.Pending
                            Severity.ERROR -> CaptureColors.Failure
                        },
                    )
                }
                Text(entry.type.label, style = MaterialTheme.typography.titleSmall)
                Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                entry.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}
