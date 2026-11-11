package com.phonecapture.tracker.capture.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.phonecapture.tracker.core.model.CaptureCategory
import com.phonecapture.tracker.core.model.FailureReason
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Takes exactly one still photo with one camera, then releases it.
 *
 * - Uses CameraX ImageCapture only (no preview, no video, no audio).
 * - Binds to the capture service's lifecycle, captures into memory, and unbinds immediately, so
 *   the camera is held for a few seconds per attempt and the system privacy indicator is shown
 *   while it is in use. Nothing here hides or suppresses that indicator.
 * - Every failure is returned as [CaptureOutcome.NotCaptured] with a reason; nothing is retried
 *   silently and nothing is reported as a success unless a decodable image was produced.
 */
abstract class CameraCaptureController(
    context: Context,
    private val processor: JpegProcessor,
    private val hasCameraPermission: () -> Boolean,
) {
    protected val appContext: Context = context.applicationContext

    abstract val category: CaptureCategory

    /** Which camera to use. CameraX resolves this to one logical camera; individual lenses are not assumed. */
    protected abstract val cameraSelector: CameraSelector

    suspend fun isAvailable(): Boolean = try {
        cameraProvider().hasCamera(cameraSelector)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun capture(
        owner: LifecycleOwner,
        jpegQuality: Int,
        maxEdgePx: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): CaptureOutcome {
        if (!hasCameraPermission()) {
            return CaptureOutcome.NotCaptured(FailureReason.CAMERA_PERMISSION_DENIED, "android.permission.CAMERA is not granted")
        }
        val provider = try {
            cameraProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CaptureOutcome.NotCaptured(FailureReason.CAMERA_UNAVAILABLE, "CameraX could not initialise: $e")
        }
        val present = try {
            provider.hasCamera(cameraSelector)
        } catch (e: CameraInfoUnavailableException) {
            false
        }
        if (!present) {
            return CaptureOutcome.NotCaptured(FailureReason.CAMERA_UNAVAILABLE, "The device reports no ${category.displayName.lowercase()}")
        }

        val raw = withContext(Dispatchers.Main) { takeRawPicture(provider, owner, jpegQuality, maxEdgePx, timeoutMillis) }
        return when (raw) {
            is RawResult.Failed -> CaptureOutcome.NotCaptured(raw.reason, raw.detail)
            is RawResult.Image -> withContext(Dispatchers.Default) {
                try {
                    processor.fromJpegBytes(raw.jpeg, raw.rotationDegrees, maxEdgePx, jpegQuality)
                } catch (e: OutOfMemoryError) {
                    CaptureOutcome.NotCaptured(FailureReason.CAMERA_ERROR, "Not enough memory to process the photo")
                } catch (e: Exception) {
                    CaptureOutcome.NotCaptured(FailureReason.CAMERA_ERROR, "Photo processing failed: $e")
                }
            }
        }
    }

    private sealed interface RawResult {
        class Image(val jpeg: ByteArray, val rotationDegrees: Int) : RawResult
        class Failed(val reason: FailureReason, val detail: String) : RawResult
    }

    /** Must run on the main thread (CameraX binding and LiveData observation). */
    private suspend fun takeRawPicture(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        jpegQuality: Int,
        maxEdgePx: Int,
        timeoutMillis: Long,
    ): RawResult {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            return RawResult.Failed(FailureReason.FOREGROUND_SERVICE_UNAVAILABLE, "The capture service was already stopped")
        }
        val imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(jpegQuality.coerceIn(1, 100))
            .setResolutionSelector(resolutionSelector(maxEdgePx))
            .setTargetRotation(displayRotation())
            .build()

        val camera = try {
            provider.bindToLifecycle(owner, cameraSelector, imageCapture)
        } catch (e: IllegalArgumentException) {
            return RawResult.Failed(FailureReason.CAMERA_UNAVAILABLE, "Camera could not be selected: ${e.message}")
        } catch (e: IllegalStateException) {
            return RawResult.Failed(FailureReason.CAMERA_BUSY, "Camera could not be bound: ${e.message}")
        }

        val cameraState = camera.cameraInfo.cameraState
        var lastError: CameraState.StateError? = null
        var observer: Observer<CameraState>? = null
        try {
            val result = withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine<RawResult> { cont ->
                    val stateObserver = Observer<CameraState> { state ->
                        val error = state.error ?: return@Observer
                        lastError = error
                        if (error.type == CameraState.ErrorType.CRITICAL && cont.isActive) {
                            cont.resume(
                                RawResult.Failed(
                                    CameraErrorMapper.fromStateError(error.code),
                                    "Camera error ${CameraErrorMapper.stateErrorName(error.code)}",
                                ),
                            )
                        }
                    }
                    observer = stateObserver
                    cameraState.observeForever(stateObserver)

                    imageCapture.takePicture(
                        ContextCompat.getMainExecutor(appContext),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val raw = try {
                                    image.toRawResult()
                                } catch (e: Exception) {
                                    RawResult.Failed(FailureReason.CAMERA_ERROR, "Could not read the photo buffer: $e")
                                } finally {
                                    image.close()
                                }
                                if (cont.isActive) cont.resume(raw)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                if (cont.isActive) {
                                    cont.resume(
                                        RawResult.Failed(
                                            CameraErrorMapper.fromImageCaptureError(exception.imageCaptureError),
                                            "ImageCapture error ${exception.imageCaptureError}: ${exception.message}",
                                        ),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (result != null) return result
            val error = lastError
            return if (error != null) {
                RawResult.Failed(
                    CameraErrorMapper.fromStateError(error.code),
                    "No photo within ${timeoutMillis / 1000}s after ${CameraErrorMapper.stateErrorName(error.code)}",
                )
            } else {
                RawResult.Failed(FailureReason.CAPTURE_TIMEOUT, "No photo within ${timeoutMillis / 1000}s")
            }
        } finally {
            observer?.let { cameraState.removeObserver(it) }
            provider.unbind(imageCapture)
        }
    }

    private fun ImageProxy.toRawResult(): RawResult {
        if (format == ImageFormat.JPEG && planes.isNotEmpty()) {
            val buffer = planes[0].buffer
            buffer.rewind()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            return RawResult.Image(bytes, imageInfo.rotationDegrees)
        }
        // Other formats: let CameraX convert, then encode losslessly enough for re-encoding later.
        val bitmap = toBitmap()
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bitmap.recycle()
        return RawResult.Image(out.toByteArray(), imageInfo.rotationDegrees)
    }

    private fun resolutionSelector(maxEdgePx: Int): ResolutionSelector = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(
                Size(maxEdgePx, maxEdgePx * 3 / 4),
                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
            ),
        )
        .build()

    private fun displayRotation(): Int =
        appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0

    private suspend fun cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e.cause ?: e)
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
    }
}

/** Front-facing camera. */
class FrontCameraController(
    context: Context,
    processor: JpegProcessor,
    hasCameraPermission: () -> Boolean,
) : CameraCaptureController(context, processor, hasCameraPermission) {
    override val category: CaptureCategory = CaptureCategory.FRONT
    override val cameraSelector: CameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
}

/**
 * Rear camera. DEFAULT_BACK_CAMERA is the device's primary back camera as exposed to apps (on
 * multi-lens phones this is usually a logical camera fronting the main sensor). Additional rear
 * lenses (ultra-wide, macro) are not assumed to be independently accessible and are not used.
 */
class RearCameraController(
    context: Context,
    processor: JpegProcessor,
    hasCameraPermission: () -> Boolean,
) : CameraCaptureController(context, processor, hasCameraPermission) {
    override val category: CaptureCategory = CaptureCategory.REAR
    override val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
}
