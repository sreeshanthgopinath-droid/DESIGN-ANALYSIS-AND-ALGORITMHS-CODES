package com.phonecapture.tracker

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.phonecapture.tracker.capture.CaptureGraph

class PhoneCaptureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // App Check must be installed before any other Firebase call. It only applies when this
        // build includes a google-services.json (otherwise the app runs in local-only mode).
        if (BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(this).isNotEmpty()) {
            try {
                AppCheckInstaller.install()
            } catch (e: Exception) {
                Log.e("PhoneCaptureTracker", "App Check could not be installed", e)
            }
        }
        CaptureGraph.get(this).initialize()
    }
}
