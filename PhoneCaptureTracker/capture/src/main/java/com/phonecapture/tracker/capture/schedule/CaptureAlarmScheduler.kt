package com.phonecapture.tracker.capture.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.phonecapture.tracker.capture.diagnostics.DiagnosticLogger
import com.phonecapture.tracker.core.model.DiagnosticType

/**
 * Wakes the device for each 30-minute slot. A coroutine `delay` alone is not enough because the
 * CPU sleeps in Doze. Exact alarms are used when the user allowed "Alarms & reminders"; otherwise
 * an inexact allow-while-idle alarm is used and the dashboard explains that attempts may drift.
 *
 * The alarm only signals the already-running foreground service. It never starts camera access
 * from the background on its own.
 */
class CaptureAlarmScheduler(context: Context, private val diagnostics: DiagnosticLogger) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private var warnedInexact = false

    /** @return true if an exact alarm was set. */
    fun schedule(atUtcMillis: Long): Boolean {
        val manager = alarmManager ?: return false
        val pending = pendingIntent()
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exactAllowed) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atUtcMillis, pending)
                return true
            } catch (e: SecurityException) {
                // Permission revoked between the check and the call; fall through to inexact.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atUtcMillis, pending)
        if (!warnedInexact) {
            warnedInexact = true
            diagnostics.log(
                DiagnosticType.EXACT_ALARM_UNAVAILABLE,
                "Exact alarms are not allowed, so attempts may start several minutes late while the phone sleeps",
                "Settings → Apps → Special app access → Alarms & reminders",
            )
        }
        return false
    }

    fun cancel() {
        alarmManager?.cancel(pendingIntent())
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, CaptureAlarmReceiver::class.java).setAction(CaptureAlarmReceiver.ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST_CODE = 7310
    }
}

/** Keeps the CPU awake for the duration of one capture cycle (bounded by a timeout). */
class CycleWakeLock(context: Context) {
    private val wakeLock: PowerManager.WakeLock? = context.applicationContext
        .getSystemService(PowerManager::class.java)
        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneCaptureTracker:cycle")
        ?.apply { setReferenceCounted(false) }

    fun acquire(timeoutMillis: Long = 3 * 60_000L) {
        wakeLock?.acquire(timeoutMillis)
    }

    fun release() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }
}
