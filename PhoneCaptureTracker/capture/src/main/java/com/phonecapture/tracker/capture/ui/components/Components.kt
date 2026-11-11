package com.phonecapture.tracker.capture.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecapture.tracker.capture.CaptureGraph
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.capture.ui.theme.NumberFont
import com.phonecapture.tracker.core.text.Formatters
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun rememberCaptureGraph(): CaptureGraph {
    val context = LocalContext.current
    return remember(context) { CaptureGraph.get(context) }
}

/** Rounded tinted card, as used for every section in Mythos Tracker. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            }
            content()
        }
    }
}

/** Three-column grid of big numbers with labels. */
@Composable
fun StatGrid(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (value, label) ->
                    Column(Modifier.weight(1f)) {
                        Text(value, fontFamily = NumberFont, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Small coloured label like Mythos Tracker's "Apps" / "Calls" / "Places" tags. */
@Composable
fun TagChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** Pulsing red dot shown whenever capture is active. */
@Composable
fun RecordingDot(active: Boolean, size: Dp = 12.dp) {
    val transition = rememberInfiniteTransition(label = "rec")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "recAlpha",
    )
    Box(
        Modifier
            .size(size)
            .alpha(if (active) alpha else 1f)
            .clip(CircleShape)
            .background(if (active) CaptureColors.Recording else MaterialTheme.colorScheme.outline),
    )
}

/** Mythos-style "‹ Prev   Today   Next ›" header. */
@Composable
fun DayNavigator(date: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val today = LocalDate.now(ZoneId.systemDefault())
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious) { Text("‹ Prev") }
        Text(
            text = if (date == today) "Today" else Formatters.dayTitle(date),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onNext, enabled = date.isBefore(today)) { Text("Next ›") }
    }
}

/** Decrypts and shows a capture thumbnail; shows a placeholder for cloud-only captures. */
@Composable
fun EncryptedThumbnail(record: CaptureRecordEntity, modifier: Modifier = Modifier) {
    val graph = rememberCaptureGraph()
    val bitmap by produceState<Bitmap?>(initialValue = null, record.captureId, record.thumbnailPath) {
        value = graph.thumbnails.load(record)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = "${record.category.displayName} at ${Formatters.time(record.capturedAtUtc, ZoneId.systemDefault())}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Text(
                if (record.thumbnailPath == null) "Cloud\nonly" else "…",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                Formatters.shortTime(record.capturedAtUtc, ZoneId.systemDefault()),
                color = Color.White,
                fontFamily = NumberFont,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(120.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f))
    }
}

fun Context.startActivitySafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

fun Context.shareText(text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivitySafely(Intent.createChooser(send, title))
}
