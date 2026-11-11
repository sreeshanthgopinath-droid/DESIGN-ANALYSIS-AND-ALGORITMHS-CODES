package com.phonecapture.tracker.capture.data.settings

import android.content.Context
import androidx.core.content.edit
import com.phonecapture.tracker.core.policy.RetentionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-configurable behaviour. Defaults favour privacy and battery: Wi-Fi only, cloud off. */
data class CaptureSettings(
    val captureFront: Boolean = true,
    val captureRear: Boolean = true,
    val captureScreen: Boolean = true,
    /** JPEG quality 50–100. 85 is the "balanced" default. */
    val jpegQuality: Int = DEFAULT_JPEG_QUALITY,
    /** Long edge of saved images in pixels; larger photos are scaled down. */
    val maxImageEdgePx: Int = DEFAULT_MAX_EDGE,
    val cloudSyncEnabled: Boolean = false,
    val allowMobileDataUpload: Boolean = false,
    val deleteLocalAfterVerifiedUpload: Boolean = false,
    /** 0 = keep forever. */
    val localRetentionDays: Int = 0,
    /** 0 = keep forever. */
    val cloudRetentionDays: Int = 0,
    val cloudQuotaBytes: Long = DEFAULT_CLOUD_QUOTA_BYTES,
    val onboardingCompleted: Boolean = false,
) {
    val anyCategoryEnabled: Boolean get() = captureFront || captureRear || captureScreen
    val anyCameraEnabled: Boolean get() = captureFront || captureRear

    fun sanitized(): CaptureSettings = copy(
        jpegQuality = jpegQuality.coerceIn(50, 100),
        maxImageEdgePx = maxImageEdgePx.coerceIn(720, 8192),
        localRetentionDays = localRetentionDays.coerceAtLeast(0),
        cloudRetentionDays = cloudRetentionDays.coerceAtLeast(0),
        cloudQuotaBytes = cloudQuotaBytes.coerceAtLeast(100L * 1024 * 1024),
    )

    fun toRetentionSettings() = RetentionSettings(
        localRetentionDays = localRetentionDays,
        cloudRetentionDays = cloudRetentionDays,
        deleteLocalAfterVerifiedUpload = deleteLocalAfterVerifiedUpload,
        cloudSyncEnabled = cloudSyncEnabled,
    )

    companion object {
        const val DEFAULT_JPEG_QUALITY = 85
        const val DEFAULT_MAX_EDGE = 2560
        const val DEFAULT_CLOUD_QUOTA_BYTES = 5L * 1024 * 1024 * 1024
        const val THUMBNAIL_EDGE = 360
        const val THUMBNAIL_QUALITY = 70
    }
}

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<CaptureSettings> = state.asStateFlow()
    val current: CaptureSettings get() = state.value

    @Synchronized
    fun update(transform: (CaptureSettings) -> CaptureSettings): CaptureSettings {
        val next = transform(state.value).sanitized()
        write(next)
        state.value = next
        return next
    }

    private fun read(): CaptureSettings {
        val d = CaptureSettings()
        return CaptureSettings(
            captureFront = prefs.getBoolean(K_FRONT, d.captureFront),
            captureRear = prefs.getBoolean(K_REAR, d.captureRear),
            captureScreen = prefs.getBoolean(K_SCREEN, d.captureScreen),
            jpegQuality = prefs.getInt(K_QUALITY, d.jpegQuality),
            maxImageEdgePx = prefs.getInt(K_MAX_EDGE, d.maxImageEdgePx),
            cloudSyncEnabled = prefs.getBoolean(K_CLOUD, d.cloudSyncEnabled),
            allowMobileDataUpload = prefs.getBoolean(K_MOBILE, d.allowMobileDataUpload),
            deleteLocalAfterVerifiedUpload = prefs.getBoolean(K_DELETE_AFTER_UPLOAD, d.deleteLocalAfterVerifiedUpload),
            localRetentionDays = prefs.getInt(K_LOCAL_RETENTION, d.localRetentionDays),
            cloudRetentionDays = prefs.getInt(K_CLOUD_RETENTION, d.cloudRetentionDays),
            cloudQuotaBytes = prefs.getLong(K_QUOTA, d.cloudQuotaBytes),
            onboardingCompleted = prefs.getBoolean(K_ONBOARDING, d.onboardingCompleted),
        ).sanitized()
    }

    private fun write(s: CaptureSettings) {
        prefs.edit {
            putBoolean(K_FRONT, s.captureFront)
            putBoolean(K_REAR, s.captureRear)
            putBoolean(K_SCREEN, s.captureScreen)
            putInt(K_QUALITY, s.jpegQuality)
            putInt(K_MAX_EDGE, s.maxImageEdgePx)
            putBoolean(K_CLOUD, s.cloudSyncEnabled)
            putBoolean(K_MOBILE, s.allowMobileDataUpload)
            putBoolean(K_DELETE_AFTER_UPLOAD, s.deleteLocalAfterVerifiedUpload)
            putInt(K_LOCAL_RETENTION, s.localRetentionDays)
            putInt(K_CLOUD_RETENTION, s.cloudRetentionDays)
            putLong(K_QUOTA, s.cloudQuotaBytes)
            putBoolean(K_ONBOARDING, s.onboardingCompleted)
        }
    }

    private companion object {
        const val PREFS = "pct_capture_settings"
        const val K_FRONT = "capture_front"
        const val K_REAR = "capture_rear"
        const val K_SCREEN = "capture_screen"
        const val K_QUALITY = "jpeg_quality"
        const val K_MAX_EDGE = "max_edge"
        const val K_CLOUD = "cloud_sync"
        const val K_MOBILE = "mobile_data_upload"
        const val K_DELETE_AFTER_UPLOAD = "delete_after_verified_upload"
        const val K_LOCAL_RETENTION = "local_retention_days"
        const val K_CLOUD_RETENTION = "cloud_retention_days"
        const val K_QUOTA = "cloud_quota_bytes"
        const val K_ONBOARDING = "onboarding_completed"
    }
}
