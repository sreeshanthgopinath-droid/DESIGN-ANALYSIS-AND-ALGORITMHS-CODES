package com.mythos.tracker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.mythos.tracker.data.EventEntity
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.day.FileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Share → "Save to Mythos" from any app: text becomes a note, files (recordings, diary files) are kept on the phone. */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shareIntent = intent
        lifecycleScope.launch {
            val saved = runCatching { withContext(Dispatchers.IO) { handle(shareIntent) } }.getOrDefault(0)
            Toast.makeText(
                this@ShareActivity,
                if (saved > 0) "Saved to Mythos" else "Nothing to save",
                Toast.LENGTH_SHORT,
            ).show()
            finish()
        }
    }

    private suspend fun handle(intent: Intent): Int {
        val ctx = applicationContext
        val dao = TrackerDatabase.get(ctx).events()
        val now = System.currentTimeMillis()
        var saved = 0

        val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        for (uri in uris) {
            if (FileStore.import(ctx, uri, "auto") != null) saved++
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotBlank() && uris.isEmpty()) {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            val payload = JSONObject().put("text", text).put("title", subject)
            dao.insertAll(listOf(EventEntity(kind = "shared_note", occurredAtMs = now,
                sourceKey = "share:$now:${text.hashCode()}", payloadJson = payload.toString())))
            saved++
        }
        return saved
    }
}
