package com.phonecapture.tracker.capture.cloud

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.settings.CaptureSettings
import com.phonecapture.tracker.capture.data.settings.SettingsRepository
import com.phonecapture.tracker.capture.schedule.UploadTrigger
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.UploadStatus
import com.phonecapture.tracker.core.model.VerificationStatus
import com.phonecapture.tracker.core.policy.CloudQuotaState
import com.phonecapture.tracker.core.policy.StoragePolicy
import com.phonecapture.tracker.core.policy.UploadDecision
import com.phonecapture.tracker.core.policy.UploadErrorKind
import com.phonecapture.tracker.core.policy.NetworkRequirement
import com.phonecapture.tracker.core.policy.UploadPolicy
import com.phonecapture.tracker.core.policy.UploadRetryPolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live progress of the upload queue for the dashboard. */
data class UploadProgress(val captureId: String, val percent: Int, val remaining: Int)

class UploadProgressTracker {
    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress.asStateFlow()
    fun set(value: UploadProgress?) {
        _progress.value = value
    }
}

/**
 * Drains the upload queue. WorkManager provides persistence across reboots, network constraints
 * and exponential back-off. It is used only for uploads – never to start camera or screen capture.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = CaptureGraph.get(applicationContext)
        val settings = graph.settings.current
        if (!settings.cloudSyncEnabled || !graph.firebase.isConfigured) return Result.success()
        if (graph.firebase.currentUid() == null) {
            graph.diagnostics.log(DiagnosticType.FIREBASE_AUTH_FAILURE, "Uploads are waiting: not signed in to the cloud account")
            return Result.success()
        }
        val dao = graph.repository.dao
        if (StoragePolicy.cloudState(dao.cloudBytes(), settings.cloudQuotaBytes) == CloudQuotaState.EXHAUSTED) {
            graph.diagnostics.log(
                DiagnosticType.CLOUD_STORAGE_EXHAUSTED,
                "Uploads paused: the cloud storage limit set in Settings has been reached",
            )
            return Result.success()
        }
        if (graph.vault.localKeyring() == null) return Result.success() // no key yet, so nothing was captured
        // Images may only be uploaded once the cloud can be used to recover their key.
        when (val keyring = graph.dataManager.syncKeyring()) {
            KeyringSync.InSync -> Unit
            KeyringSync.NeedsRecoveryCode -> {
                graph.diagnostics.log(
                    DiagnosticType.UPLOAD_FAILURE,
                    "Uploads paused: enter this account's recovery code in Settings to link this phone's key",
                )
                return Result.success()
            }
            is KeyringSync.Error -> {
                graph.diagnostics.log(DiagnosticType.UPLOAD_FAILURE, "Could not sync the recovery keyring", keyring.message)
                return Result.retry()
            }
        }

        var needsRetry = false
        var uploaded = 0
        try {
            batches@ while (!isStopped) {
                val batch = dao.pendingUploads(BATCH_SIZE)
                if (batch.isEmpty()) break
                for (record in batch) {
                    if (isStopped) break@batches
                    val remaining = dao.pendingUploadCount()
                    graph.uploadProgress.set(UploadProgress(record.captureId, 0, remaining))
                    val outcome = graph.uploader.upload(record) { percent ->
                        graph.uploadProgress.set(UploadProgress(record.captureId, percent, remaining))
                    }
                    val attempts = record.uploadAttempts + 1
                    when (outcome) {
                        is UploadOutcome.Uploaded -> {
                            uploaded++
                            val updated = record.copy(
                                uploadStatus = UploadStatus.UPLOADED,
                                verificationStatus = VerificationStatus.VERIFIED,
                                cloudPath = outcome.cloudPath,
                                uploadedAtUtc = System.currentTimeMillis(),
                                uploadAttempts = attempts,
                                uploadProgress = 100,
                                uploadError = null,
                            )
                            dao.update(updated)
                            if (settings.deleteLocalAfterVerifiedUpload) graph.dataManager.deleteLocalImageAfterVerifiedUpload(updated)
                        }
                        is UploadOutcome.Failed -> {
                            val stop = handleFailure(graph, record, attempts, outcome)
                            if (stop) {
                                needsRetry = outcome.kind.transient
                                break@batches
                            }
                        }
                    }
                }
            }
        } finally {
            graph.uploadProgress.set(null)
        }
        if (uploaded > 0) {
            graph.diagnostics.log(DiagnosticType.UPLOAD_VERIFIED, "$uploaded encrypted capture(s) uploaded and verified")
        }
        return if (needsRetry) Result.retry() else Result.success()
    }

    /** @return true when the queue should stop for now. */
    private suspend fun handleFailure(
        graph: CaptureGraph,
        record: CaptureRecordEntity,
        attempts: Int,
        outcome: UploadOutcome.Failed,
    ): Boolean {
        val dao = graph.repository.dao
        return when (val decision = retryPolicy.decide(outcome.kind, attempts)) {
            is UploadDecision.Retry -> {
                dao.update(record.copy(uploadAttempts = attempts, uploadError = outcome.detail, uploadProgress = 0))
                val type = if (outcome.kind == UploadErrorKind.NETWORK || outcome.kind == UploadErrorKind.TIMEOUT) {
                    DiagnosticType.INTERNET_UNAVAILABLE
                } else {
                    DiagnosticType.UPLOAD_FAILURE
                }
                graph.diagnostics.log(type, "Upload will be retried: ${outcome.kind.description}", outcome.detail, record.captureId)
                true
            }
            is UploadDecision.GiveUp -> {
                dao.update(
                    record.copy(
                        uploadStatus = UploadStatus.UPLOAD_FAILED,
                        uploadAttempts = attempts,
                        uploadError = "${decision.kind.description}: ${outcome.detail}",
                        uploadProgress = 0,
                        verificationStatus = if (decision.kind == UploadErrorKind.VERIFICATION_MISMATCH) {
                            VerificationStatus.MISMATCH
                        } else {
                            record.verificationStatus
                        },
                    ),
                )
                val type = when (decision.kind) {
                    UploadErrorKind.QUOTA_EXCEEDED -> DiagnosticType.CLOUD_STORAGE_EXHAUSTED
                    UploadErrorKind.NOT_SIGNED_IN -> DiagnosticType.FIREBASE_AUTH_FAILURE
                    else -> DiagnosticType.UPLOAD_FAILURE
                }
                graph.diagnostics.log(type, "Upload failed: ${decision.kind.description}", outcome.detail, record.captureId)
                // Account-wide problems stop the whole queue; per-file problems only skip this file.
                decision.kind == UploadErrorKind.QUOTA_EXCEEDED ||
                    decision.kind == UploadErrorKind.NOT_SIGNED_IN ||
                    decision.kind == UploadErrorKind.PERMISSION_DENIED
            }
        }
    }

    companion object {
        private const val BATCH_SIZE = 10
        val retryPolicy = UploadRetryPolicy()
    }
}

/** Enqueues uploads with the user's network preference. */
class UploadScheduler(context: Context, private val settings: SettingsRepository) : UploadTrigger {
    private val appContext = context.applicationContext
    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    override fun onNewCaptures() = enqueueNow()

    fun enqueueNow(replace: Boolean = false) {
        val s = settings.current
        if (!s.cloudSyncEnabled) return
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(s))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_NOW,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** Safety net: retries the queue hourly even if an enqueue was missed. */
    fun syncPeriodicWork() {
        val s = settings.current
        if (!s.cloudSyncEnabled) {
            cancelAll()
            return
        }
        val request = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints(s))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(UNIQUE_NOW)
        workManager.cancelUniqueWork(UNIQUE_PERIODIC)
    }

    private fun constraints(s: CaptureSettings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            when (UploadPolicy.networkRequirement(s.allowMobileDataUpload)) {
                NetworkRequirement.UNMETERED -> NetworkType.UNMETERED
                NetworkRequirement.CONNECTED -> NetworkType.CONNECTED
            },
        )
        .build()

    companion object {
        const val TAG = "pct-upload"
        private const val UNIQUE_NOW = "pct-upload-queue"
        private const val UNIQUE_PERIODIC = "pct-upload-periodic"
    }
}
