package com.mythos.tracker.collect

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.mythos.tracker.Prefs
import com.mythos.tracker.data.EventEntity
import org.json.JSONObject

/**
 * Builds app-usage sessions (foreground start -> background) from UsageStatsManager.
 * Requires the user to grant Usage Access in system settings.
 */
class UsageCollector(context: Context, private val prefs: Prefs) {
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun collect(now: Long): List<EventEntity> {
        val begin = maxOf(prefs.watermark(WATERMARK), now - MAX_LOOKBACK_MS)
        val open = loadOpenSessions()
        val out = mutableListOf<EventEntity>()

        val events = usm.queryEvents(begin, now) ?: return out
        val event = UsageEvents.Event()
        var newest = begin

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            newest = maxOf(newest, event.timeStamp)
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> open[event.packageName] = event.timeStamp
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val start = open.remove(event.packageName) ?: continue
                    out += session(event.packageName, start, event.timeStamp)
                }
            }
        }

        prefs.setWatermark(WATERMARK, newest)
        saveOpenSessions(open)
        return out
    }

    private fun session(pkg: String, start: Long, end: Long) = EventEntity(
        kind = "app_session",
        occurredAtMs = start,
        sourceKey = "app:$pkg:$start",
        payloadJson = JSONObject()
            .put("package", pkg)
            .put("start_ms", start)
            .put("end_ms", end)
            .put("duration_ms", end - start)
            .toString(),
    )

    private fun loadOpenSessions(): MutableMap<String, Long> {
        val json = JSONObject(prefs.openUsageJson)
        val map = mutableMapOf<String, Long>()
        json.keys().forEach { key -> map[key] = json.getLong(key) }
        return map
    }

    private fun saveOpenSessions(open: Map<String, Long>) {
        val json = JSONObject()
        open.forEach { (pkg, start) -> json.put(pkg, start) }
        prefs.openUsageJson = json.toString()
    }

    companion object {
        private const val WATERMARK = "usage"
        // The system keeps only a limited window of usage events, so never look back further than this.
        private const val MAX_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000
    }
}
