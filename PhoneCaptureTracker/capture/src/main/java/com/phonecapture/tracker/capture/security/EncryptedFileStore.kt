package com.phonecapture.tracker.capture.security

import android.content.Context
import com.phonecapture.tracker.core.crypto.EncryptedEnvelope
import com.phonecapture.tracker.core.crypto.Hashing
import com.phonecapture.tracker.core.model.CaptureCategory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

data class StoredImage(
    val imagePath: String,
    val thumbnailPath: String,
    /** JPEG size before encryption. */
    val plainBytes: Long,
    /** Encrypted image size on disk (and in the cloud). */
    val storedBytes: Long,
    val keyId: String,
    val ciphertextSha256: String,
)

/**
 * Stores images only in encrypted form, in app-private storage:
 * `files/captures/<yyyy-MM-dd>/<front|rear|screen>/<captureId>.jpg.enc` (+ `.thumb.enc`).
 * Plaintext JPEG bytes exist only in memory; they are never written to disk by this app
 * (the explicit Export action is the only exception and it warns the user first).
 */
class EncryptedFileStore(context: Context, private val vault: VaultManager) {
    private val root = File(context.filesDir, "captures")
    private val canonicalRoot: String by lazy { root.apply { mkdirs() }.canonicalPath }

    /** @throws VaultUnavailableException or an I/O / crypto exception; nothing is saved then. */
    fun save(
        captureId: String,
        category: CaptureCategory,
        capturedAtUtc: Long,
        jpeg: ByteArray,
        thumbnail: ByteArray,
    ): StoredImage {
        val (keyId, key) = vault.currentKey()
        val day = Instant.ofEpochMilli(capturedAtUtc).atZone(ZoneOffset.UTC).toLocalDate().toString()
        val dir = File(root, "$day/${category.fileTag}").apply { mkdirs() }
        val image = File(dir, "$captureId.jpg.enc")
        val thumb = File(dir, "$captureId.thumb.enc")
        val sealedImage = EncryptedEnvelope.seal(key, keyId, jpeg, imageContext(captureId))
        val sealedThumb = EncryptedEnvelope.seal(key, keyId, thumbnail, thumbnailContext(captureId))
        try {
            writeAtomically(image, sealedImage)
            writeAtomically(thumb, sealedThumb)
        } catch (e: Exception) {
            image.delete()
            thumb.delete()
            throw e
        }
        return StoredImage(
            imagePath = image.absolutePath,
            thumbnailPath = thumb.absolutePath,
            plainBytes = jpeg.size.toLong(),
            storedBytes = sealedImage.size.toLong(),
            keyId = keyId,
            ciphertextSha256 = Hashing.sha256Hex(sealedImage),
        )
    }

    fun readImage(path: String, captureId: String): ByteArray =
        EncryptedEnvelope.open(safeFile(path).readBytes(), imageContext(captureId)) { vault.keyFor(it) }

    fun readThumbnail(path: String, captureId: String): ByteArray =
        EncryptedEnvelope.open(safeFile(path).readBytes(), thumbnailContext(captureId)) { vault.keyFor(it) }

    /** Decrypts an image envelope downloaded from the cloud. */
    fun decryptImageEnvelope(envelope: ByteArray, captureId: String): ByteArray =
        EncryptedEnvelope.open(envelope, imageContext(captureId)) { vault.keyFor(it) }

    /** Encrypted bytes exactly as stored, for upload. Refuses anything that is not an envelope. */
    fun readEnvelope(path: String): ByteArray {
        val bytes = safeFile(path).readBytes()
        check(EncryptedEnvelope.isEnvelope(bytes)) { "Refusing to upload a file that is not encrypted" }
        return bytes
    }

    fun exists(path: String?): Boolean = path != null && runCatching { safeFile(path).isFile }.getOrDefault(false)

    fun delete(path: String?) {
        if (path == null) return
        runCatching { safeFile(path).delete() }
    }

    fun deleteAll() {
        root.deleteRecursively()
    }

    fun usedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    /** Only files inside the capture folder can be read or deleted through this class. */
    private fun safeFile(path: String): File {
        val file = File(path)
        require(file.canonicalPath.startsWith(canonicalRoot + File.separator)) { "Path outside capture storage" }
        return file
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            error("Could not move ${tmp.name} into place")
        }
    }

    companion object {
        fun imageContext(captureId: String) = "capture:$captureId"
        fun thumbnailContext(captureId: String) = "thumb:$captureId"
    }
}
