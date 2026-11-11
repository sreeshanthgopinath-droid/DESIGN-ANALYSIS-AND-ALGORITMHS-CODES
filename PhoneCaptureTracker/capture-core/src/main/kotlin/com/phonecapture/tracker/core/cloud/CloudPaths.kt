package com.phonecapture.tracker.core.cloud

import com.phonecapture.tracker.core.model.CaptureCategory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Cloud layout (dates are UTC so paths never depend on the phone's time-zone setting):
 *
 * ```
 * Storage:   PhoneCaptureTracker/<uid>/<yyyy>/<MM>/<dd>/<Front|Rear|Screenshots>/<stamp>_<tag>_<id>.jpg.enc
 * Firestore: users/<uid>/captures/<captureId>      (metadata only, no image data)
 *            users/<uid>/vault/keyring             (recovery keyring, no plaintext keys)
 * ```
 */
object CloudPaths {
    const val ROOT = "PhoneCaptureTracker"
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun objectPath(uid: String, category: CaptureCategory, capturedAtUtcMillis: Long, captureId: String): String {
        requireSafe(uid, "uid")
        requireSafe(captureId, "captureId")
        val t = Instant.ofEpochMilli(capturedAtUtcMillis).atZone(ZoneOffset.UTC)
        val date = "%04d/%02d/%02d".format(t.year, t.monthValue, t.dayOfMonth)
        val name = "${STAMP.format(t)}_${category.fileTag}_$captureId.jpg.enc"
        return "$ROOT/$uid/$date/${category.cloudFolder}/$name"
    }

    fun userRoot(uid: String): String = "$ROOT/${requireSafe(uid, "uid")}"

    fun capturesCollection(uid: String): String = "users/${requireSafe(uid, "uid")}/captures"

    fun captureDocument(uid: String, captureId: String): String =
        "${capturesCollection(uid)}/${requireSafe(captureId, "captureId")}"

    fun keyringDocument(uid: String): String = "users/${requireSafe(uid, "uid")}/vault/keyring"

    /** True when [path] is inside the given user's folder (used before deleting cloud objects). */
    fun belongsTo(uid: String, path: String): Boolean = path.startsWith("${userRoot(uid)}/") && ".." !in path

    private fun requireSafe(value: String, name: String): String {
        require(SAFE_ID.matches(value)) { "Unsafe $name for a cloud path" }
        return value
    }
}
