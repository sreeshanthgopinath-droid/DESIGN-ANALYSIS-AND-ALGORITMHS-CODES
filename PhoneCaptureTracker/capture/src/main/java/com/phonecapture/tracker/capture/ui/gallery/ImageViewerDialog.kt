package com.phonecapture.tracker.capture.ui.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.data.hasCloudCopy
import com.phonecapture.tracker.capture.ui.components.LabeledValue
import com.phonecapture.tracker.capture.ui.components.TagChip
import com.phonecapture.tracker.capture.ui.theme.CaptureColors
import com.phonecapture.tracker.core.image.ImageSizing
import com.phonecapture.tracker.core.model.VerificationStatus
import com.phonecapture.tracker.core.text.Formatters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface ViewerState {
    data object Loading : ViewerState
    class Loaded(val bitmap: Bitmap) : ViewerState
    class Error(val message: String) : ViewerState
}

@Composable
fun ImageViewerDialog(
    record: CaptureRecordEntity,
    loadImage: suspend (CaptureRecordEntity) -> Result<ByteArray>,
    onDelete: (alsoFromCloud: Boolean) -> Unit,
    onExport: (Uri, ContentResolver) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }

    val state by produceState<ViewerState>(ViewerState.Loading, record.captureId) {
        value = loadImage(record).fold(
            onSuccess = { bytes ->
                withContext(Dispatchers.Default) { decodeSampled(bytes) }
                    ?.let { ViewerState.Loaded(it) }
                    ?: ViewerState.Error("The image could not be decoded.")
            },
            onFailure = { ViewerState.Error("Could not open this capture: ${it.message}") },
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) onExport(uri, context.contentResolver)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagChip(record.category.shortName, CaptureColors.category(record.category))
                    Text(Formatters.dateTime(record.capturedAtUtc, zone), style = MaterialTheme.typography.titleMedium)
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 240.dp), contentAlignment = Alignment.Center) {
                    when (val s = state) {
                        ViewerState.Loading -> CircularProgressIndicator()
                        is ViewerState.Loaded -> Image(
                            bitmap = s.bitmap.asImageBitmap(),
                            contentDescription = "${record.category.displayName} capture",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        is ViewerState.Error -> Text(s.message, color = CaptureColors.Failure)
                    }
                }
                LabeledValue("Captured", Formatters.dateTime(record.capturedAtUtc, zone))
                LabeledValue("File size", "${Formatters.bytes(record.imageSizeBytes)} (encrypted ${Formatters.bytes(record.storedSizeBytes)})")
                if (record.widthPx != null && record.heightPx != null) {
                    LabeledValue("Resolution", "${record.widthPx} × ${record.heightPx}")
                }
                LabeledValue("Capture status", record.captureStatus.name.lowercase().replaceFirstChar { it.uppercase() })
                val verified = if (record.verificationStatus == VerificationStatus.VERIFIED) " · verified" else ""
                LabeledValue("Stored", storageLabel(record).first + verified)
                record.batteryPercent?.let { LabeledValue("Battery", "$it%") }
                record.note?.let { LabeledValue("Note", it) }
                record.uploadError?.let { LabeledValue("Upload error", it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmExport = true }, enabled = state is ViewerState.Loaded) { Text("Export") }
                    OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    Button(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Export an unencrypted copy?") },
            text = {
                Text(
                    "The exported JPEG is not encrypted and can be opened by any app that can read the location " +
                        "you choose. It may show other people or private information.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    exportLauncher.launch(suggestedFileName(record))
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        val cloud = record.hasCloudCopy
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this capture?") },
            text = {
                Text(
                    if (cloud) {
                        "Choose whether to delete only the copy on this phone, or the cloud copy as well."
                    } else {
                        "The encrypted image will be removed from this phone. This cannot be undone."
                    },
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        confirmDelete = false
                        onDelete(false)
                    }) { Text(if (cloud) "Phone only" else "Delete") }
                    if (cloud) {
                        TextButton(onClick = {
                            confirmDelete = false
                            onDelete(true)
                        }) { Text("Phone and cloud") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun decodeSampled(bytes: ByteArray, maxEdgePx: Int = 2048): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = ImageSizing.inSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

private fun suggestedFileName(record: CaptureRecordEntity): String {
    val stamp = FILE_STAMP.format(Instant.ofEpochMilli(record.capturedAtUtc).atZone(ZoneId.systemDefault()))
    return "PhoneCapture_${record.category.fileTag}_$stamp.jpg"
}
