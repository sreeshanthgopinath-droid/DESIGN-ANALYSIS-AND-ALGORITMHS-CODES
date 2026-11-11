package com.mythos.tracker.day

import android.content.Context
import com.mythos.tracker.data.TrackerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * On-device "ask about my day" engine. No cloud, no account: it reads the events already stored on the
 * phone and answers in plain language using transparent rules (sleep = the long nighttime gap when the
 * phone wasn't touched; wake = first use of the morning; and so on). Everything here is explainable.
 */
object DayInsights {

    // Kinds that prove the person was awake and using the phone (location pings fire during sleep, so
    // they are deliberately excluded).
    private val AWAKE = setOf(
        "app_session", "call", "payment", "web_search", "web_visit", "youtube_watch", "media_play", "diary",
    )

    data class Sleep(val onset: Long?, val wake: Long?, val durationMs: Long?)

    private fun j(s: String) = runCatching { JSONObject(s) }.getOrElse { JSONObject() }
    private fun hm(t: Long) = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))

    /** Awake timestamps inside [from,to], using both ends of each app session. */
    private suspend fun awakeTimes(context: Context, from: Long, to: Long): List<Long> {
        val events = TrackerDatabase.get(context).events().between(from, to)
        val out = ArrayList<Long>()
        for (e in events) {
            if (e.kind !in AWAKE) continue
            out += e.occurredAtMs
            if (e.kind == "app_session") {
                val end = j(e.payloadJson).optLong("end_ms")
                if (end > e.occurredAtMs) out += end
            }
        }
        return out.sorted()
    }

    /** The longest no-phone gap overlapping night, inside the window. */
    private fun longestNightGap(times: List<Long>, nightFrom: Long, nightTo: Long): Pair<Long, Long>? {
        if (times.size < 2) return null
        var best: Pair<Long, Long>? = null
        var bestLen = 0L
        for (i in 0 until times.size - 1) {
            val a = times[i]
            val b = times[i + 1]
            val overlaps = b > nightFrom && a < nightTo
            val len = b - a
            if (overlaps && len > bestLen && len >= 90 * 60 * 1000L) {
                bestLen = len
                best = a to b
            }
        }
        return best
    }

    /** Sleep that STARTED on the evening of [dayStart]. */
    suspend fun sleepStartingThatNight(context: Context, dayStart: Long): Sleep = withContext(Dispatchers.IO) {
        val times = awakeTimes(context, dayStart + 16 * H, dayStart + 36 * H)
        val gap = longestNightGap(times, dayStart + 21 * H, dayStart + 34 * H)
            ?: return@withContext Sleep(null, null, null)
        Sleep(gap.first, gap.second, gap.second - gap.first)
    }

    /** Sleep that ENDED on the morning of [dayStart] (used for "when did I wake"). */
    suspend fun sleepEndingThatMorning(context: Context, dayStart: Long): Sleep = withContext(Dispatchers.IO) {
        val times = awakeTimes(context, dayStart - 8 * H, dayStart + 14 * H)
        val gap = longestNightGap(times, dayStart - 3 * H, dayStart + 12 * H)
            ?: return@withContext Sleep(null, null, null)
        Sleep(gap.first, gap.second, gap.second - gap.first)
    }

    /** Short canned insights shown as chips under the question box. */
    suspend fun highlights(context: Context, dayStart: Long): List<String> = withContext(Dispatchers.IO) {
        val d = DayRepo.load(context, dayStart)
        val out = ArrayList<String>()
        val sleep = sleepStartingThatNight(context, dayStart)
        if (sleep.onset != null) out += "Fell asleep ~${hm(sleep.onset)}"
        val wake = sleepEndingThatMorning(context, dayStart)
        if (wake.wake != null) out += "Woke ~${hm(wake.wake)}"
        if (d.screenMs > 0) out += "Screen ${DayRepo.duration(d.screenMs)}"
        d.apps.firstOrNull()?.let { out += "Most used: ${it.label}" }
        if (d.spent > 0) out += "Spent ₹${DayRepo.money(d.spent)}"
        if (d.videosWatched > 0) out += "${d.videosWatched} videos"
        out
    }

    private val TIME = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", RegexOption.IGNORE_CASE)

    /** Answers one free-text question about the day. */
    suspend fun answer(context: Context, dayStart: Long, question: String): String = withContext(Dispatchers.IO) {
        val q = question.lowercase().trim()
        if (q.isBlank()) return@withContext "Ask me something like “when did I sleep”, “how much did I spend”, or “where was I at 3pm”."
        val d = DayRepo.load(context, dayStart)

        when {
            q.containsAny("sleep", "slept", "go to bed", "went to bed", "bedtime") -> {
                val s = sleepStartingThatNight(context, dayStart)
                if (s.onset == null) "I don't have enough activity that night to tell. Keep the app running and it will learn your nights."
                else "You likely fell asleep around ${hm(s.onset)} — that's when the phone went untouched for " +
                    "${DayRepo.duration(s.durationMs ?: 0)}" + (s.wake?.let { ", until about ${hm(it)}." } ?: ".")
            }
            q.containsAny("wake", "woke", "get up", "got up", "wake up") -> {
                val s = sleepEndingThatMorning(context, dayStart)
                if (s.wake == null) "I can't tell yet — not enough phone activity around that morning."
                else "You likely woke around ${hm(s.wake)} — that's your first phone use after the night's long quiet stretch."
            }
            q.containsAny("screen time", "how long", "usage", "on my phone", "phone time") ->
                "Screen time was ${DayRepo.duration(d.screenMs)} across ${d.apps.size} apps, with ${d.unlocks} unlocks."
            q.containsAny("most used", "top app", "which app", "used the most") ->
                d.apps.firstOrNull()?.let { "Your most used app was ${it.label} — ${DayRepo.duration(it.ms)} over ${it.opens} opens." }
                    ?: "No app usage recorded for this day."
            q.containsAny("spend", "spent", "money", "paid", "pay", "cost") -> {
                val pays = d.items.filter { it.cat == Cat.MONEY && it.title.contains("paid") }
                if (d.spent == 0.0) "No payments recorded this day."
                else "You spent ₹${DayRepo.money(d.spent)} across ${pays.size} payments" +
                    (pays.take(4).joinToString("; ", prefix = ": ") { it.title.removePrefix("₹") }.takeIf { pays.isNotEmpty() } ?: "") + "."
            }
            q.containsAny("receive", "received", "got money", "credited") ->
                if (d.received == 0.0) "No money received this day." else "You received ₹${DayRepo.money(d.received)} this day."
            q.containsAny("video", "youtube", "watch", "watched") -> {
                val vids = d.items.filter { it.cat == Cat.YOUTUBE && !it.title.startsWith("Searched") }
                if (vids.isEmpty()) "No videos recorded this day."
                else "You watched ${d.videosWatched} videos (${DayRepo.duration(d.watchMs)} total): " +
                    vids.take(5).joinToString("; ") { it.title } + (if (vids.size > 5) "…" else "")
            }
            q.containsAny("search", "searched", "google", "looked up") -> {
                val s = d.items.filter { it.title.startsWith("Searched") }
                if (s.isEmpty()) "No searches recorded this day."
                else "You searched ${s.size} times: " + s.take(6).joinToString("; ") { it.title.removePrefix("Searched ") }
            }
            q.containsAny("call", "called", "phone call") -> {
                val c = d.items.filter { it.cat == Cat.CALLS }
                if (c.isEmpty()) "No calls recorded this day." else "There were ${c.size} calls: " +
                    c.take(5).joinToString("; ") { it.title.removePrefix("call · ") }
            }
            q.containsAny("where was i", "where were", "location", "place", "went") -> {
                val t = parseTimeOnDay(q, dayStart)
                val places = d.items.filter { it.cat == Cat.PLACES && it.lat != null }
                if (places.isEmpty()) "No location recorded this day."
                else if (t != null) {
                    val near = places.minByOrNull { abs(it.time - t) }
                    val name = near?.let { Places.name(context, it.lat!!, it.lng!!) }
                    if (near != null) "Around ${hm(t)} you were near ${name ?: "%.4f, %.4f".format(near.lat, near.lng)}."
                    else "No location near ${hm(t)}."
                } else {
                    val named = places.mapNotNull { Places.name(context, it.lat!!, it.lng!!) }.distinct()
                    "You were at ${d.places} place(s)" + (named.take(5).joinToString(", ", prefix = ": ").takeIf { named.isNotEmpty() } ?: "") + "."
                }
            }
            q.containsAny("photo", "picture", "pics", "camera") ->
                if (d.media.isEmpty()) "No photos or videos this day." else "You captured ${d.media.size} photos/videos this day."
            else -> {
                val sleep = sleepStartingThatNight(context, dayStart)
                buildString {
                    append("Here's the day: screen ${DayRepo.duration(d.screenMs)}, ${d.unlocks} unlocks")
                    d.apps.firstOrNull()?.let { append(", most on ${it.label}") }
                    if (d.spent > 0) append(", spent ₹${DayRepo.money(d.spent)}")
                    if (d.videosWatched > 0) append(", ${d.videosWatched} videos")
                    append(", ${d.places} places")
                    sleep.onset?.let { append(". Slept ~${hm(it)}") }
                    append(".")
                }
            }
        }
    }

    private fun parseTimeOnDay(q: String, dayStart: Long): Long? {
        when {
            q.contains("morning") -> return dayStart + 8 * H
            q.contains("afternoon") -> return dayStart + 15 * H
            q.contains("evening") -> return dayStart + 19 * H
            q.contains("night") -> return dayStart + 22 * H
            q.contains("noon") -> return dayStart + 12 * H
        }
        val m = TIME.find(q) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3].lowercase()
        if (h !in 0..23 || min !in 0..59) return null
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        return dayStart + h * H + min * 60_000L
    }

    private fun String.containsAny(vararg keys: String) = keys.any { this.contains(it) }

    private const val H = 60L * 60 * 1000
}
