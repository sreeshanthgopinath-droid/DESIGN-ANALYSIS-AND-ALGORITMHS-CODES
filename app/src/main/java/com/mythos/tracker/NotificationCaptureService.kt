package com.mythos.tracker

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.day.PaymentParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 1. Payments: reads PhonePe / Google Pay / Paytm notifications and records amount, direction and payee.
 * 2. YouTube: records each video you play (title, channel, start time and how long it actually played).
 */
class NotificationCaptureService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracked = mutableListOf<Tracked>()
    private val playing = HashMap<String, Playing>()
    private var sessionManager: MediaSessionManager? = null

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private class Playing(
        val pkg: String,
        val key: String,
        val start: Long,
        val title: String,
        val channel: String,
        var playedMs: Long,
        var since: Long?,
    )

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
    }

    override fun onListenerConnected() {
        val msm = getSystemService(MediaSessionManager::class.java) ?: return
        sessionManager = msm
        val me = ComponentName(this, NotificationCaptureService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, me)
            attach(msm.getActiveSessions(me))
        } catch (_: SecurityException) {
        }
    }

    override fun onListenerDisconnected() {
        playing.keys.toList().forEach { finish(it) }
        detachAll()
    }

    override fun onDestroy() {
        playing.keys.toList().forEach { finish(it) }
        detachAll()
        scope.cancel()
        super.onDestroy()
    }

    // ---- payments -------------------------------------------------------------------------

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (!listed(Prefs(this).paymentApps, pkg)) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val p = PaymentParser.parse("$title. $text") ?: return
        val payload = JSONObject()
            .put("app", pkg).put("title", title).put("text", text)
            .put("amount", p.amount).put("direction", p.direction).put("party", p.party)
        val event = EventEntity(
            kind = "payment",
            occurredAtMs = sbn.postTime,
            sourceKey = "pay:$pkg:${p.amount}:${p.party}:${sbn.postTime / 120_000}",
            payloadJson = payload.toString(),
        )
        scope.launch { TrackerDatabase.get(applicationContext).events().insertAll(listOf(event)) }
    }

    // ---- YouTube / media ------------------------------------------------------------------

    private fun attach(list: List<MediaController>) {
        val apps = Prefs(this).mediaApps
        for (c in list) {
            val pkg = c.packageName ?: continue
            if (!listed(apps, pkg)) continue
            if (tracked.any { it.controller.sessionToken == c.sessionToken }) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = onMeta(pkg, metadata, c.playbackState)
                override fun onPlaybackStateChanged(state: PlaybackState?) = onState(pkg, state)
                override fun onSessionDestroyed() {
                    finish(pkg)
                    tracked.removeAll { it.controller.sessionToken == c.sessionToken }
                }
            }
            try {
                c.registerCallback(cb)
                tracked += Tracked(c, cb)
                onMeta(pkg, c.metadata, c.playbackState)
            } catch (_: Exception) {
            }
        }
    }

    private fun onMeta(pkg: String, m: MediaMetadata?, state: PlaybackState?) {
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        if (title.isBlank()) return
        if (playing[pkg]?.title == title) return
        finish(pkg)
        val now = System.currentTimeMillis()
        val channel = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val p = Playing(pkg, "media:$pkg:$now", now, title, channel, 0L,
            if (state?.state == PlaybackState.STATE_PLAYING) now else null)
        playing[pkg] = p
        write(p, ended = false)
    }

    private fun onState(pkg: String, state: PlaybackState?) {
        val p = playing[pkg] ?: return
        val now = System.currentTimeMillis()
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val since = p.since
        if (isPlaying && since == null) {
            p.since = now
        } else if (!isPlaying && since != null) {
            p.playedMs += now - since
            p.since = null
            write(p, ended = false)
        }
    }

    private fun finish(pkg: String) {
        val p = playing.remove(pkg) ?: return
        val since = p.since
        if (since != null) {
            p.playedMs += System.currentTimeMillis() - since
            p.since = null
        }
        write(p, ended = true)
    }

    private fun write(p: Playing, ended: Boolean) {
        val now = System.currentTimeMillis()
        val kind = if (p.pkg.contains("youtube")) "youtube_watch" else "media_play"
        val payload = JSONObject()
            .put("app", p.pkg).put("title", p.title).put("channel", p.channel)
            .put("watched_ms", p.playedMs).put("end_ms", if (ended) now else 0L)
        val event = EventEntity(kind = kind, occurredAtMs = p.start, sourceKey = p.key, payloadJson = payload.toString())
        scope.launch { TrackerDatabase.get(applicationContext).events().upsert(event) }
    }

    private fun detachAll() {
        tracked.forEach { t -> runCatching { t.controller.unregisterCallback(t.callback) } }
        tracked.clear()
        runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
    }

    private fun listed(csv: String, pkg: String): Boolean = csv.split(',').any { it.trim() == pkg }
}
