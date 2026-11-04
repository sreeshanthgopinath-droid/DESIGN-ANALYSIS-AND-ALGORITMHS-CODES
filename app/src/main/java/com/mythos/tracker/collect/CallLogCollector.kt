package com.mythos.tracker.collect

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CallLog
import com.mythos.tracker.Permissions
import com.mythos.tracker.Prefs
import com.mythos.tracker.data.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Reads the call history (who, when, how long, direction). Backfills everything on the first run. */
class CallLogCollector(private val context: Context, private val prefs: Prefs) {

    suspend fun collect(save: suspend (List<EventEntity>) -> Unit): Int {
        if (!Permissions.hasCallLog(context)) return 0
        return withContext(Dispatchers.IO) {
            var total = 0
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                val since = prefs.watermark(WM)
                val events = mutableListOf<EventEntity>()
                var lastId = since
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${CallLog.Calls._ID} > ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString()))
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${CallLog.Calls._ID} ASC")
                    putInt(ContentResolver.QUERY_ARG_LIMIT, BATCH)
                }
                val projection = arrayOf(
                    CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE,
                    CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME,
                )
                context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        lastId = id
                        val payload = JSONObject()
                            .put("number", c.getString(1) ?: "")
                            .put("duration_s", c.getLong(3))
                            .put("type", typeName(c.getInt(4)))
                            .put("name", c.getString(5) ?: "")
                        events += EventEntity(
                            kind = "call",
                            occurredAtMs = c.getLong(2),
                            sourceKey = "call:$id",
                            payloadJson = payload.toString(),
                        )
                    }
                }
                if (events.isNotEmpty()) {
                    save(events)
                    prefs.setWatermark(WM, lastId)
                }
                total += events.size
                if (events.size < BATCH) break
            }
            total
        }
    }

    private fun typeName(t: Int) = when (t) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other"
    }

    companion object {
        private const val WM = "calllog_id"
        private const val BATCH = 500
        private const val MAX_ROUNDS = 20
    }
}
