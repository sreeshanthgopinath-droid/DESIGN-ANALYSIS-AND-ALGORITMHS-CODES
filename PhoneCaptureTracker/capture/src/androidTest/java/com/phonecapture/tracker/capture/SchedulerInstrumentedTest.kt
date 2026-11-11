package com.phonecapture.tracker.capture

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.phonecapture.tracker.capture.camera.CaptureOutcome
import com.phonecapture.tracker.capture.data.CaptureRepository
import com.phonecapture.tracker.capture.data.db.CaptureDatabase
import com.phonecapture.tracker.capture.data.settings.CaptureSettings
import com.phonecapture.tracker.capture.data.settings.ScheduleStateStore
import com.phonecapture.tracker.capture.data.settings.SettingsRepository
import com.phonecapture.tracker.capture.diagnostics.DiagnosticLogger
import com.phonecapture.tracker.capture.schedule.CaptureCycleRunner
import com.phonecapture.tracker.capture.schedule.CaptureSources
import com.phonecapture.tracker.capture.schedule.TickResult
import com.phonecapture.tracker.capture.security.EncryptedFileStore
import com.phonecapture.tracker.capture.security.KeystoreKeyWrapper
import com.phonecapture.tracker.capture.security.VaultManager
import com.phonecapture.tracker.capture.service.DeviceStateReader
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.model.ScheduleState
import com.phonecapture.tracker.core.schedule.CaptureSchedule
import com.phonecapture.tracker.core.schedule.SchedulePlanner
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Scheduler tests: real database, storage and encryption; fake capture sources. */
@RunWith(AndroidJUnit4::class)
class SchedulerInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: CaptureDatabase
    private lateinit var vault: VaultManager
    private lateinit var runner: CaptureCycleRunner
    private lateinit var store: ScheduleStateStore
    private val schedule = CaptureSchedule()
    private var now = Instant.parse("2026-11-11T10:05:00Z").toEpochMilli()
    private val sources = FakeSources()
    private val owner: LifecycleOwner = TestLifecycleOwner(Lifecycle.State.RESUMED)

    private class FakeSources : CaptureSources {
        var front: CaptureOutcome = ok()
        var screen: CaptureOutcome = CaptureOutcome.NotCaptured(FailureReason.SCREEN_LOCKED, "locked")
        var calls = 0

        override suspend fun captureCamera(category: CaptureCategory, owner: LifecycleOwner, settings: CaptureSettings): CaptureOutcome {
            calls++
            return if (category == CaptureCategory.FRONT) front else ok()
        }

        override suspend fun captureScreen(settings: CaptureSettings): CaptureOutcome {
            calls++
            return screen
        }

        companion object {
            fun ok() = CaptureOutcome.Captured(byteArrayOf(1, 2, 3), byteArrayOf(4), 4, 3)
        }
    }

    @Before
    fun setUp() {
        context.getSharedPreferences("pct_schedule_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("pct_capture_settings", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.noBackupFilesDir, "vault").deleteRecursively()
        db = CaptureDatabase.inMemory(context)
        val keystore = KeystoreKeyWrapper("pct_test_scheduler_key").also { it.delete() }
        vault = VaultManager(context, keystore).also { it.setUpNewVault() }
        store = ScheduleStateStore(context)
        val planner = SchedulePlanner(schedule)
        store.update { it.copy(state = ScheduleState.RUNNING, lastHandledSlot = planner.baselineForStart(now)) }
        runner = CaptureCycleRunner(
            schedule = schedule,
            planner = planner,
            scheduleStore = store,
            settings = SettingsRepository(context),
            repository = CaptureRepository(db.captureRecordDao(), schedule),
            fileStore = EncryptedFileStore(context, vault),
            sources = sources,
            deviceState = DeviceStateReader(context),
            diagnostics = DiagnosticLogger(db.diagnosticLogDao(), CoroutineScope(Dispatchers.IO)),
            uploadTrigger = { },
            cloudReady = { false },
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        db.close()
        vault.destroy()
        EncryptedFileStore(context, vault).deleteAll()
    }

    @Test
    fun oneCyclePerSlotAndRealOutcomesRecorded() = runTest {
        val first = runner.tick(owner) as TickResult.Done
        assertEquals(2, first.saved)
        assertEquals(1, first.notCaptured)
        val records = db.captureRecordDao().between(0, Long.MAX_VALUE)
        assertEquals(3, records.size)
        val screen = records.single { it.category == CaptureCategory.SCREENSHOT }
        assertEquals(CaptureStatus.SKIPPED, screen.captureStatus)
        assertEquals(FailureReason.SCREEN_LOCKED, screen.failureReason)

        // A second trigger in the same slot (alarm + timer) must not capture again.
        now += 60_000
        val second = runner.tick(owner) as TickResult.Done
        assertEquals(null, second.ranSlot)
        assertEquals(3, sources.calls)
    }

    @Test
    fun failuresAreNeverStoredAsSuccess() = runTest {
        sources.front = CaptureOutcome.NotCaptured(FailureReason.CAMERA_BUSY, "in use")
        runner.tick(owner)
        val front = db.captureRecordDao().between(0, Long.MAX_VALUE).single { it.category == CaptureCategory.FRONT }
        assertEquals(CaptureStatus.FAILED, front.captureStatus)
        assertEquals(null, front.localPath)
    }

    @Test
    fun delayedTickRecordsMissedSlotsWithoutCatchUpCaptures() = runTest {
        runner.tick(owner) // 10:00 slot
        now = Instant.parse("2026-11-11T12:10:00Z").toEpochMilli() // 10:30, 11:00, 11:30 missed
        runner.tick(owner)
        val records = db.captureRecordDao().between(0, Long.MAX_VALUE)
        val missed = records.filter { it.failureReason == FailureReason.MISSED_SCHEDULE }
        assertEquals(3 * 3, missed.size) // 3 slots × 3 categories
        assertTrue(missed.all { it.captureStatus == CaptureStatus.FAILED })
        assertEquals(6, sources.calls) // only two real cycles were run
    }

    @Test
    fun pausedScheduleDoesNothing() = runTest {
        store.update { it.copy(state = ScheduleState.PAUSED) }
        assertEquals(TickResult.NotRunning, runner.tick(owner))
        assertEquals(0, sources.calls)
    }
}
