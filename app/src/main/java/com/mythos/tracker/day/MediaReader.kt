package com.mythos.tracker.day

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mythos.tracker.Permissions

/** Lists the photos and videos taken (or saved) on a given day, straight from the phone's gallery. */
object MediaReader {
    data class Media(val uri: Uri, val time: Long, val name: String, val video: Boolean, val where: String)

    fun day(context: Context, start: Long, end: Long): List<Media> {
        if (!Permissions.hasMedia(context)) return emptyList()
        return (query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, start, end) +
            query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, start, end)).sortedBy { it.time }
    }

    private fun query(context: Context, collection: Uri, video: Boolean, start: Long, end: Long): List<Media> {
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            pathCol,
        )
        val taken = MediaStore.Images.Media.DATE_TAKEN
        val added = MediaStore.MediaColumns.DATE_ADDED
        val selection = "($taken >= ? AND $taken < ?) OR (($taken IS NULL OR $taken = 0) AND $added >= ? AND $added < ?)"
        val args = arrayOf(start.toString(), end.toString(), (start / 1000).toString(), (end / 1000).toString())
        val out = ArrayList<Media>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val t = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                    val path = c.getString(4).orEmpty()
                    val where = when {
                        path.contains("Screenshot", true) -> "Screenshot"
                        path.contains("DCIM", true) -> if (video) "Video taken" else "Photo taken"
                        path.contains("WhatsApp", true) -> "WhatsApp"
                        else -> if (video) "Video saved" else "Image saved"
                    }
                    out += Media(ContentUris.withAppendedId(collection, id), t, c.getString(3).orEmpty(), video, where)
                }
            }
        }
        return out
    }
}
