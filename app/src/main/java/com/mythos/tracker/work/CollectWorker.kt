package com.mythos.tracker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mythos.tracker.Prefs
import com.mythos.tracker.collect.LocationCollector
import com.mythos.tracker.collect.MediaCollector
import com.mythos.tracker.collect.UsageCollector
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase

/** Reads every enabled source into the local database. Works offline; syncing happens separately. */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val collected = mutableListOf<EventEntity>()

        // Each source is isolated: one failing source must not stop the others.
        runCatching { collected.addAll(UsageCollector(ctx, prefs).collect(now)) }
        runCatching { LocationCollector(ctx).collect()?.let { collected.add(it) } }
        runCatching { collected.addAll(MediaCollector(ctx, prefs).collect()) }

        if (collected.isNotEmpty()) {
            TrackerDatabase.get(ctx).events().insertAll(collected)
            Scheduler.requestSync(ctx)
        }
        prefs.lastCollectMs = now
        return Result.success()
    }
}
