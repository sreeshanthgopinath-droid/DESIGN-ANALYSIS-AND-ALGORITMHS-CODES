package com.mythos.tracker.collect

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.mythos.tracker.Permissions
import com.mythos.tracker.data.EventEntity
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Takes one location fix per call. Background access needs "Allow all the time" location permission. */
class LocationCollector(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun collect(): EventEntity? {
        if (!Permissions.hasLocation(context)) return null

        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = client.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            CancellationTokenSource().token,
        ).await() ?: return null

        val ts = location.time
        return EventEntity(
            kind = "location",
            occurredAtMs = ts,
            sourceKey = "loc:$ts",
            payloadJson = JSONObject()
                .put("lat", location.latitude)
                .put("lng", location.longitude)
                .put("accuracy_m", location.accuracy.toDouble())
                .toString(),
        )
    }
}
