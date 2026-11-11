package com.mythos.tracker

import android.content.Context
import java.util.UUID

/** Small key-value store for settings, sync status and collector watermarks. */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("mythos_tracker", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(value) = sp.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(value) = sp.edit().putString("token", value.trim()).apply()

    val deviceId: String
        get() = sp.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("device_id", it).apply()
        }

    /** Payment apps whose notifications are read for amount and payee. */
    var paymentApps: String
        get() = sp.getString("payment_apps", DEFAULT_PAYMENT_APPS) ?: DEFAULT_PAYMENT_APPS
        set(value) = sp.edit().putString("payment_apps", value).apply()

    /** Apps whose currently playing video or song is recorded. */
    var mediaApps: String
        get() = sp.getString("media_apps", DEFAULT_MEDIA_APPS) ?: DEFAULT_MEDIA_APPS
        set(value) = sp.edit().putString("media_apps", value).apply()

    var lastCollectMs: Long
        get() = sp.getLong("last_collect_ms", 0L)
        set(value) = sp.edit().putLong("last_collect_ms", value).apply()

    var lastCollectSummary: String
        get() = sp.getString("last_collect_summary", "") ?: ""
        set(value) = sp.edit().putString("last_collect_summary", value).apply()

    var collectProgress: String
        get() = sp.getString("collect_progress", "") ?: ""
        set(value) = sp.edit().putString("collect_progress", value).apply()

    /** Time of the most recent location fix from the always-on location tracker. */
    var lastLocationMs: Long
        get() = sp.getLong("last_location_ms", 0L)
        set(value) = sp.edit().putLong("last_location_ms", value).apply()

    var lastSyncMs: Long
        get() = sp.getLong("last_sync_ms", 0L)
        set(value) = sp.edit().putLong("last_sync_ms", value).apply()

    var lastSyncResult: String
        get() = sp.getString("last_sync_result", "") ?: ""
        set(value) = sp.edit().putString("last_sync_result", value).apply()

    fun watermark(name: String): Long = sp.getLong("wm_$name", 0L)
    fun setWatermark(name: String, value: Long) = sp.edit().putLong("wm_$name", value).apply()

    var openUsageJson: String
        get() = sp.getString("open_usage", "{}") ?: "{}"
        set(value) = sp.edit().putString("open_usage", value).apply()

    companion object {
        const val DEFAULT_PAYMENT_APPS =
            "com.phonepe.app,com.google.android.apps.nbu.paisa.user,net.one97.paytm,in.org.npci.upiapp"
        const val DEFAULT_MEDIA_APPS =
            "com.google.android.youtube,com.google.android.apps.youtube.music"
    }
}
