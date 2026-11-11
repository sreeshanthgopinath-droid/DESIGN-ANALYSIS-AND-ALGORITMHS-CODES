package com.mythos.tracker.day

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.mythos.tracker.Permissions
import java.util.concurrent.ConcurrentHashMap

/** Reads exact app usage for a day straight from Android's usage log (to the second), like screen-time apps do. */
object UsageReader {
    data class Session(val pkg: String, val start: Long, val end: Long)
    class Day(val sessions: List<Session>, val unlocks: Int, val fromSystemLog: Boolean)

    private const val LOOKBACK = 6L * 60 * 60 * 1000
    private const val MERGE_GAP = 3_000L
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val KEYGUARD_HIDDEN = 18
    private const val DEVICE_SHUTDOWN = 26

    fun read(context: Context, dayStart: Long, dayEnd: Long): Day {
        if (!Permissions.hasUsageAccess(context)) return Day(emptyList(), 0, false)
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return Day(emptyList(), 0, false)
        val now = System.currentTimeMillis()
        val end = minOf(dayEnd, now)
        if (end <= dayStart) return Day(emptyList(), 0, true)
        val ignore = ignoredPackages(context)

        val events = usm.queryEvents(dayStart - LOOKBACK, end) ?: return Day(emptyList(), 0, false)
        val e = UsageEvents.Event()
        val open = HashMap<String, Pair<String, Long>>() // pkg/class -> (pkg, start)
        val raw = ArrayList<Session>()
        var unlocks = 0
        var sawInDay = false

        fun closeAll(t: Long) {
            open.values.forEach { (pkg, s) -> raw += Session(pkg, s, t) }
            open.clear()
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = e.timeStamp
            if (t >= dayStart) sawInDay = true
            val pkg = e.packageName ?: continue
            val key = pkg + "/" + (e.className ?: "")
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> if (pkg !in ignore && !open.containsKey(key)) open[key] = pkg to t
                UsageEvents.Event.MOVE_TO_BACKGROUND -> open.remove(key)?.let { (p, s) -> raw += Session(p, s, t) }
                SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN -> closeAll(t)
                KEYGUARD_HIDDEN -> if (t >= dayStart && Build.VERSION.SDK_INT >= 28) unlocks++
            }
        }
        closeAll(end)

        val clipped = raw.mapNotNull { s ->
            val a = maxOf(s.start, dayStart)
            val b = minOf(s.end, end)
            if (b - a >= 1_000) Session(s.pkg, a, b) else null
        }.sortedBy { it.start }

        // Merge back-to-back pieces of the same app (activity switches inside one app).
        val merged = ArrayList<Session>()
        for (s in clipped) {
            val last = merged.lastOrNull()
            if (last != null && last.pkg == s.pkg && s.start - last.end <= MERGE_GAP) {
                merged[merged.size - 1] = Session(last.pkg, last.start, maxOf(last.end, s.end))
            } else {
                merged += s
            }
        }
        return Day(merged, unlocks, sawInDay)
    }

    private fun ignoredPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = runCatching {
            pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return launchers.toSet() + setOf("com.android.systemui", "android")
    }

    private val labels = ConcurrentHashMap<String, String>()

    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }
}
