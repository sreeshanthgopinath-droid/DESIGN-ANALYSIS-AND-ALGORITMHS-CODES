package com.example.keywordalert

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val id: Int,
    val keyword: String,
    val text: String,
    val app: String,
    val created: Long,
    var rings: Int = 0,
    var nextAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("keyword", keyword).put("text", text).put("app", app)
        .put("created", created).put("rings", rings).put("nextAt", nextAt)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            o.getInt("id"), o.optString("keyword"), o.optString("text"), o.optString("app"),
            o.optLong("created"), o.optInt("rings"), o.optLong("nextAt")
        )
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("quiz_alert", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setB(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun i(key: String, def: Int) = sp.getInt(key, def)
    private fun setI(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    var enabled: Boolean get() = b("enabled", true); set(v) = setB("enabled", v)

    var keywordsText: String
        get() = sp.getString("keywords", "QUIZ, DIGI CAMPUS") ?: ""
        set(v) = sp.edit().putString("keywords", v).apply()
    val keywords: List<String>
        get() = keywordsText.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    var watchSms: Boolean get() = b("watchSms", true); set(v) = setB("watchSms", v)
    var watchWhatsapp: Boolean get() = b("watchWhatsapp", true); set(v) = setB("watchWhatsapp", v)
    var watchTelegram: Boolean get() = b("watchTelegram", true); set(v) = setB("watchTelegram", v)
    var watchEmail: Boolean get() = b("watchEmail", true); set(v) = setB("watchEmail", v)
    var watchOther: Boolean get() = b("watchOther", true); set(v) = setB("watchOther", v)

    var intervalMin: Int get() = i("interval", 60); set(v) = setI("interval", v)
    var maxRings: Int get() = i("maxRings", 0); set(v) = setI("maxRings", v)   // 0 = until Done
    var snoozeMin: Int get() = i("snooze", 10); set(v) = setI("snooze", v)

    var sound: Boolean get() = b("sound", true); set(v) = setB("sound", v)
    var vibrate: Boolean get() = b("vibrate", true); set(v) = setB("vibrate", v)
    var fullScreen: Boolean get() = b("fullScreen", true); set(v) = setB("fullScreen", v)
    var toneUri: String?
        get() = sp.getString("tone", null)
        set(v) = sp.edit().putString("tone", v).apply()

    var quietOn: Boolean get() = b("quietOn", false); set(v) = setB("quietOn", v)
    var quietStart: Int get() = i("quietStart", 22 * 60); set(v) = setI("quietStart", v)
    var quietEnd: Int get() = i("quietEnd", 7 * 60); set(v) = setI("quietEnd", v)

    var channelVersion: Int get() = i("channelVersion", 1); set(v) = setI("channelVersion", v)

    var reminders: List<Reminder>
        get() {
            val arr = JSONArray(sp.getString("reminders", "[]") ?: "[]")
            return (0 until arr.length()).map { Reminder.fromJson(arr.getJSONObject(it)) }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it.toJson()) }
            sp.edit().putString("reminders", arr.toString()).apply()
        }

    fun nextId(): Int {
        val id = i("nextId", 1000)
        setI("nextId", if (id > 900000) 1000 else id + 1)
        return id
    }
}
