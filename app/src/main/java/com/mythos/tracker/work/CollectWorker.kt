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
import kotlinx.coroutines.CancellationException

/**
 * Reads every enabled source into the local database. Works offline; syncing happens separately.
 * Each source saves its own results as it goes and is isolated from the others, and the outcome
 * of every source (count or error) is written to a short summary shown in the app.
 */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val dao = TrackerDatabase.get(ctx).events()
        val now = System.currentTimeMillis()

        var newlyStored = 0
        val save: suspend (List<EventEntity>) -> Unit = { events ->
            // -1 means the event already existed and was skipped.
            newlyStored += dao.insertAll(events).count { it != -1L }
        }

        val results = mutableListOf<String>()
        suspend fun step(name: String, block: suspend () -> Int) {
            try {
                results += "$name ${block()}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results += "$name FAILED (${e.javaClass.simpleName}: ${e.message})"
            }
        }

        step("apps") { UsageCollector(ctx, prefs).collect(now, save) }
        step("location") {
            val event = LocationCollector(ctx).collect()
            if (event != null) save(listOf(event))
            if (event != null) 1 else 0
        }
        step("media") { MediaCollector(ctx, prefs).collect(save) }

        prefs.lastCollectMs = now
        prefs.lastCollectSummary = results.joinToString(" · ")
        if (newlyStored > 0) Scheduler.requestSync(ctx)
        return Result.success()
    }
}
