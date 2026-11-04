package com.mythos.tracker.collect

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.mythos.tracker.Permissions
import com.mythos.tracker.Prefs
import com.mythos.tracker.data.EventEntity
import org.json.JSONObject

/**
 * Reads metadata (capture time, GPS from EXIF for photos, file name, URI) for new photos and videos.
 * The media files themselves are NOT copied; only metadata is sent.
 *
 * Progress is tracked with a (date_added, _id) pair so batches never skip or repeat rows,
 * even when many files share the same timestamp.
 */
class MediaCollector(private val context: Context, private val prefs: Prefs) {

    fun collect(): List<EventEntity> {
        if (!Permissions.hasMedia(context)) return emptyList()
        return collectFrom(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo") +
            collectFrom(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
    }

    private fun collectFrom(collection: Uri, kind: String): List<EventEntity> {
        val out = mutableListOf<EventEntity>()
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val read = readBatch(collection, kind, out)
            if (read < BATCH) break // caught up
        }
        return out
    }

    /** Reads up to BATCH rows after the saved position into [out], advances the position, returns rows read. */
    private fun readBatch(collection: Uri, kind: String, out: MutableList<EventEntity>): Int {
        val dateKey = "media_${kind}_date"
        val idKey = "media_${kind}_id"
        var lastDate = prefs.watermark(dateKey)
        var lastId = prefs.watermark(idKey)

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )

        // LIMIT inside the sort order is rejected on Android 11+, so the limit goes in a query Bundle.
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DATE_ADDED} > ? OR " +
                    "(${MediaStore.MediaColumns.DATE_ADDED} = ? AND ${MediaStore.MediaColumns._ID} > ?)",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(lastDate.toString(), lastDate.toString(), lastId.toString()),
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_ADDED} ASC, ${MediaStore.MediaColumns._ID} ASC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, BATCH)
        }

        var count = 0
        context.contentResolver.query(collection, projection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                count++
                val id = cursor.getLong(0)
                val addedSec = cursor.getLong(1)
                val takenMs = cursor.getLong(2)
                val name = cursor.getString(3)

                lastDate = addedSec
                lastId = id

                val uri = ContentUris.withAppendedId(collection, id)
                val payload = JSONObject()
                    .put("uri", uri.toString())
                    .put("display_name", name ?: "")
                if (kind == "photo") {
                    readLatLng(uri)?.let { payload.put("lat", it[0]).put("lng", it[1]) }
                }

                out += EventEntity(
                    kind = kind,
                    occurredAtMs = if (takenMs > 0) takenMs else addedSec * 1000,
                    sourceKey = "$kind:$id",
                    payloadJson = payload.toString(),
                )
            }
        }

        prefs.setWatermark(dateKey, lastDate)
        prefs.setWatermark(idKey, lastId)
        return count
    }

    /**
     * Android 10+ hides photo location from MediaStore columns, so it is read from the file's EXIF.
     * That needs the "access media location" permission; without it this simply returns null.
     */
    private fun readLatLng(uri: Uri): DoubleArray? = runCatching {
        val source = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
        context.contentResolver.openInputStream(source)?.use { ExifInterface(it).latLong }
    }.getOrNull()

    companion object {
        private const val BATCH = 500
        private const val MAX_ROUNDS = 20
    }
}
