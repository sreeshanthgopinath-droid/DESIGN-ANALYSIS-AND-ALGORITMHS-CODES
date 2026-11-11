package com.phonecapture.tracker.capture.mythos

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.toAttemptInfo
import com.phonecapture.tracker.capture.service.CameraCaptureService
import com.phonecapture.tracker.capture.ui.components.EncryptedThumbnail
import com.phonecapture.tracker.capture.ui.components.RecordingDot
import com.phonecapture.tracker.capture.ui.components.SectionCard
import com.phonecapture.tracker.capture.ui.components.StatGrid
import com.phonecapture.tracker.capture.ui.components.rememberCaptureGraph
import com.phonecapture.tracker.capture.ui.gallery.storageLabel
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.model.ScheduleState
import com.phonecapture.tracker.core.stats.DaySummaries
import com.phonecapture.tracker.core.stats.DaySummary
import com.phonecapture.tracker.core.text.DayShareFormatter
import com.phonecapture.tracker.core.text.Formatters
import com.phonecapture.tracker.core.time.DayWindow
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One row for Mythos Tracker's "My day" timeline, shaped like its Apps / Calls / Places rows:
 * a time, a coloured tag, a title and a subtitle.
 */
data class CaptureTimelineEntry(
    val captureId: String,
    val timeUtcMillis: Long,
    val tag: String,
    val tagColorArgb: Int,
    val title: String,
    val subtitle: String,
    val captured: Boolean,
)

/**
 * Everything Mythos Tracker needs to show phone captures inside its own screens.
 * See docs/MYTHOS_TRACKER_INTEGRATION.md for the step-by-step wiring.
 */
object MythosCaptureIntegration {

    /** Call from Mythos Tracker's Application.onCreate(). */
    fun initialize(context: Context) = CaptureGraph.get(context).initialize()

    fun timeline(context: Context, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<List<CaptureTimelineEntry>> =
        CaptureGraph.get(context).repository.observeDay(DayWindow.of(date, zone)).map { records -> records.map { toEntry(it) } }

    fun daySummary(context: Context, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Flow<DaySummary> =
        CaptureGraph.get(context).repository.observeDaySummary(DayWindow.of(date, zone))

    /** Lines to append to Mythos Tracker's "Share day as text". */
    suspend fun shareText(context: Context, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val graph = CaptureGraph.get(context)
        val window = DayWindow.of(date, zone)
        val records = graph.repository.dao.between(window.startUtcMillis, window.endUtcMillisExclusive)
        return DayShareFormatter.format(date, zone, records.map { it.toAttemptInfo() }, graph.schedule)
    }

    /** Label for Mythos Tracker's filter chip row ("All · Apps 202 · YouTube 0 · … · Captures 24"). */
    fun chipLabel(summary: DaySummary): String = "Captures ${summary.successful}"

    private fun toEntry(record: CaptureRecordEntity): CaptureTimelineEntry {
        val captured = record.captureStatus == CaptureStatus.SUCCESS
        val title = when (record.captureStatus) {
            CaptureStatus.SUCCESS -> if (record.category == CaptureCategory.SCREENSHOT) "Screenshot saved" else "Photo saved"
            CaptureStatus.FAILED -> if (record.failureReason == FailureReason.MISSED_SCHEDULE) "Missed capture" else "Capture failed"
            CaptureStatus.SKIPPED -> "Capture skipped"
        }
        val subtitle = if (captured) {
            "${Formatters.bytes(record.imageSizeBytes)} · ${storageLabel(record).first}"
        } else {
            record.failureReason?.userMessage ?: "No reason recorded"
        }
        return CaptureTimelineEntry(
            captureId = record.captureId,
            timeUtcMillis = record.capturedAtUtc,
            tag = record.category.shortName,
            tagColorArgb = CaptureColors.category(record.category).toArgb(),
            title = title,
            subtitle = subtitle,
            captured = captured,
        )
    }
}

/**
 * Drop-in card for Mythos Tracker's "My day" tab, styled like its "Screen time by app" and
 * "Photos & videos" sections.
 */
@Composable
fun MythosCaptureDayCard(date: LocalDate, onOpenCaptures: () -> Unit, modifier: Modifier = Modifier) {
    val graph = rememberCaptureGraph()
    val zone = ZoneId.systemDefault()
    val recordsFlow = remember(graph, date) { graph.repository.observeDay(DayWindow.of(date, zone)) }
    val records by recordsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val schedule by graph.scheduleStore.snapshot.collectAsStateWithLifecycle()
    val serviceRunning by CameraCaptureService.isRunning.collectAsStateWithLifecycle()
    val summary = DaySummaries.summarize(records.map { it.toAttemptInfo() })
    val missed = records.count { it.failureReason == FailureReason.MISSED_SCHEDULE }
    val images = records.filter { it.captureStatus == CaptureStatus.SUCCESS }.sortedByDescending { it.capturedAtUtc }

    SectionCard(modifier = modifier, title = "Phone captures") {
        val active = schedule.state == ScheduleState.RUNNING && serviceRunning
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordingDot(active = active)
            Text(
                when {
                    active -> "Capturing every 30 minutes"
                    schedule.state == ScheduleState.RUNNING -> "Interrupted – open to resume"
                    schedule.state == ScheduleState.PAUSED -> "Paused"
                    else -> "Off"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        StatGrid(
            listOf(
                summary.frontCount.toString() to "Front",
                summary.rearCount.toString() to "Rear",
                summary.screenshotCount.toString() to "Screens",
                summary.failed.toString() to "Failed",
                missed.toString() to "Missed",
                summary.uploaded.toString() to "Uploaded",
            ),
        )
        if (images.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Captures (${images.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images, key = { it.captureId }) { record ->
                        EncryptedThumbnail(record, Modifier.size(96.dp).clickable(onClick = onOpenCaptures))
                    }
                }
            }
        }
        TextButton(onClick = onOpenCaptures) { Text("Open captures") }
    }
}
