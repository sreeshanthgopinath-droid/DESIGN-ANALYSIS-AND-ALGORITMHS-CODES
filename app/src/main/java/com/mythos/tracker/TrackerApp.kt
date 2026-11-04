package com.mythos.tracker

import android.app.Application
import com.mythos.tracker.work.Scheduler

class TrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Background collection keeps running after the app is closed, reboots, or the UI is never opened.
        Scheduler.start(this)
    }
}
