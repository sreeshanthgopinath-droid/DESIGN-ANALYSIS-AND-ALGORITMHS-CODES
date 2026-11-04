package com.mythos.tracker.net

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import com.mythos.tracker.Permissions
import com.mythos.tracker.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Uploads audio files (call recordings, Essential Space voice notes, voice recorder files) whose path
 * contains one of the configured folder fragments. Progress only moves forward after a successful upload,
 * so a dropped connection resumes with the same file next time.
 */
class RecordingUploader(private val context: Context, private val prefs: Prefs) {

    /** Returns number of files uploaded this run. Throws if an upload fails (caller retries later). */
    suspend fun uploadPending(maxFiles: Int = 8): Int {
        if (!Permissions.hasAudio(context)) return 0
        val fragments = prefs.recordingFolders.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (fragments.isEmpty()) return 0
        return withContext(Dispatchers.IO) {
            val client = BlobClient(prefs)
            var uploaded = 0
            while (uploaded < maxFiles) {
                val row = nextRow(fragments) ?: break
                if (row.size in 1..MAX_BYTES) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, row.id)
                    val meta = JSONObject()
                        .put("display_name", row.name)
                        .put("duration_ms", row.durationMs)
                        .put("path", row.path)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        client.upload("audio:${row.id}", "recording", row.addedSec * 1000, meta.toString(),
                            row.name, row.mime, input)
                    }
                    uploaded++
                }
                prefs.setWatermark(WM_DATE, row.addedSec)
                prefs.setWatermark(WM_ID, row.id)
            }
            uploaded
        }
    }

    private class Row(
        val id: Long, val addedSec: Long, val name: String, val mime: String,
        val size: Long, val durationMs: Long, val path: String,
    )

    private fun nextRow(fragments: List<String>): Row? {
        val sinceDate = prefs.watermark(WM_DATE)
        val sinceId = prefs.watermark(WM_ID)
        val like = fragments.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "(${MediaStore.MediaColumns.DATE_ADDED} > ? OR " +
                    "(${MediaStore.MediaColumns.DATE_ADDED} = ? AND ${MediaStore.MediaColumns._ID} > ?)) AND ($like)",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(sinceDate.toString(), sinceDate.toString(), sinceId.toString()) +
                    fragments.map { "%$it%" },
            )
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.MediaColumns.DATE_ADDED} ASC, ${MediaStore.MediaColumns._ID} ASC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA,
        )
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, args, null)?.use { c ->
            if (c.moveToFirst()) {
                return Row(c.getLong(0), c.getLong(1), c.getString(2) ?: "audio", c.getString(3) ?: "",
                    c.getLong(4), c.getLong(5), c.getString(6) ?: "")
            }
        }
        return null
    }

    companion object {
        private const val WM_DATE = "audio_date"
        private const val WM_ID = "audio_id"
        private const val MAX_BYTES = 30L * 1024 * 1024 // server host limit is 32 MB per request
    }
}
