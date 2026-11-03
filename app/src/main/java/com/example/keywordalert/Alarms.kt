package com.example.keywordalert

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.service.notification.NotificationListenerService
import java.util.Calendar

object Alarms {
    const val ACTION_FIRE = "com.example.keywordalert.FIRE"
    const val ACTION_DONE = "com.example.keywordalert.DONE"
    const val ACTION_SNOOZE = "com.example.keywordalert.SNOOZE"
    const val ACTION_SILENCE = "com.example.keywordalert.SILENCE"
    const val EXTRA_ID = "id"
    private const val CHANNEL_PREFIX = "alarm_v"
    private const val SILENT_CHANNEL = "silenced"

    fun channelId(p: Prefs) = CHANNEL_PREFIX + p.channelVersion

    fun toneUri(p: Prefs): Uri? = p.toneUri?.let { Uri.parse(it) }
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun intervalLabel(min: Int): String = when {
        min < 60 -> "$min min"
        min % 60 == 0 -> if (min == 60) "1 hour" else "${min / 60} hours"
        else -> "${min / 60} h ${min % 60} min"
    }

    fun secondsLabel(sec: Int): String = when {
        sec < 60 -> "$sec sec"
        sec % 60 == 0 -> "${sec / 60} min"
        else -> "${sec / 60} min ${sec % 60} sec"
    }

    fun ensureChannel(ctx: Context, p: Prefs) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val id = channelId(p)
        nm.notificationChannels
            .filter { (it.id.startsWith(CHANNEL_PREFIX) && it.id != id) || it.id == "keyword_alerts" }
            .forEach { nm.deleteNotificationChannel(it.id) }

        if (nm.getNotificationChannel(SILENT_CHANNEL) == null) {
            val s = NotificationChannel(SILENT_CHANNEL, "Silenced reminders", NotificationManager.IMPORTANCE_LOW)
            s.setSound(null, null)
            s.enableVibration(false)
            nm.createNotificationChannel(s)
        }
        if (nm.getNotificationChannel(id) != null) return

        val ch = NotificationChannel(id, "Keyword alarms", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Alarms for messages that contain your keywords"
        if (p.sound) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            ch.setSound(toneUri(p), attrs)
        } else {
            ch.setSound(null, null)
        }
        ch.enableVibration(p.vibrate)
        if (p.vibrate) {
            ch.vibrationPattern = if (p.strongVibe) longArrayOf(0, 1500, 300, 1500, 300, 1500, 300, 1500)
            else longArrayOf(0, 1000, 500, 1000, 500, 1000)
        }
        ch.setBypassDnd(true)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    /** Sound/vibration live on the channel, so a change needs a fresh channel. */
    fun settingsChanged(ctx: Context) {
        val p = Prefs(ctx)
        p.channelVersion = p.channelVersion + 1
        ensureChannel(ctx, p)
    }

    /** Called for every new matching message. Returns a short result for the activity log. */
    fun onMatch(ctx: Context, keyword: String, text: String, app: String, pkg: String): String {
        val p = Prefs(ctx)
        val now = System.currentTimeMillis()
        val list = p.reminders.toMutableList()
        val same = list.find { it.pkg == pkg }

        if (same != null && p.cooldownSec > 0 && now - same.updated < p.cooldownSec * 1000L) {
            same.text = text
            same.updated = now
            p.reminders = list
            return "matched $keyword (already ringing for this app)"
        }
        if (same != null && p.mergePerApp) {
            same.keyword = keyword
            same.text = text
            same.created = now
            same.updated = now
            same.rings = 0
            p.reminders = list
            ring(ctx, same.id)
            return "ALARM ($keyword)"
        }
        val r = Reminder(p.nextId(), keyword, text, app, pkg, now, now, 0, now)
        list.add(r)
        p.reminders = list
        ring(ctx, r.id)
        return "ALARM ($keyword)"
    }

    fun ring(ctx: Context, id: Int) {
        val p = Prefs(ctx)
        val list = p.reminders
        val r = list.find { it.id == id } ?: return
        val now = System.currentTimeMillis()

        if (p.quietOn && inQuiet(p, now)) {
            r.nextAt = quietEndTime(p, now)
            p.reminders = list
            scheduleFire(ctx, r.id, r.nextAt)
            return
        }

        r.rings += 1
        if (p.maxRings == 0 || r.rings < p.maxRings) {
            r.nextAt = now + p.intervalMin * 60_000L
            scheduleFire(ctx, r.id, r.nextAt)
        } else {
            r.nextAt = 0L
        }
        p.reminders = list

        if (p.sound && p.maxVolume) {
            try {
                val am = ctx.getSystemService(AudioManager::class.java)
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (e: Exception) {
            }
        }
        show(ctx, p, r, loud = true)
        if (p.ringLimitSec > 0) scheduleExact(ctx, now + p.ringLimitSec * 1000L, pending(ctx, ACTION_SILENCE, r.id))
    }

    fun done(ctx: Context, id: Int) {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, ACTION_FIRE, id))
        am.cancel(pending(ctx, ACTION_SILENCE, id))
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
        p.reminders = p.reminders.filter { it.id != id }
    }

    fun snooze(ctx: Context, id: Int) {
        val p = Prefs(ctx)
        val list = p.reminders
        val r = list.find { it.id == id } ?: return
        r.nextAt = System.currentTimeMillis() + p.snoozeMin * 60_000L
        p.reminders = list
        scheduleFire(ctx, r.id, r.nextAt)
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, ACTION_SILENCE, id))
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }

    /** Stop the sound now but keep the reminder (it rings again at the next interval). */
    fun silence(ctx: Context, id: Int) {
        val p = Prefs(ctx)
        val r = p.reminders.find { it.id == id } ?: return
        show(ctx, p, r, loud = false)
    }

    fun stopAll(ctx: Context) {
        Prefs(ctx).reminders.forEach { done(ctx, it.id) }
    }

    fun rescheduleAll(ctx: Context) {
        val p = Prefs(ctx)
        val list = p.reminders
        val now = System.currentTimeMillis()
        list.filter { it.nextAt > 0 }.forEach {
            if (it.nextAt < now) it.nextAt = now + 10_000L
            scheduleFire(ctx, it.id, it.nextAt)
        }
        p.reminders = list
    }

    private fun pending(ctx: Context, action: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun canExact(am: AlarmManager) = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

    private fun scheduleFire(ctx: Context, id: Int, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, ACTION_FIRE, id)
        if (canExact(am)) {
            val showApp = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showApp), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun scheduleExact(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (canExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun show(ctx: Context, p: Prefs, r: Reminder, loud: Boolean) {
        ensureChannel(ctx, p)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        val open = PendingIntent.getActivity(
            ctx, r.id,
            Intent(ctx, AlarmActivity::class.java)
                .putExtra(EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(icon: Int, label: String, act: String) = Notification.Action.Builder(
            Icon.createWithResource(ctx, icon), label, pending(ctx, act, r.id)
        ).build()

        val count = if (p.maxRings == 0) "Alert ${r.rings}" else "Alert ${r.rings} of ${p.maxRings}"
        val next = if (r.nextAt > 0) " · again in ${intervalLabel(p.intervalMin)}" else " · last alert"

        val b = Notification.Builder(ctx, if (loud) channelId(p) else SILENT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${r.keyword} · ${r.app}")
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setSubText(count + next)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Done", ACTION_DONE))
            .addAction(action(android.R.drawable.ic_popup_reminder, "Snooze ${p.snoozeMin}m", ACTION_SNOOZE))

        if (loud) {
            b.addAction(action(android.R.drawable.ic_lock_silent_mode, "Silence", ACTION_SILENCE))
            if (p.fullScreen) b.setFullScreenIntent(open, true)
        } else {
            b.setOnlyAlertOnce(true)
            nm.cancel(r.id)   // stops a ringing alarm
        }

        val n = b.build()
        if (loud && (p.sound || p.vibrate)) n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(r.id, n)
    }

    private fun minuteOfDay(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun inQuiet(p: Prefs, t: Long): Boolean {
        val m = minuteOfDay(t)
        val s = p.quietStart
        val e = p.quietEnd
        return when {
            s == e -> false
            s < e -> m in s until e
            else -> m >= s || m < e
        }
    }

    private fun quietEndTime(p: Prefs, t: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = t
            set(Calendar.HOUR_OF_DAY, p.quietEnd / 60)
            set(Calendar.MINUTE, p.quietEnd % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= t) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
}

/** Keeps the message reader connected; some phones silently disconnect it. */
object Watchdog {
    fun schedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                ctx, 7, Intent(ctx, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(
                AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi
            )
        } catch (e: Exception) {
        }
    }

    fun rebind(ctx: Context) {
        try {
            NotificationListenerService.requestRebind(ComponentName(ctx, KeywordListenerService::class.java))
        } catch (e: Exception) {
        }
    }

    fun hardReconnect(ctx: Context) {
        try {
            val pm = ctx.packageManager
            val cn = ComponentName(ctx, KeywordListenerService::class.java)
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
        }
        rebind(ctx)
    }
}
