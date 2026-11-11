package com.mythos.tracker.day

import android.content.Context
import android.net.Uri
import com.mythos.tracker.data.TrackerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

enum class Cat(val label: String) {
    APPS("Apps"), YOUTUBE("YouTube"), WEB("Web"), MONEY("Money"), PHOTOS("Photos"),
    PLACES("Places"), CALLS("Calls"), NOTES("Notes"),
}

data class Item(
    val time: Long,
    val cat: Cat,
    val title: String,
    val detail: String = "",
    val endTime: Long? = null,
    val open: Uri? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

data class AppTime(val pkg: String, val label: String, val ms: Long, val opens: Int)

data class DayData(
    val dayStart: Long,
    val items: List<Item>,
    val screenMs: Long,
    val unlocks: Int,
    val apps: List<AppTime>,
    val spent: Double,
    val received: Double,
    val media: List<MediaReader.Media>,
    val videosWatched: Int,
    val watchMs: Long,
    val searches: Int,
    val places: Int,
    val usageFromSystem: Boolean,
)

object DayRepo {

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(dayStart: Long, n: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_YEAR, n)
    }.timeInMillis

    private class Point(val t: Long, val lat: Double, val lng: Double)
    private class Pay(val t: Long, val amount: Double, val direction: String, val party: String, val source: String)

    suspend fun load(context: Context, dayStart: Long): DayData = withContext(Dispatchers.IO) {
        val dayEnd = addDays(dayStart, 1)
        val db = TrackerDatabase.get(context).events().between(dayStart, dayEnd)
        val items = ArrayList<Item>()

        // 1. Apps, to the second, from Android's usage log (falls back to what was saved earlier).
        val usage = UsageReader.read(context, dayStart, dayEnd)
        var sessions = usage.sessions
        if (sessions.isEmpty()) {
            sessions = db.filter { it.kind == "app_session" }.mapNotNull { e ->
                val p = json(e.payloadJson)
                val pkg = p.optString("package")
                val a = p.optLong("start_ms"); val b = p.optLong("end_ms")
                if (pkg.isNotEmpty() && b > a) UsageReader.Session(pkg, maxOf(a, dayStart), minOf(b, dayEnd)) else null
            }
        }
        val apps = sessions.groupBy { it.pkg }.map { (pkg, list) ->
            AppTime(pkg, UsageReader.label(context, pkg), list.sumOf { it.end - it.start }, list.size)
        }.sortedByDescending { it.ms }
        sessions.forEach { s ->
            items += Item(s.start, Cat.APPS, UsageReader.label(context, s.pkg), duration(s.end - s.start), endTime = s.end)
        }

        // 8. Location points, grouped into stays.
        val points = db.filter { it.kind == "location" }.mapNotNull { e ->
            val p = json(e.payloadJson)
            if (p.has("lat")) Point(e.occurredAtMs, p.optDouble("lat"), p.optDouble("lng", p.optDouble("lon"))) else null
        }.sortedBy { it.t }
        var stays = 0
        var i = 0
        while (i < points.size) {
            val first = points[i]
            var j = i
            while (j + 1 < points.size &&
                Places.distanceM(first.lat, first.lng, points[j + 1].lat, points[j + 1].lng) < 250
            ) j++
            val last = points[j]
            val n = j - i + 1
            items += Item(
                first.t, Cat.PLACES,
                if (last.t > first.t) "Stayed here" else "Was here",
                if (last.t > first.t) "${duration(last.t - first.t)} · $n readings" else "",
                endTime = if (last.t > first.t) last.t else null,
                lat = first.lat, lng = first.lng,
            )
            stays++
            i = j + 1
        }
        fun nearest(t: Long): Point? =
            points.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= 45 * 60 * 1000 }

        // 4. Payments from notifications, then bank SMS that are not already covered.
        val pays = ArrayList<Pay>()
        db.filter { it.kind == "payment" }.forEach { e ->
            val p = json(e.payloadJson)
            pays += Pay(e.occurredAtMs, p.optDouble("amount", 0.0), p.optString("direction"), p.optString("party"),
                appName(p.optString("app")))
        }
        db.filter { it.kind == "sms_bank" }.forEach { e ->
            val body = json(e.payloadJson).optString("body")
            val parsed = PaymentParser.parse(body) ?: return@forEach
            val dup = pays.any { it.amount == parsed.amount && abs(it.t - e.occurredAtMs) < 15 * 60 * 1000 }
            if (!dup) pays += Pay(e.occurredAtMs, parsed.amount, parsed.direction, parsed.party, "Bank SMS")
        }
        var spent = 0.0
        var received = 0.0
        pays.forEach { p ->
            if (p.direction == "debit") spent += p.amount else received += p.amount
            val verb = if (p.direction == "debit") "paid" else "received"
            val who = if (p.party.isNotBlank()) (if (p.direction == "debit") " to ${p.party}" else " from ${p.party}") else ""
            val at = nearest(p.t)
            items += Item(p.t, Cat.MONEY, "₹${money(p.amount)} $verb$who", p.source, lat = at?.lat, lng = at?.lng)
        }

        // 2, 3, calls and notes.
        var videos = 0
        var watchMs = 0L
        var searches = 0
        for (e in db) {
            val p = json(e.payloadJson)
            when (e.kind) {
                "youtube_watch", "media_play", "now_playing" -> {
                    val watched = p.optLong("watched_ms")
                    val end = p.optLong("end_ms").takeIf { it > 0 }
                    if (e.kind == "youtube_watch") { videos++; watchMs += watched }
                    val where = if (e.kind == "youtube_watch") "YouTube" else appName(p.optString("app", p.optString("pkg")))
                    val detail = listOf(p.optString("channel", p.optString("artist")), if (watched > 0) "watched ${duration(watched)}" else "", where)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    items += Item(e.occurredAtMs, Cat.YOUTUBE, p.optString("title"), detail, endTime = end)
                }
                "web_search" -> {
                    searches++
                    val cat = if (p.optString("where") == "YouTube") Cat.YOUTUBE else Cat.WEB
                    items += Item(e.occurredAtMs, cat, "Searched “${p.optString("query")}”", p.optString("where"))
                }
                "web_visit" -> {
                    val url = p.optString("url")
                    items += Item(e.occurredAtMs, Cat.WEB, "Opened $url", "Chrome",
                        open = runCatching { Uri.parse(if (url.startsWith("http")) url else "https://$url") }.getOrNull())
                }
                "call" -> {
                    val who = p.optString("name").ifBlank { p.optString("number") }
                    val secs = p.optLong("duration_s")
                    items += Item(e.occurredAtMs, Cat.CALLS, "${p.optString("type").replaceFirstChar { it.uppercase() }} call · $who",
                        if (secs > 0) duration(secs * 1000) else "not answered",
                        endTime = if (secs > 0) e.occurredAtMs + secs * 1000 else null)
                }
                "diary" -> items += Item(e.occurredAtMs, Cat.NOTES, "Diary", p.optString("text"))
                "shared_note", "essential_note" -> items += Item(e.occurredAtMs, Cat.NOTES, p.optString("title").ifBlank { "Note" }, p.optString("text"))
                "file" -> {
                    val stored = p.optString("stored")
                    val rec = p.optString("category") == "recording"
                    items += Item(
                        e.occurredAtMs, if (rec) Cat.CALLS else Cat.NOTES,
                        (if (rec) "Recording: " else "File: ") + p.optString("name"),
                        p.optString("text").take(500),
                        open = if (stored.isNotEmpty()) runCatching { FileStore.uriFor(context, stored) }.getOrNull() else null,
                    )
                }
            }
        }

        // 7. Photos and videos of the day.
        val media = MediaReader.day(context, dayStart, dayEnd)
        media.forEach { m ->
            val at = nearest(m.time)
            items += Item(m.time, Cat.PHOTOS, m.where, m.name, open = m.uri, lat = at?.lat, lng = at?.lng)
        }

        items.sortBy { it.time }
        DayData(
            dayStart = dayStart,
            items = items,
            screenMs = sessions.sumOf { it.end - it.start },
            unlocks = usage.unlocks,
            apps = apps,
            spent = spent,
            received = received,
            media = media,
            videosWatched = videos,
            watchMs = watchMs,
            searches = searches,
            places = stays,
            usageFromSystem = usage.fromSystemLog,
        )
    }

    private fun json(s: String): JSONObject = runCatching { JSONObject(s) }.getOrElse { JSONObject() }

    private fun appName(pkg: String): String = when {
        pkg.contains("phonepe") -> "PhonePe"
        pkg.contains("paisa") -> "Google Pay"
        pkg.contains("paytm") -> "Paytm"
        pkg.contains("youtube.music") -> "YouTube Music"
        pkg.contains("youtube") -> "YouTube"
        pkg.contains("npci") -> "BHIM"
        else -> pkg.substringAfterLast('.')
    }

    fun money(v: Double): String = if (v == Math.floor(v)) String.format("%,.0f", v) else String.format("%,.2f", v)

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}
