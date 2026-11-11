package com.phonecapture.tracker.core.crypto

import java.security.MessageDigest
import java.util.Base64

object Hashing {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Base64 MD5, the format Firebase Cloud Storage reports in `StorageMetadata.md5Hash`. */
    fun md5Base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes))
}
