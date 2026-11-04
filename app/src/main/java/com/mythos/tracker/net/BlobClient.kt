package com.mythos.tracker.net

import com.mythos.tracker.Prefs
import java.io.DataOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Uploads one file (call/voice recording, shared file) to POST {serverUrl}/api/blob as multipart. */
class BlobClient(private val prefs: Prefs) {

    fun upload(
        sourceKey: String,
        kind: String,
        occurredAtMs: Long,
        metaJson: String,
        fileName: String,
        mime: String,
        input: InputStream,
    ) {
        val boundary = "----mythos${System.currentTimeMillis()}"
        val conn = (URL("${prefs.serverUrl}/api/blob").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (prefs.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${prefs.token}")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.write(
                        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                            .toByteArray(Charsets.UTF_8),
                    )
                }
                field("source_key", sourceKey)
                field("kind", kind)
                field("occurred_at_ms", occurredAtMs.toString())
                field("meta", metaJson)
                field("device_id", prefs.deviceId)
                val safeName = fileName.replace("\"", "_").replace("\r", "").replace("\n", "")
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n" +
                        "Content-Type: ${mime.ifBlank { "application/octet-stream" }}\r\n\r\n")
                        .toByteArray(Charsets.UTF_8),
                )
                input.copyTo(out, 64 * 1024)
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code !in 200..299) error("server responded HTTP $code")
        } finally {
            conn.disconnect()
        }
    }
}
