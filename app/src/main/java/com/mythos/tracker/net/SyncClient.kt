package com.mythos.tracker.net

import com.mythos.tracker.Prefs
import com.mythos.tracker.data.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends one batch to the Mythos cloud server.
 *
 * POST {serverUrl}/api/events
 * Authorization: Bearer {token}
 * Body: {"device_id": "...", "events": [{"kind", "occurred_at_ms", "source_key", "payload"}]}
 * Any 2xx response means the batch is stored.
 */
class SyncClient(private val prefs: Prefs) {

    suspend fun upload(batch: List<EventEntity>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val events = JSONArray()
            batch.forEach { e ->
                events.put(
                    JSONObject()
                        .put("kind", e.kind)
                        .put("occurred_at_ms", e.occurredAtMs)
                        .put("source_key", e.sourceKey)
                        .put("payload", JSONObject(e.payloadJson)),
                )
            }
            val body = JSONObject()
                .put("device_id", prefs.deviceId)
                .put("events", events)

            val conn = (URL("${prefs.serverUrl}/api/events").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (prefs.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${prefs.token}")
                }
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) error("server responded HTTP $code")
            } finally {
                conn.disconnect()
            }
        }
    }

    /** GET {serverUrl}/api/status, used by the Sources tab. Returns the raw JSON text. */
    suspend fun status(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("${prefs.serverUrl}/api/status").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer ${prefs.token}")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) error("server responded HTTP $code")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
