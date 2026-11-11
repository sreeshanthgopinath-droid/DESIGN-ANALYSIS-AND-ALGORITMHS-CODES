package com.phonecapture.tracker.core.text

import com.phonecapture.tracker.core.model.AttemptInfo
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.schedule.CaptureSchedule
import com.phonecapture.tracker.core.stats.DaySummaries
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val SHORT_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.ENGLISH)
    private val DAY_TITLE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

    fun bytes(count: Long): String = when {
        count < 0 -> "–"
        count < 1024 -> "$count B"
        count < 1024L * 1024 -> "%.0f KB".format(Locale.ROOT, count / 1024.0)
        count < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.ROOT, count / (1024.0 * 1024))
        else -> "%.2f GB".format(Locale.ROOT, count / (1024.0 * 1024 * 1024))
    }

    fun time(utcMillis: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(utcMillis).atZone(zone))
    fun shortTime(utcMillis: Long, zone: ZoneId): String = SHORT_TIME.format(Instant.ofEpochMilli(utcMillis).atZone(zone))
    fun dateTime(utcMillis: Long, zone: ZoneId): String = DATE_TIME.format(Instant.ofEpochMilli(utcMillis).atZone(zone))
    fun dayTitle(date: LocalDate): String = DAY_TITLE.format(date)

    /** "12m 30s", "1h 05m", "45s" */
    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "%dh %02dm".format(Locale.ROOT, h, m)
            m > 0 -> "%dm %02ds".format(Locale.ROOT, m, s)
            else -> "${s}s"
        }
    }
}

/**
 * Plain-text day report in the same spirit as Mythos Tracker's "Share day as text".
 * Each slot appears on one line with the real result of every category; nothing is inferred.
 */
object DayShareFormatter {
    fun format(
        date: LocalDate,
        zone: ZoneId,
        attempts: List<AttemptInfo>,
        schedule: CaptureSchedule = CaptureSchedule(),
    ): String {
        val summary = DaySummaries.summarize(attempts)
        val sb = StringBuilder()
        sb.appendLine("Phone captures — ${Formatters.dayTitle(date)}")
        sb.appendLine(
            "Attempts ${summary.scheduledAttempts} · Successful ${summary.successful} · " +
                "Failed ${summary.failed} · Skipped ${summary.skipped}",
        )
        sb.appendLine(
            "Front ${summary.frontCount} · Rear ${summary.rearCount} · Screenshots ${summary.screenshotCount}",
        )
        sb.appendLine(
            "Cloud: ${summary.uploaded} uploaded · ${summary.pendingUploads} pending · ${summary.failedUploads} failed",
        )
        if (attempts.isEmpty()) {
            sb.appendLine()
            sb.append("No capture attempts recorded.")
            return sb.toString()
        }
        sb.appendLine()
        attempts.groupBy { it.slotIndex }.toSortedMap().forEach { (slot, inSlot) ->
            val slotTime = Formatters.shortTime(schedule.slotStart(slot), zone)
            val parts = CaptureCategory.entries.mapNotNull { category ->
                val attempt = inSlot.firstOrNull { it.category == category } ?: return@mapNotNull null
                val result = when (attempt.captureStatus) {
                    CaptureStatus.SUCCESS -> "OK"
                    CaptureStatus.FAILED -> "FAILED"
                    CaptureStatus.SKIPPED -> "SKIPPED"
                }
                val reason = attempt.failureReason?.let { " (${it.name.lowercase().replace('_', ' ')})" } ?: ""
                "${category.shortName} $result$reason"
            }
            sb.appendLine("$slotTime  ${parts.joinToString(" · ")}")
        }
        return sb.toString().trimEnd()
    }
}
