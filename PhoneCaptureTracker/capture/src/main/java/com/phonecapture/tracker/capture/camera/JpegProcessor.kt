package com.phonecapture.tracker.capture.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.phonecapture.tracker.capture.data.settings.CaptureSettings
import com.phonecapture.tracker.core.image.ImageSizing
import java.io.ByteArrayOutputStream

/**
 * Turns camera JPEGs and screen bitmaps into upright, size-limited JPEGs plus a small thumbnail.
 * Decoding uses power-of-two subsampling so large sensors do not exhaust memory.
 */
class JpegProcessor {

    fun fromJpegBytes(jpeg: ByteArray, rotationDegrees: Int, maxEdgePx: Int, quality: Int): CaptureOutcome.Captured {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The camera returned an image that cannot be decoded" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSizing.inSampleSize(bounds.outWidth, bounds.outHeight, maxEdgePx)
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: error("The camera image could not be decoded")
        return fromBitmap(rotate(decoded, rotationDegrees), maxEdgePx, quality, note = null)
    }

    /** Takes ownership of [source] and recycles it. */
    fun fromBitmap(source: Bitmap, maxEdgePx: Int, quality: Int, note: String?): CaptureOutcome.Captured {
        val target = ImageSizing.fitWithin(source.width, source.height, maxEdgePx)
        val scaled = if (target.width != source.width || target.height != source.height) {
            Bitmap.createScaledBitmap(source, target.width, target.height, true)
        } else {
            source
        }
        try {
            val jpeg = compress(scaled, quality)
            val thumbSize = ImageSizing.fitWithin(scaled.width, scaled.height, CaptureSettings.THUMBNAIL_EDGE)
            val thumbBitmap = Bitmap.createScaledBitmap(scaled, thumbSize.width, thumbSize.height, true)
            val thumbnail = try {
                compress(thumbBitmap, CaptureSettings.THUMBNAIL_QUALITY)
            } finally {
                if (thumbBitmap !== scaled) thumbBitmap.recycle()
            }
            return CaptureOutcome.Captured(jpeg, thumbnail, scaled.width, scaled.height, note)
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ImageSizing.normalizeRotation(degrees)
        if (normalized == 0) return bitmap
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) { "JPEG encoding failed" }
        return out.toByteArray()
    }
}
