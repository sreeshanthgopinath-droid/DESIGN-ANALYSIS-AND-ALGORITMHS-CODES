package com.mythos.tracker.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager survives app closure and reboots. 15 minutes is the shortest
 * repeat interval Android allows for periodic work.
 */
object Scheduler {
    private const val COLLECT_PERIODIC = "collect-periodic"
    private const val COLLECT_NOW = "collect-now"
    private const val SYNC_PERIODIC = "sync-periodic"
    private const val SYNC_NOW = "sync-now"
    private const val DIARY_REMINDER = "diary-reminder"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun start(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            COLLECT_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CollectWorker>(15, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            SYNC_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build(),
        )
        wm.cancelUniqueWork(DIARY_REMINDER) // removed feature
    }

    fun collectNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            COLLECT_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CollectWorker>().build(),
        )
    }

    /** Uploads whatever is pending once a network is available; retries with backoff on failure. */
    fun requestSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }
}
