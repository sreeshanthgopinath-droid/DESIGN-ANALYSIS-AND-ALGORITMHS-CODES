package com.mythos.tracker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mythos.tracker.LocationService
import com.mythos.tracker.Prefs
import com.mythos.tracker.collect.CallLogCollector
import com.mythos.tracker.collect.LocationCollector
import com.mythos.tracker.collect.MediaCollector
import com.mythos.tracker.collect.SmsCollector
import com.mythos.tracker.collect.UsageCollector
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs about every 15 minutes in the background: saves app usage, calls, bank SMS and photo details,
 * keeps the location tracker alive, and takes a location reading itself if the tracker has gone quiet.
 */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        val dao = TrackerDatabase.get(ctx).events()
        val now = System.currentTimeMillis()

        LocationService.start(ctx)

        var newlyStored = 0
        val save: suspend (List<EventEntity>) -> Unit = { events ->
            newlyStored += dao.insertAll(events).count { it != -1L }
        }

        val results = mutableListOf<String>()
        suspend fun step(name: String, block: suspend () -> Int) {
            prefs.collectProgress = "Reading $name…"
            try {
                val n = withTimeoutOrNull(STEP_TIMEOUT_MS) { block() }
                results += if (n == null) "$name timed out" else "$name $n"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results += "$name FAILED (${e.javaClass.simpleName}: ${e.message})"
            }
        }

        step("apps") { UsageCollector(ctx, prefs).collect(now, save) }
        if (now - prefs.lastLocationMs > 20 * 60 * 1000L) {
            step("location") {
                val event = LocationCollector(ctx).collect()
                if (event != null) {
                    save(listOf(event))
                    prefs.lastLocationMs = System.currentTimeMillis()
                }
                if (event != null) 1 else 0
            }
        }
        step("photos") { MediaCollector(ctx, prefs).collect(save) }
        step("calls") { CallLogCollector(ctx, prefs).collect(save) }
        step("sms") { SmsCollector(ctx, prefs).collect(save) }

        prefs.collectProgress = ""
        prefs.lastCollectMs = System.currentTimeMillis()
        prefs.lastCollectSummary = results.joinToString(" · ")
        if (newlyStored > 0) Scheduler.requestSync(ctx)
        return Result.success()
    }

    companion object {
        private const val STEP_TIMEOUT_MS = 90_000L
    }
}
