package com.phonecapture.tracker.capture.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.phonecapture.tracker.capture.data.db.CaptureRecordEntity
import com.phonecapture.tracker.capture.security.EncryptedFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decrypts thumbnails on a background thread and keeps decoded bitmaps in memory only. */
class ThumbnailCache(private val fileStore: EncryptedFileStore) {
    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(record: CaptureRecordEntity): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(record.captureId)?.let { return@withContext it }
        val path = record.thumbnailPath ?: return@withContext null
        val bitmap = try {
            val bytes = fileStore.readThumbnail(path, record.captureId)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
        bitmap?.also { cache.put(record.captureId, it) }
    }

    fun evict(captureId: String) {
        cache.remove(captureId)
    }

    fun clear() = cache.evictAll()

    private companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
    }
}
