package com.example.keywordalert

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import java.util.Calendar

object Alarms {
    const val ACTION_FIRE = "com.example.keywordalert.FIRE"
    const val ACTION_DONE = "com.example.keywordalert.DONE"
    const val ACTION_SNOOZE = "com.example.keywordalert.SNOOZE"
    const val EXTRA_ID = "id"
    private const val CHANNEL_PREFIX = "alarm_v"

    fun channelId(p: Prefs) = CHANNEL_PREFIX + p.channelVersion

    fun toneUri(p: Prefs): Uri? = p.toneUri?.let { Uri.parse(it) }
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun intervalLabel(min: Int): String = when {
        min < 60 -> "$min min"
        min == 60 -> "1 hour"
        else -> "${min / 60} hours"
    }

    fun ensureChannel(ctx: Context, p: Prefs) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val id = channelId(p)
        nm.notificationChannels
            .filter { (it.id.startsWith(CHANNEL_PREFIX) && it.id != id) || it.id == "keyword_alerts" }
            .forEach { nm.deleteNotificationChannel(it.id) }
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
        if (p.vibrate) ch.vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
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

    fun addReminder(ctx: Context, keyword: String, text: String, app: String) {
        val p = Prefs(ctx)
        val now = System.currentTimeMillis()
        val r = Reminder(p.nextId(), keyword, text, app, now, 0, now)
        p.reminders = p.reminders + r
        ring(ctx, r.id)
    }

    fun ring(ctx: Context, id: Int) {
        val p = Prefs(ctx)
        val list = p.reminders
        val r = list.find { it.id == id } ?: return
        val now = System.currentTimeMillis()

        if (p.quietOn && inQuiet(p, now)) {
            r.nextAt = quietEndTime(p, now)
            p.reminders = list
            schedule(ctx, r)
            return
        }

        r.rings += 1
        if (p.maxRings == 0 || r.rings < p.maxRings) {
            r.nextAt = now + p.intervalMin * 60_000L
            schedule(ctx, r)
        } else {
            r.nextAt = 0L
        }
        p.reminders = list
        show(ctx, p, r)
    }

    fun done(ctx: Context, id: Int) {
        val p = Prefs(ctx)
        ctx.getSystemService(AlarmManager::class.java).cancel(firePending(ctx, id))
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
        p.reminders = p.reminders.filter { it.id != id }
    }

    fun snooze(ctx: Context, id: Int) {
        val p = Prefs(ctx)
        val list = p.reminders
        val r = list.find { it.id == id } ?: return
        r.nextAt = System.currentTimeMillis() + p.snoozeMin * 60_000L
        p.reminders = list
        schedule(ctx, r)
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
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
            schedule(ctx, it)
        }
        p.reminders = list
    }

    private fun firePending(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = firePending(ctx, r.id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            val showApp = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(r.nextAt, showApp), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.nextAt, pi)
        }
    }

    private fun show(ctx: Context, p: Prefs, r: Reminder) {
        ensureChannel(ctx, p)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val open = PendingIntent.getActivity(
            ctx, r.id,
            Intent(ctx, AlarmActivity::class.java)
                .putExtra(EXTRA_ID, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val done = PendingIntent.getBroadcast(
            ctx, r.id,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_ID, r.id),
            flags
        )
        val snooze = PendingIntent.getBroadcast(
            ctx, r.id,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SNOOZE).putExtra(EXTRA_ID, r.id),
            flags
        )

        val count = if (p.maxRings == 0) "Alert ${r.rings}" else "Alert ${r.rings} of ${p.maxRings}"
        val next = if (r.nextAt > 0) " · again in ${intervalLabel(p.intervalMin)}" else " · last alert"

        val b = Notification.Builder(ctx, channelId(p))
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${r.keyword} message · ${r.app}")
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setSubText(count + next)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_menu_close_clear_cancel),
                    "Done", done
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(ctx, android.R.drawable.ic_popup_reminder),
                    "Snooze ${p.snoozeMin} min", snooze
                ).build()
            )
        if (p.fullScreen) b.setFullScreenIntent(open, true)

        val n = b.build()
        // Keep ringing/vibrating until the person reacts.
        if (p.sound || p.vibrate) n.flags = n.flags or Notification.FLAG_INSISTENT
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
