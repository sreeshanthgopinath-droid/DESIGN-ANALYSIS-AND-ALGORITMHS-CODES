package com.phonecapture.tracker.core.policy

enum class LocalStorageState { OK, LOW, EXHAUSTED }
enum class CloudQuotaState { OK, WARNING, EXHAUSTED }

object StoragePolicy {
    /** Below this, captures are not attempted and are recorded as FAILED (local storage exhausted). */
    const val MIN_FREE_BYTES_TO_CAPTURE: Long = 300L * 1024 * 1024
    /** Below this, the dashboard shows a low-storage warning. */
    const val LOW_FREE_BYTES: Long = 1024L * 1024 * 1024
    const val CLOUD_WARNING_FRACTION = 0.8

    fun localState(freeBytes: Long): LocalStorageState = when {
        freeBytes < MIN_FREE_BYTES_TO_CAPTURE -> LocalStorageState.EXHAUSTED
        freeBytes < LOW_FREE_BYTES -> LocalStorageState.LOW
        else -> LocalStorageState.OK
    }

    fun cloudState(usedBytes: Long, quotaBytes: Long): CloudQuotaState {
        require(quotaBytes > 0) { "Quota must be positive" }
        return when {
            usedBytes >= quotaBytes -> CloudQuotaState.EXHAUSTED
            usedBytes >= (quotaBytes * CLOUD_WARNING_FRACTION).toLong() -> CloudQuotaState.WARNING
            else -> CloudQuotaState.OK
        }
    }
}
