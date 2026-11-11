@file:OptIn(ExperimentalMaterial3Api::class)

package com.phonecapture.tracker.capture.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.hasCloudCopy
import com.phonecapture.tracker.capture.data.hasLocalImage
import com.phonecapture.tracker.capture.ui.components.EncryptedThumbnail
import com.phonecapture.tracker.capture.ui.components.rememberCaptureGraph
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.capture.ui.theme.NumberFont
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.text.Formatters
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun GalleryScreen(onShowMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val graph = rememberCaptureGraph()
    val vm: GalleryViewModel = viewModel { GalleryViewModel(graph) }
    val query by vm.query.collectAsStateWithLifecycle()
    val captures by vm.items.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val marked by vm.markedDays.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var showCalendar by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CaptureRecordEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            onShowMessage(it)
            vm.consumeMessage()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureCategory.entries.forEach { category ->
                        FilterChip(
                            selected = query.category == category,
                            onClick = { vm.setCategory(category) },
                            label = { Text(if (category == CaptureCategory.SCREENSHOT) "Screenshots" else category.displayName) },
                        )
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateFilter.entries.filter { it != DateFilter.DAY }.forEach { filter ->
                        FilterChip(
                            selected = query.filter == filter,
                            onClick = { vm.setFilter(filter) },
                            label = { Text(filter.label) },
                        )
                    }
                    FilterChip(
                        selected = showCalendar || query.filter == DateFilter.DAY,
                        onClick = { showCalendar = !showCalendar },
                        label = {
                            Text(if (query.filter == DateFilter.DAY) Formatters.dayTitle(query.day) else "Calendar")
                        },
                    )
                }
                if (showCalendar) {
                    MonthCalendar(
                        month = month,
                        markedDays = marked,
                        selectedDay = if (query.filter == DateFilter.DAY) query.day else null,
                        onSelect = {
                            vm.pickDay(it)
                            showCalendar = false
                        },
                        onPrevious = vm::previousMonth,
                        onNext = vm::nextMonth,
                    )
                }
                Text(
                    "${captures.size} ${if (query.category == CaptureCategory.SCREENSHOT) "screenshots" else "photos"}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (captures.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Nothing captured for this filter yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        items(captures, key = { it.captureId }) { record ->
            GalleryTile(record, onClick = { selected = record })
        }
    }

    selected?.let { record ->
        ImageViewerDialog(
            record = record,
            loadImage = { vm.loadFullImage(it) },
            onDelete = { alsoCloud ->
                vm.delete(record, alsoCloud)
                selected = null
            },
            onExport = { uri, resolver -> vm.export(record, uri, resolver) },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun GalleryTile(record: CaptureRecordEntity, onClick: () -> Unit) {
    val zone = ZoneId.systemDefault()
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        EncryptedThumbnail(record, Modifier.fillMaxWidth().aspectRatio(0.75f))
        Text(Formatters.dateTime(record.capturedAtUtc, zone), style = MaterialTheme.typography.labelSmall, fontFamily = NumberFont)
        val (label, color) = storageLabel(record)
        Text("${Formatters.bytes(record.imageSizeBytes)} · $label", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Where the capture currently lives, in plain words. */
fun storageLabel(record: CaptureRecordEntity): Pair<String, Color> = when {
    record.uploadStatus == UploadStatus.UPLOAD_FAILED -> "Upload failed" to CaptureColors.Failure
    record.uploadStatus == UploadStatus.UPLOAD_PENDING -> "Upload pending" to CaptureColors.Pending
    record.hasCloudCopy && record.hasLocalImage -> "Phone + cloud" to CaptureColors.Cloud
    record.hasCloudCopy -> "Cloud only" to CaptureColors.Cloud
    record.cloudDeletePending -> "Cloud deletion pending" to CaptureColors.Pending
    else -> "Phone only" to CaptureColors.Skipped
}

@Composable
fun MonthCalendar(
    month: YearMonth,
    markedDays: Set<LocalDate>,
    selectedDay: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val today = LocalDate.now(ZoneId.systemDefault())
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPrevious) { Text("‹") }
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(onClick = onNext, enabled = month.isBefore(YearMonth.from(today))) { Text("›") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }
        val leadingBlanks = month.atDay(1).dayOfWeek.value - 1
        val cells: List<LocalDate?> = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val isSelected = day == selectedDay
                            val enabled = !day.isAfter(today)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .then(
                                        if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                                    )
                                    .clickable(enabled = enabled) { onSelect(day) }
                                    .padding(6.dp),
                            ) {
                                Text(
                                    day.dayOfMonth.toString(),
                                    fontFamily = NumberFont,
                                    fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                )
                                if (day in markedDays) {
                                    Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                } else {
                                    Spacer(Modifier.height(5.dp))
                                }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
