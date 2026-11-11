@file:OptIn(ExperimentalMaterial3Api::class)

package com.mythos.tracker.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mythos.tracker.day.Cat
import com.mythos.tracker.day.DayData
import com.mythos.tracker.day.DayRepo
import com.mythos.tracker.day.Item
import com.mythos.tracker.day.MediaReader
import com.mythos.tracker.day.Places
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CAT_COLORS = mapOf(
    Cat.APPS to Color(0xFF5B6CFF), Cat.YOUTUBE to Color(0xFFE53935), Cat.WEB to Color(0xFF1E88E5),
    Cat.MONEY to Color(0xFF2E7D32), Cat.PHOTOS to Color(0xFF8E24AA), Cat.PLACES to Color(0xFFEF6C00),
    Cat.CALLS to Color(0xFF00897B), Cat.NOTES to Color(0xFF6D4C41),
)

@Composable
fun DayScreen() {
    val context = LocalContext.current
    var day by remember { mutableLongStateOf(DayRepo.startOfDay(System.currentTimeMillis())) }
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<DayData?>(null) }
    var filter by remember { mutableStateOf<Cat?>(null) }
    var showAllApps by remember { mutableStateOf(false) }
    val places = remember { mutableStateMapOf<String, String>() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reload++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(day, reload) {
        data = runCatching { DayRepo.load(context, day) }.getOrNull()
            ?: DayData(day, emptyList(), 0, 0, emptyList(), 0.0, 0.0, emptyList(), 0, 0, 0, 0, false)
    }
    LaunchedEffect(data) {
        val d = data ?: return@LaunchedEffect
        for (item in d.items) {
            val lat = item.lat ?: continue
            val lng = item.lng ?: continue
            val k = Places.key(lat, lng)
            if (!places.containsKey(k)) Places.name(context, lat, lng)?.let { places[k] = it }
        }
    }

    val isToday = day == DayRepo.startOfDay(System.currentTimeMillis())
    val d = data

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { day = DayRepo.addDays(day, -1) }) { Text("‹ Prev") }
                Text(
                    if (isToday) "Today" else SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(day)),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(enabled = !isToday, onClick = { day = DayRepo.addDays(day, 1) }) { Text("Next ›") }
            }
        }

        if (d == null) {
            item { Text("Loading your day…") }
            return@LazyColumn
        }

        item { Summary(d) }

        if (d.apps.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Screen time by app", style = MaterialTheme.typography.titleMedium)
                        val top = d.apps.first().ms.coerceAtLeast(1)
                        (if (showAllApps) d.apps else d.apps.take(8)).forEach { a ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(a.label, modifier = Modifier.weight(1f))
                                Text("${DayRepo.duration(a.ms)} · ${a.opens}×", style = MaterialTheme.typography.bodySmall)
                            }
                            LinearProgressIndicator(progress = { a.ms.toFloat() / top }, modifier = Modifier.fillMaxWidth())
                        }
                        if (d.apps.size > 8) {
                            TextButton(onClick = { showAllApps = !showAllApps }) {
                                Text(if (showAllApps) "Show less" else "Show all ${d.apps.size} apps")
                            }
                        }
                    }
                }
            }
        }

        if (d.media.isNotEmpty()) {
            item {
                Text("Photos & videos (${d.media.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(d.media) { m -> Thumb(context, m) }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
                Cat.entries.forEach { c ->
                    val n = d.items.count { it.cat == c }
                    FilterChip(selected = filter == c, onClick = { filter = if (filter == c) null else c },
                        label = { Text("${c.label} $n") })
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { reload++ }) { Text("Refresh") }
                OutlinedButton(onClick = { shareDay(context, d, places) }) { Text("Share day as text") }
            }
        }

        val shown = d.items.filter { filter == null || it.cat == filter }
        if (shown.isEmpty()) {
            item { Text("Nothing recorded here yet. Check Settings for anything not allowed.") }
        }
        items(shown) { item -> ItemRow(context, item, places) }
    }
}

@Composable
private fun Summary(d: DayData) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Screen time", DayRepo.duration(d.screenMs), Modifier.weight(1f))
                Stat("Unlocks", d.unlocks.toString(), Modifier.weight(1f))
                Stat("Apps used", d.apps.size.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Spent", "₹" + DayRepo.money(d.spent), Modifier.weight(1f))
                Stat("Received", "₹" + DayRepo.money(d.received), Modifier.weight(1f))
                Stat("Places", d.places.toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Videos", "${d.videosWatched} · ${DayRepo.duration(d.watchMs)}", Modifier.weight(1f))
                Stat("Searches", d.searches.toString(), Modifier.weight(1f))
                Stat("Photos", d.media.size.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ItemRow(context: Context, item: Item, places: Map<String, String>) {
    val color = CAT_COLORS[item.cat] ?: Color.Gray
    val place = if (item.lat != null && item.lng != null) places[Places.key(item.lat, item.lng)] else null
    val click = item.open
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (click != null) Modifier.clickable { open(context, click) } else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                time(item.time) + (item.endTime?.let { " – " + time(it) } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(150.dp),
            )
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 1.dp),
            ) { Text(item.cat.label, color = Color.White, style = MaterialTheme.typography.labelSmall) }
        }
        Text(
            if (item.cat == Cat.PLACES) (place ?: "Location ${"%.4f".format(item.lat)}, ${"%.4f".format(item.lng)}") else item.title,
            style = MaterialTheme.typography.bodyLarge,
        )
        val extra = listOfNotNull(
            if (item.cat == Cat.PLACES) item.title + (if (item.detail.isNotBlank()) " · " + item.detail else "") else item.detail.takeIf { it.isNotBlank() },
            if (item.cat != Cat.PLACES && place != null) "at $place" else null,
        ).joinToString(" · ")
        if (extra.isNotBlank()) Text(extra, style = MaterialTheme.typography.bodySmall, maxLines = 4)
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Thumb(context: Context, m: MediaReader.Media) {
    val bmp by produceState<Bitmap?>(initialValue = null, m.uri) {
        value = withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= 29) {
                runCatching { context.contentResolver.loadThumbnail(m.uri, Size(240, 240), null) }.getOrNull()
            } else null
        }
    }
    Box(
        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0E0E0))
            .clickable { open(context, m.uri) },
        contentAlignment = Alignment.BottomStart,
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = m.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(
            time(m.time).take(5) + if (m.video) " ▶" else "",
            color = Color.White, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.background(Color(0x88000000)).padding(2.dp),
        )
    }
}

private fun time(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))

private fun open(context: Context, uri: Uri) {
    runCatching {
        val mime = if (uri.scheme == "http" || uri.scheme == "https") null else context.contentResolver.getType(uri)
        val i = Intent(Intent.ACTION_VIEW).apply {
            if (mime != null) setDataAndType(uri, mime) else data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(i)
    }
}

private fun shareDay(context: Context, d: DayData, places: Map<String, String>) {
    val sb = StringBuilder()
    sb.append("My day: ").append(SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(d.dayStart))).append('\n')
    sb.append("Screen time ${DayRepo.duration(d.screenMs)}, ${d.unlocks} unlocks, spent ₹${DayRepo.money(d.spent)}, ")
        .append("${d.videosWatched} videos, ${d.searches} searches, ${d.media.size} photos/videos\n\n")
    d.items.forEach { i ->
        val place = if (i.lat != null && i.lng != null) places[Places.key(i.lat, i.lng)] else null
        sb.append(time(i.time)).append(i.endTime?.let { "–" + time(it) } ?: "").append("  [").append(i.cat.label).append("] ")
        sb.append(if (i.cat == Cat.PLACES) (place ?: "location") + " (${i.title.lowercase()})" else i.title)
        if (i.detail.isNotBlank() && i.cat != Cat.NOTES) sb.append(" — ").append(i.detail)
        if (i.cat == Cat.NOTES && i.detail.isNotBlank()) sb.append("\n    ").append(i.detail.replace("\n", "\n    "))
        if (i.cat != Cat.PLACES && place != null) sb.append(" @ ").append(place)
        sb.append('\n')
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sb.toString())
    context.startActivity(Intent.createChooser(send, "Share day").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
