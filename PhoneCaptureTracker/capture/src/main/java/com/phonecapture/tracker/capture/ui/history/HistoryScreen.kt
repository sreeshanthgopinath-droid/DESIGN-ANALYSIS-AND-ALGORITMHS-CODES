@file:OptIn(ExperimentalMaterial3Api::class)

package com.phonecapture.tracker.capture.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.toAttemptInfo
import com.phonecapture.tracker.capture.ui.components.DayNavigator
import com.phonecapture.tracker.capture.ui.components.TagChip
import com.phonecapture.tracker.capture.ui.components.rememberCaptureGraph
import com.phonecapture.tracker.capture.ui.components.shareText
import com.phonecapture.tracker.capture.ui.gallery.storageLabel
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.capture.ui.theme.NumberFont
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.text.DayShareFormatter
import com.phonecapture.tracker.core.text.Formatters
import com.phonecapture.tracker.core.time.DayWindow
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

enum class HistoryFilter(val label: String, val matches: (CaptureRecordEntity) -> Boolean) {
    ALL("All", { true }),
    CAPTURED("Captured", { it.captureStatus == CaptureStatus.SUCCESS }),
    FAILED("Failed", { it.captureStatus == CaptureStatus.FAILED }),
    SKIPPED("Skipped", { it.captureStatus == CaptureStatus.SKIPPED }),
    UPLOAD_PENDING("Upload pending", { it.uploadStatus == UploadStatus.UPLOAD_PENDING }),
    UPLOAD_FAILED("Upload failed", { it.uploadStatus == UploadStatus.UPLOAD_FAILED }),
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val graph: CaptureGraph) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val _date = MutableStateFlow(LocalDate.now(zone))
    val date: StateFlow<LocalDate> = _date.asStateFlow()
    private val _filter = MutableStateFlow(HistoryFilter.ALL)
    val filter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    val records: StateFlow<List<CaptureRecordEntity>> = _date
        .flatMapLatest { graph.repository.observeDay(DayWindow.of(it, zone)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFilter(filter: HistoryFilter) {
        _filter.value = filter
    }

    fun previousDay() {
        _date.value = _date.value.minusDays(1)
    }

    fun nextDay() {
        if (_date.value.isBefore(LocalDate.now(zone))) _date.value = _date.value.plusDays(1)
    }

    fun shareText(): String =
        DayShareFormatter.format(_date.value, zone, records.value.map { it.toAttemptInfo() }, graph.schedule)
}

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val graph = rememberCaptureGraph()
    val vm: HistoryViewModel = viewModel { HistoryViewModel(graph) }
    val context = LocalContext.current
    val date by vm.date.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val visible = records.filter(filter.matches)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { DayNavigator(date, vm::previousDay, vm::nextDay) }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { vm.setFilter(f) },
                        label = { Text("${f.label} ${records.count(f.matches)}") },
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = { context.shareText(vm.shareText(), "Share day as text") }) {
                Text("Share day as text")
            }
        }
        if (visible.isEmpty()) {
            item {
                Text(
                    "Nothing recorded here yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(visible, key = { it.captureId }) { record -> TimelineRow(record) }
    }
}

@Composable
private fun TimelineRow(record: CaptureRecordEntity) {
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Formatters.time(record.capturedAtUtc, zone), fontFamily = NumberFont, style = MaterialTheme.typography.titleSmall)
            TagChip(record.category.shortName, CaptureColors.category(record.category))
        }
        val title = when (record.captureStatus) {
            CaptureStatus.SUCCESS -> if (record.category == CaptureCategory.SCREENSHOT) "Screenshot saved" else "Photo saved"
            CaptureStatus.FAILED -> "Not captured"
            CaptureStatus.SKIPPED -> "Skipped"
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = CaptureColors.status(record.captureStatus))
        if (record.captureStatus == CaptureStatus.SUCCESS) {
            val (label, color) = storageLabel(record)
            Text("${Formatters.bytes(record.imageSizeBytes)} · $label", style = MaterialTheme.typography.bodyMedium, color = color)
            record.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text(record.failureReason?.userMessage ?: "No reason recorded", style = MaterialTheme.typography.bodyMedium)
            record.failureDetail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        record.batteryPercent?.let {
            Text("Battery $it%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}
