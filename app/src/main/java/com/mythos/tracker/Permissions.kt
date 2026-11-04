package com.mythos.tracker

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat

data class PermissionSnapshot(
    val location: Boolean,
    val backgroundLocation: Boolean,
    val media: Boolean,
    val usage: Boolean,
    val batteryUnrestricted: Boolean,
)

object Permissions {
    val location: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    val media: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** What to ask for: the media permissions plus, on Android 10+, access to photo location tags. */
    val mediaRequest: Array<String> =
        if (Build.VERSION.SDK_INT >= 29) media + Manifest.permission.ACCESS_MEDIA_LOCATION else media

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(context: Context): Boolean = location.any { granted(context, it) }

    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasMedia(context: Context): Boolean = media.all { granted(context, it) }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun snapshot(context: Context) = PermissionSnapshot(
        location = hasLocation(context),
        backgroundLocation = hasBackgroundLocation(context),
        media = hasMedia(context),
        usage = hasUsageAccess(context),
        batteryUnrestricted = isBatteryUnrestricted(context),
    )
}
