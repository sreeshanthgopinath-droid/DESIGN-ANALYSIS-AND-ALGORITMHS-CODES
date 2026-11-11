package com.mythos.tracker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Records where you are every 15 minutes, all day. Runs as a foreground service (with a small
 * permanent notification) because Android otherwise delays background location for hours.
 */
class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: FusedLocationProviderClient? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { save(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Permissions.hasLocation(this)) {
            stopSelf()
            return
        }
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        requestUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        client?.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val c = LocationServices.getFusedLocationProviderClient(this)
        client = c
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(10 * 60 * 1000L)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching { c.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
        // One reading right away so the day starts with a point.
        runCatching {
            c.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).addOnSuccessListener { it?.let(::save) }
        }
    }

    private fun save(location: Location) {
        val ts = location.time
        Prefs(this).lastLocationMs = System.currentTimeMillis()
        val event = EventEntity(
            kind = "location",
            occurredAtMs = ts,
            sourceKey = "loc:$ts",
            payloadJson = JSONObject()
                .put("lat", location.latitude)
                .put("lng", location.longitude)
                .put("accuracy_m", location.accuracy.toDouble())
                .toString(),
        )
        scope.launch { TrackerDatabase.get(applicationContext).events().insertAll(listOf(event)) }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Day tracking", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Mythos is recording your day")
            .setContentText("Location every 15 minutes")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIFICATION_ID = 2001
        private const val INTERVAL_MS = 15 * 60 * 1000L

        /** Safe to call any time; does nothing if Android does not allow starting it right now. */
        fun start(context: Context) {
            if (!Permissions.hasLocation(context)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
            }
        }
    }
}
