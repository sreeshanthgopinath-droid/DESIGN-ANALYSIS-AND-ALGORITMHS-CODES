package com.phonecapture.tracker.core

import com.phonecapture.tracker.core.image.FrameAnalysis
import com.phonecapture.tracker.core.image.ImageSizing
import com.phonecapture.tracker.core.image.PixelSize
import com.phonecapture.tracker.core.model.AttemptInfo
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.schedule.CaptureSchedule
import com.phonecapture.tracker.core.stats.DaySummaries
import com.phonecapture.tracker.core.stats.DaySummary
import com.phonecapture.tracker.core.text.DayShareFormatter
import com.phonecapture.tracker.core.text.Formatters
import com.phonecapture.tracker.core.time.DayWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummaryAndFormatTest {
    private val schedule = CaptureSchedule()
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun attempt(
        id: String,
        iso: String,
        category: CaptureCategory,
        status: CaptureStatus,
        upload: UploadStatus = UploadStatus.NOT_QUEUED,
        reason: FailureReason? = null,
        size: Long = 0,
    ) = AttemptInfo(id, schedule.slotOf(at(iso)), category, status, upload, at(iso), size, reason)

    private val attempts = listOf(
        attempt("1", "2026-11-11T04:30:02Z", CaptureCategory.FRONT, CaptureStatus.SUCCESS, UploadStatus.UPLOADED, size = 400_000),
        attempt("2", "2026-11-11T04:30:05Z", CaptureCategory.REAR, CaptureStatus.SUCCESS, UploadStatus.UPLOAD_PENDING, size = 600_000),
        attempt("3", "2026-11-11T04:30:06Z", CaptureCategory.SCREENSHOT, CaptureStatus.SKIPPED, reason = FailureReason.SCREEN_LOCKED),
        attempt("4", "2026-11-11T05:00:01Z", CaptureCategory.FRONT, CaptureStatus.FAILED, reason = FailureReason.CAMERA_BUSY),
        attempt("5", "2026-11-11T05:00:04Z", CaptureCategory.REAR, CaptureStatus.SUCCESS, UploadStatus.UPLOAD_FAILED, size = 500_000),
        attempt("6", "2026-11-11T05:00:06Z", CaptureCategory.SCREENSHOT, CaptureStatus.SUCCESS, UploadStatus.UPLOAD_PENDING, size = 300_000),
    )

    @Test
    fun summaryCountsEveryOutcomeSeparately() {
        val s = DaySummaries.summarize(attempts)
        assertEquals(6, s.scheduledAttempts)
        assertEquals(4, s.successful)
        assertEquals(1, s.failed)
        assertEquals(1, s.skipped)
        assertEquals(1, s.frontCount)
        assertEquals(2, s.rearCount)
        assertEquals(1, s.screenshotCount)
        assertEquals(1, s.uploaded)
        assertEquals(2, s.pendingUploads)
        assertEquals(1, s.failedUploads)
        assertEquals(1_800_000, s.storedBytes)
        assertEquals(at("2026-11-11T05:00:06Z"), s.lastSuccessUtcMillis)
        assertEquals(DaySummary.EMPTY, DaySummaries.summarize(emptyList()))
    }

    @Test
    fun shareTextUsesLocalTimeAndRealResults() {
        val text = DayShareFormatter.format(LocalDate.of(2026, 11, 11), ist, attempts, schedule)
        assertTrue(text.startsWith("Phone captures — Wed, 11 Nov 2026"))
        assertTrue("10:00  Front OK · Rear OK · Screen SKIPPED (screen locked)" in text, text)
        assertTrue("10:30  Front FAILED (camera busy) · Rear OK · Screen OK" in text, text)
        assertTrue("Cloud: 1 uploaded · 2 pending · 1 failed" in text)
        assertTrue("No capture attempts" in DayShareFormatter.format(LocalDate.of(2026, 11, 11), ist, emptyList()))
    }

    @Test
    fun dayWindowHandlesTimeZonesAndDst() {
        val window = DayWindow.of(LocalDate.of(2026, 11, 11), ist)
        assertEquals(at("2026-11-10T18:30:00Z"), window.startUtcMillis)
        assertTrue(at("2026-11-11T18:29:59Z") in window)
        assertFalse(at("2026-11-11T18:30:00Z") in window)
        val london = DayWindow.of(LocalDate.of(2026, 3, 29), ZoneId.of("Europe/London"))
        assertEquals(23L * 3600 * 1000, london.endUtcMillisExclusive - london.startUtcMillis)
        assertEquals(LocalDate.of(2026, 11, 11), DayWindow.containing(at("2026-11-11T00:10:00Z"), ist).date)
    }

    @Test
    fun formatters() {
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("400 KB", Formatters.bytes(400 * 1024))
        assertEquals("3.5 MB", Formatters.bytes((3.5 * 1024 * 1024).toLong()))
        assertEquals("1.25 GB", Formatters.bytes((1.25 * 1024 * 1024 * 1024).toLong()))
        assertEquals("10:00:02", Formatters.time(at("2026-11-11T04:30:02Z"), ist))
        assertEquals("1h 48m", Formatters.duration((108 * 60 + 5) * 1000L))
        assertEquals("7m 03s", Formatters.duration((7 * 60 + 3) * 1000L))
        assertEquals("45s", Formatters.duration(45_000))
    }

    @Test
    fun imageSizing() {
        assertEquals(1, ImageSizing.inSampleSize(4080, 3072, 2560))
        assertEquals(2, ImageSizing.inSampleSize(8160, 6144, 2560))
        assertEquals(PixelSize(2560, 1928), ImageSizing.fitWithin(4080, 3072, 2560))
        assertEquals(PixelSize(1080, 2400), ImageSizing.fitWithin(1080, 2400, 2560))
        assertEquals(270, ImageSizing.normalizeRotation(-90))
        assertEquals(PixelSize(3, 4), ImageSizing.rotatedSize(PixelSize(4, 3), 90))
        assertFailsWith<IllegalArgumentException> { ImageSizing.normalizeRotation(45) }
    }

    @Test
    fun blackedOutFramesAreDetected() {
        val black = IntArray(1000) { 0xFF000000.toInt() }
        val normal = IntArray(1000) { if (it % 3 == 0) 0xFF000000.toInt() else 0xFF8080FF.toInt() }
        assertTrue(FrameAnalysis.isLikelyBlackedOut(black))
        assertFalse(FrameAnalysis.isLikelyBlackedOut(normal))
    }

    @Test
    fun failureReasonsMapToStatusesAndDiagnostics() {
        assertEquals(CaptureStatus.SKIPPED, FailureReason.SCREEN_LOCKED.resultingStatus)
        assertEquals(CaptureStatus.FAILED, FailureReason.CAMERA_BUSY.resultingStatus)
        assertEquals(CaptureStatus.FAILED, FailureReason.MISSED_SCHEDULE.resultingStatus)
        FailureReason.entries.forEach { assertTrue(it.userMessage.isNotBlank()); it.diagnosticType }
    }
}
