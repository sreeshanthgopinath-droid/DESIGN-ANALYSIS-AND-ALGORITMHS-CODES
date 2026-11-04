package com.mythos.tracker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.work.Scheduler
import com.mythos.tracker.work.SharedUploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * "Share to Mythos": notes and files shared from Essential Space, Keep, the voice recorder, screenshots, etc.
 * Text is stored directly; other files are copied to private storage and uploaded to the server.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceApp = referrer?.host.orEmpty()
        val shareIntent = intent
        lifecycleScope.launch(Dispatchers.IO) {
            val saved = runCatching { handle(shareIntent, sourceApp) }.getOrDefault(0)
            launch(Dispatchers.Main) {
                Toast.makeText(
                    this@ShareActivity,
                    if (saved > 0) "Saved to Mythos" else "Nothing to save",
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }
    }

    private suspend fun handle(intent: Intent, sourceApp: String): Int {
        val ctx = applicationContext
        val dao = TrackerDatabase.get(ctx).events()
        val kind = if (sourceApp.contains("essential", true) || sourceApp.startsWith("com.nothing"))
            "essential_note" else "shared_note"
        val now = System.currentTimeMillis()
        var saved = 0

        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        if (text.isNotBlank()) {
            val payload = JSONObject().put("text", text).put("title", subject).put("from", sourceApp)
            dao.insertAll(listOf(EventEntity(kind = kind, occurredAtMs = now,
                sourceKey = "share:$now:${text.hashCode()}", payloadJson = payload.toString())))
            saved++
        }

        val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        for ((i, uri) in uris.withIndex()) {
            val mime = contentResolver.getType(uri) ?: intent.type.orEmpty()
            val name = displayName(uri) ?: "shared-$now-$i"
            if (mime.startsWith("text/")) {
                val body = contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_TEXT).toByteArray() }
                    ?.toString(Charsets.UTF_8).orEmpty()
                if (body.isNotBlank()) {
                    val payload = JSONObject().put("text", body).put("title", name).put("from", sourceApp)
                    dao.insertAll(listOf(EventEntity(kind = kind, occurredAtMs = now,
                        sourceKey = "share:$now:$i:${body.hashCode()}", payloadJson = payload.toString())))
                    saved++
                }
            } else {
                val dir = File(ctx.filesDir, "shared").apply { mkdirs() }
                val copy = File(dir, "$now-$i-${name.replace('/', '_')}")
                contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                if (copy.exists() && copy.length() in 1..MAX_FILE) {
                    SharedUploadWorker.enqueue(ctx, copy, name, mime, kind, "sharefile:$now:$i:${copy.length()}",
                        JSONObject().put("from", sourceApp).toString())
                    saved++
                } else {
                    copy.delete()
                }
            }
        }
        if (saved > 0) Scheduler.requestSync(ctx)
        return saved
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    companion object {
        private const val MAX_TEXT = 2_000_000
        private const val MAX_FILE = 30L * 1024 * 1024
    }
}
