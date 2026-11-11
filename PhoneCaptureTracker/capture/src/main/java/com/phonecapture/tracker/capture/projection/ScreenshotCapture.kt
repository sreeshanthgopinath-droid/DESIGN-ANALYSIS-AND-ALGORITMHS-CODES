package com.phonecapture.tracker.capture.projection

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import com.phonecapture.tracker.capture.camera.CaptureOutcome
import com.phonecapture.tracker.capture.camera.JpegProcessor
import com.phonecapture.tracker.capture.service.DeviceStateReader
import com.phonecapture.tracker.core.image.FrameAnalysis
import com.phonecapture.tracker.core.model.FailureReason
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class DisplaySize(val width: Int, val height: Int, val densityDpi: Int)

/**
 * Owns one MediaProjection session and grabs single frames from it.
 *
 * Android rules this class follows:
 *  - The projection is obtained only from a fresh user consent (one token, used once).
 *  - The callback is registered before the VirtualDisplay is created (required on Android 14+).
 *  - Exactly one VirtualDisplay is created per projection; it is resized, never re-created.
 *  - Between captures the VirtualDisplay has no surface attached, so no frames are rendered.
 *  - When Android stops the session (lock screen on Android 15 QPR1+, user tapped the status-bar
 *    chip, consent revoked), all resources are released and the session is NOT restarted; a new
 *    consent from the user is required.
 */
class ScreenshotCapture(
    context: Context,
    private val deviceState: DeviceStateReader,
    private val processor: JpegProcessor,
    private val onSessionEnded: (reason: FailureReason, detail: String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handlerThread = HandlerThread("pct-screen-capture").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val lock = Any()
    private val captureMutex = Mutex()
    private val pendingFrame = AtomicReference<Image?>(null)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var readerSize: DisplaySize? = null

    @Volatile
    private var stopRequestedByApp = false

    val isActive: Boolean get() = synchronized(lock) { virtualDisplay != null }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            val byApp = stopRequestedByApp
            releaseResources()
            if (!byApp) {
                val lockedOrOff = deviceState.isLocked() || !deviceState.isInteractive()
                val detail = if (lockedOrOff) {
                    "Android ended screen sharing because the phone was locked (Android 15 QPR1 and later do this)."
                } else {
                    "Screen sharing was ended by Android or from the status-bar screen-sharing chip."
                }
                onSessionEnded(FailureReason.PROJECTION_STOPPED, detail)
            }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            synchronized(lock) {
                val density = readerSize?.densityDpi ?: currentDisplaySize().densityDpi
                if (width > 0 && height > 0) resizeLocked(DisplaySize(width, height, density))
            }
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) = Unit
    }

    /**
     * Must be called from the mediaProjection foreground service, after startForeground().
     * @return the captured display size, or an error message.
     */
    fun start(resultCode: Int, data: Intent): Result<DisplaySize> = synchronized(lock) {
        stopInternal()
        stopRequestedByApp = false
        val manager = appContext.getSystemService(MediaProjectionManager::class.java)
            ?: return Result.failure(IllegalStateException("MediaProjectionManager unavailable"))
        val obtained: MediaProjection? = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            return Result.failure(e)
        } catch (e: IllegalStateException) {
            return Result.failure(e)
        }
        val mp = obtained ?: return Result.failure(IllegalStateException("Consent was not granted"))

        mp.registerCallback(callback, handler)
        val size = currentDisplaySize()
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        val vd = try {
            mp.createVirtualDisplay(
                "PhoneCaptureTracker-screen",
                size.width,
                size.height,
                size.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null, // no surface until a capture is requested
                null,
                handler,
            )
        } catch (e: RuntimeException) {
            null
        }
        if (vd == null) {
            reader.close()
            stopRequestedByApp = true
            mp.stop()
            return Result.failure(IllegalStateException("Android refused to create the capture display"))
        }
        projection = mp
        virtualDisplay = vd
        imageReader = reader
        readerSize = size
        Result.success(size)
    }

    /** Ends the session at the user's request. */
    fun stop() = synchronized(lock) { stopInternal() }

    suspend fun capture(jpegQuality: Int, maxEdgePx: Int, timeoutMillis: Long = FRAME_TIMEOUT_MILLIS): CaptureOutcome =
        captureMutex.withLock {
            if (!isActive) {
                return CaptureOutcome.NotCaptured(FailureReason.PROJECTION_NOT_ACTIVE, "No active screen-sharing session")
            }
            if (!deviceState.isInteractive()) {
                return CaptureOutcome.NotCaptured(FailureReason.SCREEN_OFF, "The screen was off at the scheduled time")
            }
            if (deviceState.isLocked()) {
                return CaptureOutcome.NotCaptured(FailureReason.SCREEN_LOCKED, "The lock screen was showing at the scheduled time")
            }
            val frame = when (val result = acquireFrame(timeoutMillis)) {
                is FrameResult.Frame -> result.bitmap
                is FrameResult.Ended -> return CaptureOutcome.NotCaptured(FailureReason.PROJECTION_STOPPED, result.detail)
                FrameResult.NoFrame -> return CaptureOutcome.NotCaptured(
                    FailureReason.NO_FRAME,
                    "No screen frame within ${timeoutMillis}ms",
                )
            }
            withContext(Dispatchers.Default) {
                try {
                    val note = if (looksBlackedOut(frame)) {
                        "Mostly black: the visible app may block screenshots (secure content)."
                    } else {
                        null
                    }
                    processor.fromBitmap(frame, maxEdgePx, jpegQuality, note)
                } catch (e: OutOfMemoryError) {
                    CaptureOutcome.NotCaptured(FailureReason.UNKNOWN, "Not enough memory to encode the screenshot")
                } catch (e: Exception) {
                    CaptureOutcome.NotCaptured(FailureReason.UNKNOWN, "Screenshot encoding failed: $e")
                }
            }
        }

    private sealed interface FrameResult {
        class Frame(val bitmap: Bitmap) : FrameResult
        data object NoFrame : FrameResult
        class Ended(val detail: String) : FrameResult
    }

    private suspend fun acquireFrame(timeoutMillis: Long): FrameResult {
        val signal = CompletableDeferred<Unit>()
        val display: VirtualDisplay
        val reader: ImageReader
        synchronized(lock) {
            val vd = virtualDisplay ?: return FrameResult.Ended("Screen sharing ended before the capture")
            val current = currentDisplaySize()
            if (current != readerSize) resizeLocked(current) // rotation or resolution change
            val r = imageReader ?: return FrameResult.Ended("Screen capture buffer unavailable")
            pendingFrame.getAndSet(null)?.close()
            drain(r)
            r.setOnImageAvailableListener({ source ->
                val image = try {
                    source.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    null
                }
                if (image != null) {
                    pendingFrame.getAndSet(image)?.close()
                    signal.complete(Unit)
                }
            }, handler)
            try {
                vd.surface = r.surface // start rendering
            } catch (e: RuntimeException) {
                r.setOnImageAvailableListener(null, null)
                return FrameResult.Ended("The capture display is no longer valid: ${e.message}")
            }
            display = vd
            reader = r
        }
        try {
            withTimeoutOrNull(timeoutMillis) { signal.await() }
        } finally {
            synchronized(lock) {
                if (virtualDisplay === display) runCatching { display.surface = null } // pause rendering
                runCatching { reader.setOnImageAvailableListener(null, null) }
            }
        }
        val image = pendingFrame.getAndSet(null) ?: return FrameResult.NoFrame
        return try {
            FrameResult.Frame(image.toCroppedBitmap())
        } catch (e: Exception) {
            FrameResult.NoFrame
        } finally {
            image.close()
        }
    }

    private fun drain(reader: ImageReader) {
        while (true) {
            val image = try {
                reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                null
            } ?: break
            image.close()
        }
    }

    private fun Image.toCroppedBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowPaddingPixels = (plane.rowStride - pixelStride * width) / pixelStride
        val padded = Bitmap.createBitmap(width + rowPaddingPixels, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPaddingPixels == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun looksBlackedOut(bitmap: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(bitmap, 54, 120, false)
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== bitmap) sample.recycle()
        return FrameAnalysis.isLikelyBlackedOut(pixels)
    }

    /** Caller holds [lock]. */
    private fun resizeLocked(size: DisplaySize) {
        val vd = virtualDisplay ?: return
        if (size == readerSize) return
        runCatching { vd.surface = null }
        pendingFrame.getAndSet(null)?.close()
        imageReader?.close()
        imageReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        vd.resize(size.width, size.height, size.densityDpi)
        readerSize = size
    }

    /** Caller holds [lock]. */
    private fun stopInternal() {
        val mp = projection
        stopRequestedByApp = true
        releaseResources()
        mp?.stop()
    }

    private fun releaseResources() = synchronized(lock) {
        pendingFrame.getAndSet(null)?.close()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        readerSize = null
        projection = null
    }

    private fun currentDisplaySize(): DisplaySize {
        val metrics = DisplayMetrics()
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
        } else {
            metrics.setTo(appContext.resources.displayMetrics)
        }
        return DisplaySize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    companion object {
        const val FRAME_TIMEOUT_MILLIS = 2_500L
    }
}
