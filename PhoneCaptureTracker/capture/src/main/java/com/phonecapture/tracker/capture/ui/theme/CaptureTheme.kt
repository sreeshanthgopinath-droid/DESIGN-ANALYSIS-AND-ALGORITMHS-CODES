package com.phonecapture.tracker.capture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.CaptureStatus
import com.phonecapture.tracker.core.model.UploadStatus

/**
 * Material 3 baseline purple – the same look as Mythos Tracker – with a few Nothing OS touches:
 * monospace numerals and a red recording dot.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF65558F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    background = Color(0xFFFEF7FF),
    surface = Color(0xFFFEF7FF),
    surfaceVariant = Color(0xFFE7E0EC),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    secondaryContainer = Color(0xFF4A4458),
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
    surfaceVariant = Color(0xFF49454F),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
)

@Composable
fun CaptureTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

object CaptureColors {
    val Recording = Color(0xFFD71921) // Nothing red
    val Success = Color(0xFF2E7D32)
    val Failure = Color(0xFFC62828)
    val Skipped = Color(0xFF757575)
    val Pending = Color(0xFFEF6C00)
    val Cloud = Color(0xFF1565C0)

    fun category(category: CaptureCategory): Color = when (category) {
        CaptureCategory.FRONT -> Color(0xFF7E57C2)
        CaptureCategory.REAR -> Color(0xFF3F51B5)
        CaptureCategory.SCREENSHOT -> Color(0xFF00897B)
    }

    fun status(status: CaptureStatus): Color = when (status) {
        CaptureStatus.SUCCESS -> Success
        CaptureStatus.FAILED -> Failure
        CaptureStatus.SKIPPED -> Skipped
    }

    fun upload(status: UploadStatus): Color = when (status) {
        UploadStatus.UPLOADED -> Cloud
        UploadStatus.UPLOAD_PENDING -> Pending
        UploadStatus.UPLOAD_FAILED -> Failure
        UploadStatus.NOT_QUEUED -> Skipped
    }
}

/** Monospace for numbers and times (Nothing-style), regular type everywhere else. */
val NumberFont: FontFamily = FontFamily.Monospace
