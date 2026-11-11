package com.phonecapture.tracker.capture

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecapture.tracker.capture.data.db.CaptureDatabase
import com.phonecapture.tracker.capture.data.db.CaptureRecordDao
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.db.DiagnosticLogEntity
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.model.UploadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Database tests (in-memory Room). */
@RunWith(AndroidJUnit4::class)
class CaptureRecordDaoTest {
    private lateinit var db: CaptureDatabase
    private lateinit var dao: CaptureRecordDao

    @Before
    fun setUp() {
        db = CaptureDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.captureRecordDao()
    }

    @After
    fun tearDown() = db.close()

    private fun record(
        id: String,
        slot: Long,
        category: CaptureCategory = CaptureCategory.FRONT,
        status: CaptureStatus = CaptureStatus.SUCCESS,
        upload: UploadStatus = UploadStatus.NOT_QUEUED,
        at: Long = slot * 1_800_000,
        size: Long = 1000,
        local: String? = "/x/$id.jpg.enc",
    ) = CaptureRecordEntity(
        captureId = id,
        slotIndex = slot,
        slotStartUtc = slot * 1_800_000,
        capturedAtUtc = at,
        category = category,
        captureStatus = status,
        uploadStatus = upload,
        localPath = if (status == CaptureStatus.SUCCESS) local else null,
        thumbnailPath = if (status == CaptureStatus.SUCCESS) "/x/$id.thumb.enc" else null,
        storedSizeBytes = size,
        failureReason = if (status == CaptureStatus.SUCCESS) null else FailureReason.CAMERA_BUSY,
    )

    @Test
    fun duplicateSlotAndCategoryIsRejected() = runTest {
        assertTrue(dao.insert(record("a", 10)) != -1L)
        assertEquals(-1L, dao.insert(record("b", 10)))
        assertTrue(dao.insert(record("c", 10, CaptureCategory.REAR)) != -1L)
        assertTrue(dao.isSlotRecorded(10))
        assertFalse(dao.isSlotRecorded(11))
        assertEquals(10L, dao.latestSlot())
    }

    @Test
    fun enumsAndFailuresRoundTrip() = runTest {
        dao.insert(record("f", 3, status = CaptureStatus.FAILED))
        val loaded = dao.get("f")!!
        assertEquals(CaptureStatus.FAILED, loaded.captureStatus)
        assertEquals(FailureReason.CAMERA_BUSY, loaded.failureReason)
        assertNull(loaded.localPath)
    }

    @Test
    fun uploadQueueOrderingAndRetry() = runTest {
        dao.insert(record("late", 5, upload = UploadStatus.UPLOAD_PENDING))
        dao.insert(record("early", 4, upload = UploadStatus.UPLOAD_PENDING))
        dao.insert(record("failed", 3, upload = UploadStatus.UPLOAD_FAILED))
        dao.insert(record("local", 2))
        assertEquals(listOf("early", "late"), dao.pendingUploads(10).map { it.captureId })
        assertEquals(1, dao.resetFailedUploads())
        assertEquals(3, dao.pendingUploadCount())
        assertEquals(1, dao.queueLocalOnlyCaptures())
        assertEquals(4, dao.pendingUploadCount())
        assertEquals(4, dao.unqueuePendingUploads())
    }

    @Test
    fun galleryOnlyShowsSuccessfulCapturesOfTheCategoryInRange() = runTest {
        dao.insert(record("in", 10))
        dao.insert(record("rear", 10, CaptureCategory.REAR))
        dao.insert(record("failed", 11, status = CaptureStatus.FAILED))
        dao.insert(record("out", 100))
        val items = dao.observeGallery(CaptureCategory.FRONT, 0, 50L * 1_800_000).first()
        assertEquals(listOf("in"), items.map { it.captureId })
    }

    @Test
    fun storageSumsCountOnlyExistingCopies() = runTest {
        dao.insert(record("a", 1, size = 100, upload = UploadStatus.UPLOADED))
        dao.insert(record("b", 2, size = 200))
        val gone = record("c", 3, size = 400).copy(localImageDeleted = true, uploadStatus = UploadStatus.UPLOADED)
        dao.insert(gone)
        assertEquals(300L, dao.observeLocalImageBytes().first())
        assertEquals(500L, dao.cloudBytes())
    }

    @Test
    fun diagnosticsArePersistedNewestFirst() = runTest {
        val logs = db.diagnosticLogDao()
        logs.insert(DiagnosticLogEntity(timestampUtc = 1, type = DiagnosticType.CAMERA_BUSY, severity = DiagnosticType.CAMERA_BUSY.severity, message = "old"))
        logs.insert(DiagnosticLogEntity(timestampUtc = 2, type = DiagnosticType.CYCLE_COMPLETED, severity = DiagnosticType.CYCLE_COMPLETED.severity, message = "new"))
        assertEquals(listOf("new", "old"), logs.recent(10).map { it.message })
        assertEquals("old", logs.observeLatestProblem().first()?.message)
        assertEquals(1, logs.deleteOlderThan(2))
    }
}
