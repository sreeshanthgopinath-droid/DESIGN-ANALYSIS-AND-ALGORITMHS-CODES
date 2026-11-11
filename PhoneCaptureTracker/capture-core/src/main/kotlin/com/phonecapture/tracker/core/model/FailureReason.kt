package com.phonecapture.tracker.core.model

/**
 * Why an attempt did not produce an image. Every value carries a message that is shown to the
 * user as-is, so the wording explains what happened and, where possible, what to do.
 */
enum class FailureReason(
    val userMessage: String,
    /** True when the attempt is recorded as SKIPPED (intentional) rather than FAILED. */
    val isSkip: Boolean = false,
) {
    CAMERA_PERMISSION_DENIED("Camera permission is not granted. Allow it in Settings → Permissions."),
    CAMERA_UNAVAILABLE("This camera is not available on this phone."),
    CAMERA_BUSY("Another app was using the camera."),
    CAMERA_DISABLED("Android blocked camera access (camera privacy toggle, a device policy, or a background restriction)."),
    CAMERA_ERROR("The camera reported an error while capturing."),
    CAPTURE_TIMEOUT("The camera did not deliver a photo in time."),
    FOREGROUND_SERVICE_UNAVAILABLE("Android did not allow the capture service to run. Open the app to resume."),
    PROJECTION_NOT_ACTIVE("No screen-sharing session is active. Open the app and start screen sharing."),
    PROJECTION_STOPPED("Android ended the screen-sharing session. Open the app to approve a new one."),
    CONSENT_REVOKED("Screen-sharing permission was revoked."),
    SCREEN_LOCKED("The phone was locked, so the screen was not captured.", isSkip = true),
    SCREEN_OFF("The screen was off, so it was not captured.", isSkip = true),
    NO_FRAME("Android did not deliver a screen frame in time."),
    LOCAL_STORAGE_EXHAUSTED("Not enough free storage on the phone to save the image."),
    ENCRYPTION_FAILED("The image could not be encrypted, so it was discarded and not saved."),
    VAULT_LOCKED("The encryption key is not available. Restore it with your recovery code."),
    DATABASE_ERROR("The attempt could not be written to the database."),
    PROCESS_INTERRUPTED("Android stopped the app, so this scheduled attempt was missed."),
    MISSED_SCHEDULE("The phone could not run this scheduled attempt on time. No catch-up photo was taken."),
    UNKNOWN("An unexpected error occurred."),
    ;

    val resultingStatus: CaptureStatus
        get() = if (isSkip) CaptureStatus.SKIPPED else CaptureStatus.FAILED

    /** Diagnostic category this failure is logged under. */
    val diagnosticType: DiagnosticType
        get() = when (this) {
            CAMERA_PERMISSION_DENIED -> DiagnosticType.CAMERA_PERMISSION_DENIED
            CAMERA_UNAVAILABLE, CAMERA_DISABLED, CAMERA_ERROR, CAPTURE_TIMEOUT -> DiagnosticType.CAMERA_UNAVAILABLE
            CAMERA_BUSY -> DiagnosticType.CAMERA_BUSY
            FOREGROUND_SERVICE_UNAVAILABLE -> DiagnosticType.FOREGROUND_SERVICE_UNAVAILABLE
            PROJECTION_NOT_ACTIVE, PROJECTION_STOPPED, NO_FRAME -> DiagnosticType.PROJECTION_STOPPED
            CONSENT_REVOKED -> DiagnosticType.CONSENT_REVOKED
            SCREEN_LOCKED, SCREEN_OFF -> DiagnosticType.SCREEN_LOCKED
            LOCAL_STORAGE_EXHAUSTED -> DiagnosticType.LOCAL_STORAGE_EXHAUSTED
            ENCRYPTION_FAILED, VAULT_LOCKED -> DiagnosticType.ENCRYPTION_ERROR
            DATABASE_ERROR -> DiagnosticType.DATABASE_ERROR
            PROCESS_INTERRUPTED, MISSED_SCHEDULE -> DiagnosticType.PROCESS_INTERRUPTED
            UNKNOWN -> DiagnosticType.UNEXPECTED_ERROR
        }
}

enum class Severity { INFO, WARNING, ERROR }

/** Categories for the diagnostic log (one per spec item, plus informational events). */
enum class DiagnosticType(val label: String, val severity: Severity) {
    CAMERA_UNAVAILABLE("Camera unavailable", Severity.ERROR),
    CAMERA_PERMISSION_DENIED("Camera permission denied", Severity.ERROR),
    CAMERA_BUSY("Camera busy", Severity.WARNING),
    FOREGROUND_SERVICE_UNAVAILABLE("Foreground service unavailable", Severity.ERROR),
    PROJECTION_STOPPED("Screen projection stopped", Severity.WARNING),
    SCREEN_LOCKED("Screen locked / off", Severity.INFO),
    CONSENT_REVOKED("Screen-sharing consent revoked", Severity.WARNING),
    INTERNET_UNAVAILABLE("Internet unavailable", Severity.WARNING),
    FIREBASE_AUTH_FAILURE("Firebase authentication failure", Severity.ERROR),
    UPLOAD_FAILURE("Upload failure", Severity.WARNING),
    CLOUD_STORAGE_EXHAUSTED("Cloud storage exhausted", Severity.ERROR),
    LOCAL_STORAGE_EXHAUSTED("Local storage exhausted", Severity.ERROR),
    DATABASE_ERROR("Database error", Severity.ERROR),
    PROCESS_INTERRUPTED("Application process interrupted", Severity.WARNING),
    ENCRYPTION_ERROR("Encryption error", Severity.ERROR),
    EXACT_ALARM_UNAVAILABLE("Exact alarms not allowed", Severity.WARNING),
    UNEXPECTED_ERROR("Unexpected error", Severity.ERROR),

    SCHEDULE_CHANGED("Schedule changed", Severity.INFO),
    CYCLE_COMPLETED("Capture cycle completed", Severity.INFO),
    PROJECTION_STARTED("Screen sharing started", Severity.INFO),
    UPLOAD_VERIFIED("Upload verified", Severity.INFO),
    DATA_DELETED("Data deleted", Severity.INFO),
}
