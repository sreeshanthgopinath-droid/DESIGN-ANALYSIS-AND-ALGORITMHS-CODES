package com.mythos.tracker.day

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Turns coordinates into a short place name (street, area, city), cached on the phone. */
object Places {
    private val memory = ConcurrentHashMap<String, String>()

    fun key(lat: Double, lng: Double): String = String.format(Locale.US, "%.3f,%.3f", lat, lng)

    suspend fun name(context: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val k = key(lat, lng)
        memory[k]?.let { return@withContext it }
        val sp = context.getSharedPreferences("places", Context.MODE_PRIVATE)
        sp.getString(k, null)?.let { memory[k] = it; return@withContext it }
        if (!Geocoder.isPresent()) return@withContext null
        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()
        }.getOrNull() ?: return@withContext null
        val parts = listOfNotNull(
            address.thoroughfare,
            address.subLocality ?: address.locality,
            if (address.subLocality != null) address.locality else null,
        ).filter { it.isNotBlank() }.distinct()
        val name = (if (parts.isNotEmpty()) parts.joinToString(", ") else address.getAddressLine(0) ?: "").take(80)
        if (name.isBlank()) return@withContext null
        memory[k] = name
        sp.edit().putString(k, name).apply()
        name
    }

    fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(aLat, aLng, bLat, bLng, r)
        return r[0]
    }
}
