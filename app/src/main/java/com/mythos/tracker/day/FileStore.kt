package com.mythos.tracker.day

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/** Files you add yourself (call recordings, diary files, anything else). Kept privately on the phone. */
object FileStore {
    private const val DIR = "mythos_files"
    private const val MAX_TEXT = 200_000
    private val DATE_IN_NAME = Regex("(20\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})(?:[ _T-]?(\\d{2})[-_.:]?(\\d{2})(?:[-_.:]?(\\d{2}))?)?")

    fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    fun uriFor(context: Context, storedName: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir(context), storedName))

    /** Copies the file in and records it on the timeline. Returns the display name, or null on failure. */
    suspend fun import(context: Context, uri: Uri, category: String): String? = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "file"
        var modified = 0L
        runCatching {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0) name = c.getString(n) ?: name
                    val m = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (m >= 0 && !c.isNull(m)) modified = c.getLong(m)
                }
            }
        }
        val mime = cr.getType(uri).orEmpty()
        val now = System.currentTimeMillis()
        val stored = "${now}_" + name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100)
        val dest = File(dir(context), stored)
        val ok = runCatching {
            cr.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } != null
        }.getOrDefault(false)
        if (!ok || !dest.exists()) return@withContext null

        val time = timeFromName(name) ?: modified.takeIf { it > 0 } ?: now
        val cat = if (category == "auto") {
            if (mime.startsWith("audio/")) "recording" else "file"
        } else category
        val payload = JSONObject()
            .put("name", name).put("mime", mime).put("stored", stored)
            .put("size", dest.length()).put("category", cat)
        if ((mime.startsWith("text/") || name.endsWith(".txt") || name.endsWith(".md")) && dest.length() <= MAX_TEXT) {
            payload.put("text", dest.readText())
        }
        TrackerDatabase.get(context).events().insertAll(
            listOf(EventEntity(kind = "file", occurredAtMs = time, sourceKey = "file:$stored", payloadJson = payload.toString())),
        )
        name
    }

    /** Recording apps put the date and time in the file name, e.g. Call_20261110_153012.m4a. */
    fun timeFromName(name: String): Long? {
        val m = DATE_IN_NAME.find(name) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        val h = g[4].toIntOrNull() ?: 12
        val mi = g[5].toIntOrNull() ?: 0
        val s = g[6].toIntOrNull() ?: 0
        if (h > 23 || mi > 59 || s > 59) return null
        return Calendar.getInstance().apply {
            set(y, mo - 1, d, h, mi, s); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
