package com.phonecapture.tracker.capture

import android.content.Context
import com.phonecapture.tracker.capture.camera.FrontCameraController
import com.phonecapture.tracker.capture.camera.JpegProcessor
import com.phonecapture.tracker.capture.camera.RearCameraController
import com.phonecapture.tracker.capture.cloud.CaptureDataManager
import com.phonecapture.tracker.capture.cloud.CloudUploader
import com.phonecapture.tracker.capture.cloud.FirebaseGateway
import com.phonecapture.tracker.capture.cloud.UploadProgressTracker
import com.phonecapture.tracker.capture.cloud.UploadScheduler
import com.phonecapture.tracker.capture.data.CaptureRepository
import com.phonecapture.tracker.capture.data.db.CaptureDatabase
import com.phonecapture.tracker.capture.data.settings.ScheduleStateStore
import com.phonecapture.tracker.capture.data.settings.SettingsRepository
import com.phonecapture.tracker.capture.diagnostics.DiagnosticLogger
import com.phonecapture.tracker.capture.maintenance.MaintenanceWorker
import com.phonecapture.tracker.capture.projection.ProjectionController
import com.phonecapture.tracker.capture.schedule.CaptureAlarmScheduler
import com.phonecapture.tracker.capture.schedule.CaptureCycleRunner
import com.phonecapture.tracker.capture.schedule.CycleWakeLock
import com.phonecapture.tracker.capture.schedule.DefaultCaptureSources
import com.phonecapture.tracker.capture.security.EncryptedFileStore
import com.phonecapture.tracker.capture.security.KeystoreKeyWrapper
import com.phonecapture.tracker.capture.security.VaultManager
import com.phonecapture.tracker.capture.service.CaptureController
import com.phonecapture.tracker.capture.service.CaptureNotifications
import com.phonecapture.tracker.capture.service.DeviceStateReader
import com.phonecapture.tracker.capture.ui.components.ThumbnailCache
import com.phonecapture.tracker.core.schedule.CaptureSchedule
import com.phonecapture.tracker.core.schedule.SchedulePlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Composition root (manual constructor injection). Every collaborator receives its dependencies
 * through its constructor, which keeps classes testable and lets the module be embedded in another
 * app (e.g. Mythos Tracker) without imposing a DI framework on it.
 */
class CaptureGraph private constructor(context: Context) {
    val appContext: Context = context.applicationContext
    val clock: () -> Long = System::currentTimeMillis
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val schedule = CaptureSchedule()
    val planner = SchedulePlanner(schedule)
    val settings = SettingsRepository(appContext)
    val scheduleStore = ScheduleStateStore(appContext)
    val deviceState = DeviceStateReader(appContext)
    val notifications = CaptureNotifications(appContext)
    val jpegProcessor = JpegProcessor()
    val cycleWakeLock = CycleWakeLock(appContext)
    val uploadProgress = UploadProgressTracker()

    val database: CaptureDatabase by lazy { CaptureDatabase.build(appContext) }
    val diagnostics: DiagnosticLogger by lazy { DiagnosticLogger(database.diagnosticLogDao(), appScope, clock) }
    val repository: CaptureRepository by lazy { CaptureRepository(database.captureRecordDao(), schedule) }

    val vault: VaultManager by lazy { VaultManager(appContext, KeystoreKeyWrapper(), clock) }
    val fileStore: EncryptedFileStore by lazy { EncryptedFileStore(appContext, vault) }
    val thumbnails: ThumbnailCache by lazy { ThumbnailCache(fileStore) }

    val frontCamera: FrontCameraController by lazy {
        FrontCameraController(appContext, jpegProcessor) { deviceState.hasCameraPermission() }
    }
    val rearCamera: RearCameraController by lazy {
        RearCameraController(appContext, jpegProcessor) { deviceState.hasCameraPermission() }
    }
    val projection: ProjectionController by lazy {
        ProjectionController(appContext, deviceState, jpegProcessor, diagnostics, notifications, clock)
    }

    val firebase: FirebaseGateway by lazy { FirebaseGateway(appContext, diagnostics).also { it.startListening() } }
    val uploads: UploadScheduler by lazy { UploadScheduler(appContext, settings) }
    val uploader: CloudUploader by lazy { CloudUploader(firebase, fileStore) }
    val dataManager: CaptureDataManager by lazy {
        CaptureDataManager(repository, fileStore, firebase, vault, settings, diagnostics, database.diagnosticLogDao(), clock)
    }

    val alarms: CaptureAlarmScheduler by lazy { CaptureAlarmScheduler(appContext, diagnostics) }
    val cycleRunner: CaptureCycleRunner by lazy {
        CaptureCycleRunner(
            schedule = schedule,
            planner = planner,
            scheduleStore = scheduleStore,
            settings = settings,
            repository = repository,
            fileStore = fileStore,
            sources = DefaultCaptureSources(frontCamera, rearCamera, projection),
            deviceState = deviceState,
            diagnostics = diagnostics,
            uploadTrigger = uploads,
            cloudReady = { isCloudReady() },
            clock = clock,
        )
    }
    val controller: CaptureController by lazy {
        CaptureController(appContext, scheduleStore, settings, planner, alarms, projection, deviceState, vault, diagnostics, notifications, clock)
    }

    /** Captures are queued for upload only when sync is on, Firebase is configured and a user is signed in. */
    fun isCloudReady(): Boolean =
        settings.current.cloudSyncEnabled && firebase.isConfigured && firebase.currentUid() != null

    /** Call once from Application.onCreate() of the host app (PhoneCaptureTracker or Mythos Tracker). */
    fun initialize() {
        notifications.ensureChannels()
        MaintenanceWorker.schedule(appContext)
        uploads.syncPeriodicWork()
        if (firebase.isConfigured) firebase.startListening()
    }

    companion object {
        @Volatile
        private var instance: CaptureGraph? = null

        fun get(context: Context): CaptureGraph =
            instance ?: synchronized(this) {
                instance ?: CaptureGraph(context.applicationContext).also { instance = it }
            }
    }
}
