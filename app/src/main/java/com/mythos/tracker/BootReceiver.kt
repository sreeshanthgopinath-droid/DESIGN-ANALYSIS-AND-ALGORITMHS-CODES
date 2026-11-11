package com.mythos.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mythos.tracker.work.Scheduler

/** Restarts tracking after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.start(context)
        LocationService.start(context)
    }
}
