package com.example.keywordalert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Int,
    var keyword: String,
    var text: String,
    val app: String,
    val pkg: String,
    var created: Long,
    var updated: Long,
    var rings: Int = 0,
    var nextAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("keyword", keyword).put("text", text).put("app", app).put("pkg", pkg)
        .put("created", created).put("updated", updated).put("rings", rings).put("nextAt", nextAt)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            o.getInt("id"), o.optString("keyword"), o.optString("text"), o.optString("app"),
            o.optString("pkg"), o.optLong("created"), o.optLong("updated", o.optLong("created")),
            o.optInt("rings"), o.optLong("nextAt")
        )
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("quiz_alert", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setB(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun i(key: String, def: Int) = sp.getInt(key, def)
    private fun setI(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    private fun l(key: String, def: Long) = sp.getLong(key, def)
    private fun setL(key: String, v: Long) = sp.edit().putLong(key, v).apply()
    private fun s(key: String, def: String) = sp.getString(key, def) ?: def
    private fun setS(key: String, v: String) = sp.edit().putString(key, v).apply()
    private fun list(text: String) = text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    // Matching
    var enabled: Boolean get() = b("enabled", true); set(v) = setB("enabled", v)
    var keywordsText: String get() = s("keywords", "QUIZ, DIGI CAMPUS"); set(v) = setS("keywords", v)
    val keywords: List<String> get() = list(keywordsText)
    var excludeText: String get() = s("exclude", ""); set(v) = setS("exclude", v)
    val excludes: List<String> get() = list(excludeText)
    var wholeWord: Boolean get() = b("wholeWord", false); set(v) = setB("wholeWord", v)
    var matchTitles: Boolean get() = b("matchTitles", true); set(v) = setB("matchTitles", v)

    // Sources
    var watchSms: Boolean get() = b("watchSms", true); set(v) = setB("watchSms", v)
    var watchWhatsapp: Boolean get() = b("watchWhatsapp", true); set(v) = setB("watchWhatsapp", v)
    var watchTelegram: Boolean get() = b("watchTelegram", true); set(v) = setB("watchTelegram", v)
    var watchEmail: Boolean get() = b("watchEmail", true); set(v) = setB("watchEmail", v)
    var watchOther: Boolean get() = b("watchOther", true); set(v) = setB("watchOther", v)
    var checkSummaries: Boolean get() = b("checkSummaries", true); set(v) = setB("checkSummaries", v)
    var checkOngoing: Boolean get() = b("checkOngoing", false); set(v) = setB("checkOngoing", v)

    // Schedule
    var intervalMin: Int get() = i("interval", 60); set(v) = setI("interval", v)
    var maxRings: Int get() = i("maxRings", 0); set(v) = setI("maxRings", v)   // 0 = until Done
    var snoozeMin: Int get() = i("snooze", 10); set(v) = setI("snooze", v)
    var mergePerApp: Boolean get() = b("mergePerApp", true); set(v) = setB("mergePerApp", v)
    var cooldownSec: Int get() = i("cooldown", 20); set(v) = setI("cooldown", v)

    // Alarm style
    var sound: Boolean get() = b("sound", true); set(v) = setB("sound", v)
    var toneUri: String?
        get() = sp.getString("tone", null)
        set(v) = sp.edit().putString("tone", v).apply()
    var maxVolume: Boolean get() = b("maxVolume", true); set(v) = setB("maxVolume", v)
    var vibrate: Boolean get() = b("vibrate", true); set(v) = setB("vibrate", v)
    var strongVibe: Boolean get() = b("strongVibe", false); set(v) = setB("strongVibe", v)
    var fullScreen: Boolean get() = b("fullScreen", true); set(v) = setB("fullScreen", v)
    var ringLimitSec: Int get() = i("ringLimit", 0); set(v) = setI("ringLimit", v)  // 0 = until I react

    // Quiet hours and pause
    var quietOn: Boolean get() = b("quietOn", false); set(v) = setB("quietOn", v)
    var quietStart: Int get() = i("quietStart", 22 * 60); set(v) = setI("quietStart", v)
    var quietEnd: Int get() = i("quietEnd", 7 * 60); set(v) = setI("quietEnd", v)
    var pausedUntil: Long get() = l("pausedUntil", 0L); set(v) = setL("pausedUntil", v)

    // Log and internals
    var logOn: Boolean get() = b("logOn", true); set(v) = setB("logOn", v)
    var logAll: Boolean get() = b("logAll", true); set(v) = setB("logAll", v)
    var logJson: String get() = s("log", "[]"); set(v) = setS("log", v)
    var seenJson: String get() = s("seen", "{}"); set(v) = setS("seen", v)
    var lastSeen: Long get() = l("lastSeen", 0L); set(v) = setL("lastSeen", v)
    var channelVersion: Int get() = i("channelVersion", 1); set(v) = setI("channelVersion", v)

    var reminders: List<Reminder>
        get() {
            val arr = try { JSONArray(s("reminders", "[]")) } catch (e: Exception) { JSONArray() }
            return (0 until arr.length()).map { Reminder.fromJson(arr.getJSONObject(it)) }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it.toJson()) }
            setS("reminders", arr.toString())
        }

    fun nextId(): Int {
        val id = i("nextId", 1000)
        setI("nextId", if (id > 900000) 1000 else id + 1)
        return id
    }
}

data class LogEntry(val t: Long, val app: String, val text: String, val result: String)

object ActivityLog {
    private const val MAX = 150

    fun add(ctx: Context, app: String, text: String, result: String) {
        val p = Prefs(ctx)
        if (!p.logOn) return
        val old = try { JSONArray(p.logJson) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        out.put(
            JSONObject().put("t", System.currentTimeMillis()).put("app", app)
                .put("text", text.replace('\n', ' ').take(160)).put("r", result)
        )
        for (i in 0 until minOf(old.length(), MAX - 1)) out.put(old.get(i))
        p.logJson = out.toString()
    }

    fun entries(p: Prefs): List<LogEntry> {
        val arr = try { JSONArray(p.logJson) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            LogEntry(o.optLong("t"), o.optString("app"), o.optString("text"), o.optString("r"))
        }
    }

    fun clear(p: Prefs) { p.logJson = "[]" }
}

/**
 * Remembers which messages already caused an alarm, per notification.
 * A message is identified by its arrival time + text, so the same words sent again
 * later count as a new message. Memory for a notification is dropped 2 minutes
 * after that notification is removed.
 */
object SeenStore {
    private const val FORGET_MS = 2 * 60 * 1000L

    fun load(p: Prefs): JSONObject {
        val o = try { JSONObject(p.seenJson) } catch (e: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        val stale = ArrayList<String>()
        o.keys().forEach { k ->
            val removed = o.optJSONObject(k)?.optLong("removed") ?: 0L
            if (o.optJSONObject(k) == null || (removed > 0 && now - removed > FORGET_MS)) stale.add(k)
        }
        stale.forEach { o.remove(it) }
        return o
    }

    fun has(o: JSONObject, key: String, id: String): Boolean {
        val a = o.optJSONObject(key)?.optJSONArray("ids") ?: return false
        for (i in 0 until a.length()) if (a.optString(i) == id) return true
        return false
    }

    fun add(o: JSONObject, key: String, id: String) {
        val entry = o.optJSONObject(key) ?: JSONObject().also { o.put(key, it) }
        val ids = entry.optJSONArray("ids") ?: JSONArray()
        val out = JSONArray()
        val start = maxOf(0, ids.length() - 199)
        for (i in start until ids.length()) out.put(ids.get(i))
        out.put(id)
        entry.put("ids", out)
        entry.put("removed", 0L)
    }

    fun markPresent(o: JSONObject, key: String) {
        o.optJSONObject(key)?.put("removed", 0L)
    }

    fun markRemoved(o: JSONObject, key: String) {
        o.optJSONObject(key)?.put("removed", System.currentTimeMillis())
    }
}
