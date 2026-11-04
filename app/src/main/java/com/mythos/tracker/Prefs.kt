package com.mythos.tracker

import android.content.Context
import java.util.UUID

/**
 * Small key-value store for settings, sync status and collector watermarks.
 * The access token is kept in plain SharedPreferences (app-private storage).
 */
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

    /** Comma-separated app package names whose notifications and now-playing info are recorded. */
    var notificationPackages: String
        get() = sp.getString("notif_pkgs", DEFAULT_NOTIFICATION_PACKAGES) ?: DEFAULT_NOTIFICATION_PACKAGES
        set(value) = sp.edit().putString("notif_pkgs", value).apply()

    /** Comma-separated path fragments; audio files whose path contains one are uploaded. */
    var recordingFolders: String
        get() = sp.getString("rec_folders", DEFAULT_RECORDING_FOLDERS) ?: DEFAULT_RECORDING_FOLDERS
        set(value) = sp.edit().putString("rec_folders", value).apply()

    var lastCollectMs: Long
        get() = sp.getLong("last_collect_ms", 0L)
        set(value) = sp.edit().putLong("last_collect_ms", value).apply()

    /** One line per collection run, e.g. "apps 12 · location 1 · media 340" or a failure reason. */
    var lastCollectSummary: String
        get() = sp.getString("last_collect_summary", "") ?: ""
        set(value) = sp.edit().putString("last_collect_summary", value).apply()

    var lastSyncMs: Long
        get() = sp.getLong("last_sync_ms", 0L)
        set(value) = sp.edit().putLong("last_sync_ms", value).apply()

    var lastSyncResult: String
        get() = sp.getString("last_sync_result", "") ?: ""
        set(value) = sp.edit().putString("last_sync_result", value).apply()

    /** Per-collector high-water marks (timestamps already read from the device). */
    fun watermark(name: String): Long = sp.getLong("wm_$name", 0L)
    fun setWatermark(name: String, value: Long) = sp.edit().putLong("wm_$name", value).apply()

    /** App sessions that were foregrounded but not yet backgrounded, stored as JSON {package: startMs}. */
    var openUsageJson: String
        get() = sp.getString("open_usage", "{}") ?: "{}"
        set(value) = sp.edit().putString("open_usage", value).apply()

    companion object {
        const val DEFAULT_NOTIFICATION_PACKAGES =
            "com.google.android.youtube,com.google.android.apps.youtube.music,com.phonepe.app," +
                "com.google.android.apps.nbu.paisa.user,com.android.chrome,com.google.android.keep"
        const val DEFAULT_RECORDING_FOLDERS = "Recordings,Call,Essential,Voice"
    }
}
