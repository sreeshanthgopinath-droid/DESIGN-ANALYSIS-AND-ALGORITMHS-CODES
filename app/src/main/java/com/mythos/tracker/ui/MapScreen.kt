package com.mythos.tracker.ui

import android.graphics.Color as AColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.day.DayRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A Strava-style trail of where you went that day, drawn on an OpenStreetMap. */
@Composable
fun MapScreen() {
    val context = LocalContext.current
    var day by remember { mutableLongStateOf(DayRepo.startOfDay(System.currentTimeMillis())) }
    var points by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var distanceKm by remember { mutableStateOf(0.0) }

    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
        }
    }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    LaunchedEffect(day) {
        val (pts, dist) = loadTrack(context, day)
        points = pts
        distanceKm = dist
    }

    LaunchedEffect(points) {
        mapView.overlays.clear()
        if (points.isNotEmpty()) {
            val line = Polyline().apply {
                setPoints(points)
                outlinePaint.color = AColor.parseColor("#FC4C02") // Strava orange
                outlinePaint.strokeWidth = 10f
            }
            mapView.overlays.add(line)
            marker(mapView, points.first(), "Start", "#2E7D32")
            if (points.size > 1) marker(mapView, points.last(), "Latest", "#C62828")
            mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.4f), false)
        }
        mapView.invalidate()
    }

    val isToday = day == DayRepo.startOfDay(System.currentTimeMillis())

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { day = DayRepo.addDays(day, -1) }) { Text("‹ Prev") }
            Text(
                if (isToday) "Today" else SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date(day)),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !isToday, onClick = { day = DayRepo.addDays(day, 1) }) { Text("Next ›") }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                if (points.isEmpty()) "No location trail for this day yet."
                else "${points.size} points · about ${"%.1f".format(distanceKm)} km traveled",
                modifier = Modifier.padding(12.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun marker(map: MapView, p: GeoPoint, title: String, color: String) {
    val m = Marker(map)
    m.position = p
    m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    m.title = title
    map.overlays.add(m)
}

private suspend fun loadTrack(context: android.content.Context, dayStart: Long): Pair<List<GeoPoint>, Double> = withContext(Dispatchers.IO) {
    val dayEnd = DayRepo.addDays(dayStart, 1)
    val events = TrackerDatabase.get(context).events().between(dayStart, dayEnd)
    val pts = events.filter { it.kind == "location" }.mapNotNull { e ->
        val p = runCatching { JSONObject(e.payloadJson) }.getOrElse { JSONObject() }
        if (p.has("lat")) GeoPoint(p.optDouble("lat"), p.optDouble("lng", p.optDouble("lon"))) else null
    }
    var dist = 0.0
    for (i in 0 until pts.size - 1) {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(
            pts[i].latitude, pts[i].longitude, pts[i + 1].latitude, pts[i + 1].longitude, r,
        )
        dist += r[0]
    }
    pts to dist / 1000.0
}
