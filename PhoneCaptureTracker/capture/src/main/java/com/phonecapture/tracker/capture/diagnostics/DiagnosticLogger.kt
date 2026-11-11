package com.phonecapture.tracker.capture.diagnostics

import android.util.Log
import com.phonecapture.tracker.capture.data.db.DiagnosticLogDao
import com.phonecapture.tracker.capture.data.db.DiagnosticLogEntity
import com.phonecapture.tracker.core.model.DiagnosticType
import com.phonecapture.tracker.core.model.FailureReason
import com.phonecapture.tracker.core.model.Severity
import com.phonecapture.tracker.core.text.Formatters
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Persistent diagnostic log shown in the Diagnostics screen. Entries never contain image data,
 * e-mail addresses or passwords.
 */
class DiagnosticLogger(
    private val dao: DiagnosticLogDao,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun log(type: DiagnosticType, message: String, detail: String? = null, captureId: String? = null) {
        val priority = when (type.severity) {
            Severity.INFO -> Log.INFO
            Severity.WARNING -> Log.WARN
            Severity.ERROR -> Log.ERROR
        }
        Log.println(priority, TAG, "[${type.name}] $message" + (detail?.let { " — $it" } ?: ""))
        val entry = DiagnosticLogEntity(
            timestampUtc = clock(),
            type = type,
            severity = type.severity,
            message = message,
            detail = detail?.take(MAX_DETAIL),
            captureId = captureId,
        )
        scope.launch {
            try {
                dao.insert(entry)
            } catch (e: Exception) {
                Log.e(TAG, "Could not persist diagnostic entry", e)
            }
        }
    }

    fun failure(reason: FailureReason, detail: String?, captureId: String? = null) =
        log(reason.diagnosticType, reason.userMessage, detail, captureId)

    fun observeRecent(limit: Int = 300): Flow<List<DiagnosticLogEntity>> = dao.observeRecent(limit)
    fun observeLatestProblem(): Flow<DiagnosticLogEntity?> = dao.observeLatestProblem()

    suspend fun clear() = dao.deleteAll()
    suspend fun pruneOlderThan(cutoffUtc: Long): Int = dao.deleteOlderThan(cutoffUtc)

    suspend fun exportText(zone: ZoneId, limit: Int = 1000): String =
        dao.recent(limit).joinToString("\n") { e ->
            buildString {
                append(Formatters.dateTime(e.timestampUtc, zone))
                append("  ").append(e.severity.name.padEnd(7))
                append(" ").append(e.type.label).append(": ").append(e.message)
                e.detail?.let { append(" — ").append(it) }
            }
        }

    companion object {
        const val TAG = "PhoneCaptureTracker"
        private const val MAX_DETAIL = 2000
    }
}
