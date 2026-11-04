package com.mythos.tracker.collect

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.Telephony
import com.mythos.tracker.Permissions
import com.mythos.tracker.Prefs
import com.mythos.tracker.data.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Reads bank and UPI transaction SMS (debit/credit alerts) from the inbox, including old ones on the first run.
 * Only messages that contain an amount and a debit/credit word are kept; OTPs and chat messages are skipped.
 * The server parses amount, merchant and balance, so the parser can improve without a new app.
 */
class SmsCollector(private val context: Context, private val prefs: Prefs) {

    suspend fun collect(save: suspend (List<EventEntity>) -> Unit): Int {
        if (!Permissions.hasSms(context)) return 0
        return withContext(Dispatchers.IO) {
            var total = 0
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                val since = prefs.watermark(WM)
                val events = mutableListOf<EventEntity>()
                var lastId = since
                var rows = 0
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${Telephony.Sms._ID} > ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(since.toString()))
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${Telephony.Sms._ID} ASC")
                    putInt(ContentResolver.QUERY_ARG_LIMIT, BATCH)
                }
                val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
                context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, args, null)?.use { c ->
                    while (c.moveToNext()) {
                        rows++
                        val id = c.getLong(0)
                        lastId = id
                        val address = c.getString(1) ?: ""
                        val body = c.getString(2) ?: ""
                        if (!isTransaction(body)) continue
                        val payload = JSONObject().put("sender", address).put("body", body)
                        events += EventEntity(
                            kind = "sms_bank",
                            occurredAtMs = c.getLong(3),
                            sourceKey = "sms:$id",
                            payloadJson = payload.toString(),
                        )
                    }
                }
                if (events.isNotEmpty()) save(events)
                if (rows > 0) prefs.setWatermark(WM, lastId)
                total += events.size
                if (rows < BATCH) break
            }
            total
        }
    }

    private fun isTransaction(body: String): Boolean =
        AMOUNT.containsMatchIn(body) && MOVEMENT.containsMatchIn(body)

    companion object {
        private const val WM = "sms_id"
        private const val BATCH = 500
        private const val MAX_ROUNDS = 40
        private val AMOUNT = Regex("(?i)(rs\\.?|inr|₹)\\s*[0-9]")
        private val MOVEMENT = Regex("(?i)\\b(debited|credited|spent|paid|received|withdrawn|sent|refund)\\b")
    }
}
