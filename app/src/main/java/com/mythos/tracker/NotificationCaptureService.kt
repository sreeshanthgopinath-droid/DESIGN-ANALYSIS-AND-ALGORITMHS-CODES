package com.mythos.tracker

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Records notifications from the chosen apps (YouTube, PhonePe, Chrome, Keep, Essential Space by default)
 * and what is playing in them (video or song titles) while notification access is granted.
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracked = mutableListOf<Tracked>()
    private val lastTitle = mutableMapOf<String, String>()
    private var sessionManager: MediaSessionManager? = null

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
    }

    override fun onListenerConnected() {
        val msm = getSystemService(MediaSessionManager::class.java)
        sessionManager = msm
        val me = ComponentName(this, NotificationCaptureService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me)
            attach(msm.getActiveSessions(me))
        } catch (_: SecurityException) {
            // notification access was revoked while connecting
        }
    }

    override fun onListenerDisconnected() {
        detachAll()
    }

    override fun onDestroy() {
        detachAll()
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!isWatched(prefs, sbn.packageName)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val payload = JSONObject().put("pkg", sbn.packageName).put("title", title).put("text", text)
            .put("category", n.category ?: "")
        store(
            EventEntity(
                kind = "notification",
                occurredAtMs = sbn.postTime,
                sourceKey = "notif:${sbn.packageName}:${sbn.postTime}:${(title + text).hashCode()}",
                payloadJson = payload.toString(),
            ),
        )
    }

    private fun attach(list: List<MediaController>) {
        val prefs = Prefs(this)
        for (c in list) {
            val pkg = c.packageName ?: continue
            if (!isWatched(prefs, pkg)) continue
            if (tracked.any { it.controller.sessionToken == c.sessionToken }) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = emit(pkg, metadata)
            }
            try {
                c.registerCallback(cb)
                tracked += Tracked(c, cb)
                emit(pkg, c.metadata)
            } catch (_: Exception) {
            }
        }
    }

    private fun emit(pkg: String, m: MediaMetadata?) {
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank() || lastTitle[pkg] == title) return
        lastTitle[pkg] = title
        val now = System.currentTimeMillis()
        val payload = JSONObject().put("pkg", pkg).put("title", title)
            .put("artist", m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty())
            .put("album", m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty())
            .put("duration_ms", m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L)
        store(
            EventEntity(
                kind = "now_playing",
                occurredAtMs = now,
                sourceKey = "np:$pkg:$now:${title.hashCode()}",
                payloadJson = payload.toString(),
            ),
        )
    }

    private fun detachAll() {
        tracked.forEach { t -> runCatching { t.controller.unregisterCallback(t.callback) } }
        tracked.clear()
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
    }

    private fun isWatched(prefs: Prefs, pkg: String): Boolean {
        if (pkg == packageName) return false
        if (pkg.contains("essential", ignoreCase = true) || pkg.startsWith("com.nothing")) return true
        return prefs.notificationPackages.split(',').any { it.trim() == pkg }
    }

    private fun store(e: EventEntity) {
        scope.launch {
            val dao = TrackerDatabase.get(applicationContext).events()
            if (dao.insertAll(listOf(e)).any { it != -1L }) Scheduler.requestSync(applicationContext)
        }
    }
}
