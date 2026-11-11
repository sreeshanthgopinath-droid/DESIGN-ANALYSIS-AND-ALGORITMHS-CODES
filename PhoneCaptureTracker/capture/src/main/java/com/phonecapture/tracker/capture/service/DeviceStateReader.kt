package com.phonecapture.tracker.capture.service

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phonecapture.tracker.core.policy.NetworkKind

/** Read-only snapshot helpers for device state. Nothing here collects identifiers (no IMEI/serial). */
class DeviceStateReader(context: Context) {
    private val context = context.applicationContext

    fun batteryPercent(): Int? {
        val bm = context.getSystemService(BatteryManager::class.java) ?: return null
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }

    /** Screen on and usable (not necessarily unlocked). */
    fun isInteractive(): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /** Lock screen is showing. */
    fun isLocked(): Boolean = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

    fun isPowerSaveMode(): Boolean = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode ?: false

    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    fun freeLocalBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun canPostNotifications(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }

    fun networkKind(): NetworkKind {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.NONE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkKind.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.NONE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkKind.UNMETERED
        } else {
            NetworkKind.METERED
        }
    }
}
