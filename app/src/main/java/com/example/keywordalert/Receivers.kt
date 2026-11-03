package com.example.keywordalert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(Alarms.EXTRA_ID, -1)
        when (intent.action) {
            Alarms.ACTION_FIRE -> Alarms.ring(ctx, id)
            Alarms.ACTION_DONE -> Alarms.done(ctx, id)
            Alarms.ACTION_SNOOZE -> Alarms.snooze(ctx, id)
            Alarms.ACTION_SILENCE -> Alarms.silence(ctx, id)
        }
    }
}

/** Alarms are wiped on reboot or app update; put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Alarms.rescheduleAll(ctx)
        Watchdog.schedule(ctx)
        Watchdog.rebind(ctx)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Watchdog.rebind(ctx)
    }
}
