package com.phonecapture.tracker.capture.schedule

import androidx.lifecycle.LifecycleOwner
import com.phonecapture.tracker.capture.camera.CaptureOutcome
import com.phonecapture.tracker.capture.camera.FrontCameraController
import com.phonecapture.tracker.capture.camera.RearCameraController
import com.phonecapture.tracker.capture.data.CaptureRepository
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.settings.CaptureSettings
import com.phonecapture.tracker.capture.data.settings.ScheduleStateStore
import com.phonecapture.tracker.capture.data.settings.SettingsRepository
import com.phonecapture.tracker.capture.diagnostics.DiagnosticLogger
import com.phonecapture.tracker.capture.projection.ProjectionController
import com.phonecapture.tracker.capture.security.EncryptedFileStore
import com.phonecapture.tracker.capture.security.VaultUnavailableException
import com.phonecapture.tracker.capture.service.DeviceStateReader
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.model.ScheduleState
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.policy.LocalStorageState
import com.phonecapture.tracker.core.policy.StoragePolicy
import com.phonecapture.tracker.core.schedule.CaptureSchedule
import com.phonecapture.tracker.core.schedule.SchedulePlanner
import com.phonecapture.tracker.core.schedule.SingleFlight
import com.phonecapture.tracker.core.text.Formatters
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Abstraction over the three capture sources so the cycle logic can be tested with fakes. */
interface CaptureSources {
    suspend fun captureCamera(category: CaptureCategory, owner: LifecycleOwner, settings: CaptureSettings): CaptureOutcome
    suspend fun captureScreen(settings: CaptureSettings): CaptureOutcome
}

class DefaultCaptureSources(
    private val front: FrontCameraController,
    private val rear: RearCameraController,
    private val projection: ProjectionController,
) : CaptureSources {
    override suspend fun captureCamera(category: CaptureCategory, owner: LifecycleOwner, settings: CaptureSettings): CaptureOutcome {
        val controller = when (category) {
            CaptureCategory.FRONT -> front
            CaptureCategory.REAR -> rear
            CaptureCategory.SCREENSHOT -> error("Not a camera category")
        }
        return controller.capture(owner, settings.jpegQuality, settings.maxImageEdgePx)
    }

    override suspend fun captureScreen(settings: CaptureSettings): CaptureOutcome =
        projection.captureScreenshot(settings.jpegQuality, settings.maxImageEdgePx)
}

/** Called after new successful captures so the upload queue can start. */
fun interface UploadTrigger {
    fun onNewCaptures()
}

sealed interface TickResult {
    data object NotRunning : TickResult
    data object AlreadyRunning : TickResult
    data class Done(val ranSlot: Long?, val nextFireAtMillis: Long, val saved: Int, val notCaptured: Int) : TickResult
}

/**
 * Runs scheduled capture cycles. Guarantees:
 *  - one cycle at a time ([SingleFlight]); concurrent triggers are dropped, not queued;
 *  - at most one attempt per slot and category (planner + database unique index);
 *  - slots that passed without a cycle are recorded as missed, never captured late;
 *  - front, rear and screen are attempted one after another, never simultaneously;
 *  - every attempt is recorded with its real outcome; failures are never stored as successes.
 */
class CaptureCycleRunner(
    private val schedule: CaptureSchedule,
    private val planner: SchedulePlanner,
    private val scheduleStore: ScheduleStateStore,
    private val settings: SettingsRepository,
    private val repository: CaptureRepository,
    private val fileStore: EncryptedFileStore,
    private val sources: CaptureSources,
    private val deviceState: DeviceStateReader,
    private val diagnostics: DiagnosticLogger,
    private val uploadTrigger: UploadTrigger,
    private val cloudReady: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val flight = SingleFlight()

    val isCycleRunning: Boolean get() = flight.isRunning

    suspend fun tick(owner: LifecycleOwner): TickResult =
        flight.runExclusive { tickExclusive(owner) } ?: TickResult.AlreadyRunning

    private suspend fun tickExclusive(owner: LifecycleOwner): TickResult {
        val snapshot = scheduleStore.current
        if (snapshot.state != ScheduleState.RUNNING) return TickResult.NotRunning

        val now = clock()
        val plan = planner.planTick(now, snapshot.lastHandledSlot)
        val currentSettings = settings.current
        val categories = enabledCategories(currentSettings)

        if (plan.missedSlots.isNotEmpty()) {
            try {
                repository.recordMissedSlots(
                    plan.missedSlots,
                    categories,
                    FailureReason.MISSED_SCHEDULE,
                    "No capture cycle ran in this slot (phone asleep, app stopped by Android, or phone off).",
                )
            } catch (e: Exception) {
                diagnostics.log(DiagnosticType.DATABASE_ERROR, "Could not record missed attempts", e.toString())
            }
            diagnostics.log(
                DiagnosticType.PROCESS_INTERRUPTED,
                "${plan.missedSlots.size + plan.missedNotRecorded} scheduled cycle(s) were missed",
                if (plan.missedNotRecorded > 0) "${plan.missedNotRecorded} older slots were counted but not listed individually" else null,
            )
        }
        if (plan.clockMovedBackwards) {
            diagnostics.log(DiagnosticType.SCHEDULE_CHANGED, "The phone's clock moved backwards; duplicate slots are skipped")
        }

        var saved = 0
        var notCaptured = 0
        val slot = plan.slotToRun
        if (slot != null && categories.isNotEmpty() && !repository.dao.isSlotRecorded(slot)) {
            val counts = runCycle(slot, owner, currentSettings, categories)
            saved = counts.first
            notCaptured = counts.second
        }

        val lastHandled = listOfNotNull(snapshot.lastHandledSlot, slot).maxOrNull()
        scheduleStore.update {
            it.copy(
                lastHandledSlot = lastHandled,
                nextAttemptUtc = plan.nextFireAtMillis,
                lastCycleUtc = if (slot != null && categories.isNotEmpty()) now else it.lastCycleUtc,
            )
        }
        return TickResult.Done(slot, plan.nextFireAtMillis, saved, notCaptured)
    }

    private suspend fun runCycle(
        slot: Long,
        owner: LifecycleOwner,
        currentSettings: CaptureSettings,
        categories: List<CaptureCategory>,
    ): Pair<Int, Int> {
        val battery = deviceState.batteryPercent()
        val freeBytes = deviceState.freeLocalBytes()
        if (StoragePolicy.localState(freeBytes) == LocalStorageState.EXHAUSTED) {
            val detail = "Only ${Formatters.bytes(freeBytes)} free on the phone"
            categories.forEach { persist(slot, it, clock(), CaptureOutcome.NotCaptured(FailureReason.LOCAL_STORAGE_EXHAUSTED, detail), battery) }
            return 0 to categories.size
        }

        var saved = 0
        var notCaptured = 0
        for (category in categories) { // sequential: one camera is fully released before the next
            val attemptedAt = clock()
            val outcome = try {
                when (category) {
                    CaptureCategory.FRONT, CaptureCategory.REAR -> sources.captureCamera(category, owner, currentSettings)
                    CaptureCategory.SCREENSHOT -> sources.captureScreen(currentSettings)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CaptureOutcome.NotCaptured(FailureReason.UNKNOWN, e.toString())
            }
            if (persist(slot, category, attemptedAt, outcome, battery)) saved++ else notCaptured++
        }
        if (saved > 0) uploadTrigger.onNewCaptures()
        diagnostics.log(
            DiagnosticType.CYCLE_COMPLETED,
            "Capture at ${Formatters.shortTime(schedule.slotStart(slot), ZoneId.systemDefault())}: $saved saved, $notCaptured not captured",
        )
        return saved to notCaptured
    }

    /** @return true only when an encrypted image was saved and its record written. */
    private suspend fun persist(
        slot: Long,
        category: CaptureCategory,
        attemptedAt: Long,
        outcome: CaptureOutcome,
        battery: Int?,
    ): Boolean {
        val captureId = UUID.randomUUID().toString()
        val base = CaptureRecordEntity(
            captureId = captureId,
            slotIndex = slot,
            slotStartUtc = schedule.slotStart(slot),
            capturedAtUtc = attemptedAt,
            category = category,
            captureStatus = CaptureStatus.FAILED,
            uploadStatus = UploadStatus.NOT_QUEUED,
            batteryPercent = battery,
        )
        val record = when (outcome) {
            is CaptureOutcome.Captured -> {
                val stored = try {
                    fileStore.save(captureId, category, attemptedAt, outcome.jpeg, outcome.thumbnail)
                } catch (e: VaultUnavailableException) {
                    return saveFailure(base, FailureReason.VAULT_LOCKED, e.message ?: "Vault locked")
                } catch (e: Exception) {
                    return saveFailure(base, FailureReason.ENCRYPTION_FAILED, e.toString())
                }
                base.copy(
                    captureStatus = CaptureStatus.SUCCESS,
                    uploadStatus = if (cloudReady()) UploadStatus.UPLOAD_PENDING else UploadStatus.NOT_QUEUED,
                    localPath = stored.imagePath,
                    thumbnailPath = stored.thumbnailPath,
                    imageSizeBytes = stored.plainBytes,
                    storedSizeBytes = stored.storedBytes,
                    widthPx = outcome.width,
                    heightPx = outcome.height,
                    note = outcome.note,
                    keyId = stored.keyId,
                    ciphertextSha256 = stored.ciphertextSha256,
                )
            }
            is CaptureOutcome.NotCaptured -> {
                return saveFailure(base, outcome.reason, outcome.detail)
            }
        }
        return insertOrCleanUp(record)
    }

    private suspend fun saveFailure(base: CaptureRecordEntity, reason: FailureReason, detail: String): Boolean {
        diagnostics.failure(reason, "${base.category.displayName}: $detail", base.captureId)
        insertOrCleanUp(base.copy(captureStatus = reason.resultingStatus, failureReason = reason, failureDetail = detail))
        return false
    }

    private suspend fun insertOrCleanUp(record: CaptureRecordEntity): Boolean {
        val inserted = try {
            repository.insertAttempt(record)
        } catch (e: Exception) {
            diagnostics.log(DiagnosticType.DATABASE_ERROR, "Could not record a ${record.category.displayName} attempt", e.toString(), record.captureId)
            false
        }
        if (!inserted) {
            // Never keep an image without a record (it could not be shown, uploaded or deleted).
            fileStore.delete(record.localPath)
            fileStore.delete(record.thumbnailPath)
            return false
        }
        return record.captureStatus == CaptureStatus.SUCCESS
    }

    companion object {
        fun enabledCategories(settings: CaptureSettings): List<CaptureCategory> = buildList {
            if (settings.captureFront) add(CaptureCategory.FRONT)
            if (settings.captureRear) add(CaptureCategory.REAR)
            if (settings.captureScreen) add(CaptureCategory.SCREENSHOT)
        }

        fun describe(categories: List<CaptureCategory>): String = when (categories.size) {
            0 -> "nothing (all capture types are off)"
            1 -> categories[0].displayName.lowercase()
            else -> categories.dropLast(1).joinToString(", ") { it.displayName.lowercase() } +
                " and " + categories.last().displayName.lowercase()
        }
    }
}
