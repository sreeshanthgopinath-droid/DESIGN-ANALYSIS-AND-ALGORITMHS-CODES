package com.phonecapture.tracker.capture.maintenance

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.policy.LocalStorageState
import com.phonecapture.tracker.core.policy.StoragePolicy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Daily housekeeping: retention rules, pending cloud deletions, old diagnostics, storage warnings. */
class MaintenanceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val graph = CaptureGraph.get(applicationContext)
        return try {
            graph.dataManager.applyRetention()
            if (graph.firebase.isConfigured && graph.firebase.currentUid() != null) {
                graph.dataManager.processPendingCloudDeletes()
            }
            graph.diagnostics.pruneOlderThan(System.currentTimeMillis() - LOG_RETENTION_MILLIS)
            if (StoragePolicy.localState(graph.deviceState.freeLocalBytes()) != LocalStorageState.OK) {
                graph.diagnostics.log(
                    DiagnosticType.LOCAL_STORAGE_EXHAUSTED,
                    "Phone storage is low; captures stop below ${StoragePolicy.MIN_FREE_BYTES_TO_CAPTURE / (1024 * 1024)} MB free",
                )
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            graph.diagnostics.log(DiagnosticType.UNEXPECTED_ERROR, "Daily maintenance failed", e.toString())
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "pct-maintenance"
        private const val LOG_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
