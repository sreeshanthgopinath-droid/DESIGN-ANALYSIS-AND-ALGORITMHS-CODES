package com.phonecapture.tracker.core.image

import kotlin.math.max
import kotlin.math.roundToInt

data class PixelSize(val width: Int, val height: Int) {
    val longEdge: Int get() = max(width, height)
}

object ImageSizing {
    /**
     * Largest power-of-two decode subsample that still leaves the long edge >= [maxLongEdge],
     * so decoding never needs more memory than necessary and never loses more detail than needed.
     */
    fun inSampleSize(width: Int, height: Int, maxLongEdge: Int): Int {
        require(width > 0 && height > 0 && maxLongEdge > 0)
        var sample = 1
        val longEdge = max(width, height)
        while (longEdge / (sample * 2) >= maxLongEdge) sample *= 2
        return sample
    }

    /** Scales down (never up) to fit [maxLongEdge], keeping the aspect ratio. */
    fun fitWithin(width: Int, height: Int, maxLongEdge: Int): PixelSize {
        require(width > 0 && height > 0 && maxLongEdge > 0)
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return PixelSize(width, height)
        val scale = maxLongEdge.toDouble() / longEdge
        return PixelSize(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** Rotation in degrees normalised to 0, 90, 180 or 270. */
    fun normalizeRotation(degrees: Int): Int {
        val r = ((degrees % 360) + 360) % 360
        require(r % 90 == 0) { "Rotation must be a multiple of 90" }
        return r
    }

    fun rotatedSize(size: PixelSize, degrees: Int): PixelSize =
        if (normalizeRotation(degrees) % 180 == 0) size else PixelSize(size.height, size.width)
}

/**
 * Heuristics for screenshots. Android blacks out windows marked FLAG_SECURE (banking apps, some
 * video players) in screen captures. Such frames are still real captures, so they are kept and
 * labelled instead of being reported as failures or hidden.
 */
object FrameAnalysis {
    /** Fraction of the given ARGB pixels whose R, G and B are all <= [threshold]. */
    fun darkFraction(argbPixels: IntArray, threshold: Int = 12): Double {
        if (argbPixels.isEmpty()) return 0.0
        var dark = 0
        for (p in argbPixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (r <= threshold && g <= threshold && b <= threshold) dark++
        }
        return dark.toDouble() / argbPixels.size
    }

    fun isLikelyBlackedOut(argbPixels: IntArray): Boolean = darkFraction(argbPixels) >= 0.995
}
