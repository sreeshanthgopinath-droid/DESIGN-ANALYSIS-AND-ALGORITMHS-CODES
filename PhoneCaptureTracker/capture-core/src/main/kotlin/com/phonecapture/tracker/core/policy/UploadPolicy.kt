package com.phonecapture.tracker.core.policy

import kotlin.math.min

enum class NetworkKind { NONE, UNMETERED, METERED }

/** Network the upload queue waits for. Wi-Fi (unmetered) only unless the user allows mobile data. */
enum class NetworkRequirement { UNMETERED, CONNECTED }

enum class UploadErrorKind(val transient: Boolean, val description: String) {
    NETWORK(true, "Network unavailable or interrupted"),
    TIMEOUT(true, "The upload timed out"),
    SERVER(true, "The cloud service returned a temporary error"),
    VERIFICATION_MISMATCH(true, "The uploaded file did not match the local file"),
    UNKNOWN(true, "Unknown upload error"),
    NOT_SIGNED_IN(false, "Not signed in to the cloud account"),
    PERMISSION_DENIED(false, "The cloud rejected the upload (security rules / App Check)"),
    QUOTA_EXCEEDED(false, "Cloud storage quota exceeded"),
    LOCAL_FILE_MISSING(false, "The local encrypted file no longer exists"),
    NOT_ENCRYPTED(false, "Refused: the file is not an encrypted envelope"),
}

sealed interface UploadDecision {
    data class Retry(val delayMillis: Long) : UploadDecision
    data class GiveUp(val kind: UploadErrorKind) : UploadDecision
}

class UploadRetryPolicy(
    val maxAttempts: Int = 8,
    private val baseDelayMillis: Long = 30_000,
    private val maxDelayMillis: Long = 6 * 60 * 60 * 1000L,
) {
    init {
        require(maxAttempts >= 1)
        require(baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis)
    }

    /** @param attempts number of attempts made so far, including the one that just failed. */
    fun decide(kind: UploadErrorKind, attempts: Int): UploadDecision =
        if (!kind.transient || attempts >= maxAttempts) UploadDecision.GiveUp(kind)
        else UploadDecision.Retry(backoffMillis(attempts))

    fun backoffMillis(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, 30)
        val raw = baseDelayMillis.toDouble() * (1L shl exponent)
        return min(raw, maxDelayMillis.toDouble()).toLong()
    }
}

object UploadPolicy {
    fun networkRequirement(allowMobileData: Boolean): NetworkRequirement =
        if (allowMobileData) NetworkRequirement.CONNECTED else NetworkRequirement.UNMETERED

    fun canUploadNow(network: NetworkKind, allowMobileData: Boolean): Boolean = when (network) {
        NetworkKind.NONE -> false
        NetworkKind.UNMETERED -> true
        NetworkKind.METERED -> allowMobileData
    }
}
