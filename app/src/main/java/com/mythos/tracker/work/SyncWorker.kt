package com.mythos.tracker.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mythos.tracker.Prefs
import com.mythos.tracker.data.TrackerDatabase
import com.mythos.tracker.net.RecordingUploader
import com.mythos.tracker.net.SyncClient

/**
 * Uploads unsynced events in batches. Each event is marked synced only after the
 * server acknowledges its batch, so a dropped connection never loses data.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (prefs.serverUrl.isBlank() || prefs.token.isBlank()) {
            prefs.lastSyncResult = "Not synced: enter the server URL and access token on the Home tab"
            return Result.success() // not configured yet; data stays queued on the phone
        }

        val dao = TrackerDatabase.get(ctx).events()
        val client = SyncClient(prefs)
        var uploaded = 0

        while (true) {
            val batch = dao.pending(BATCH_SIZE)
            if (batch.isEmpty()) break

            val result = client.upload(batch)
            if (result.isFailure) {
                prefs.lastSyncResult = "Failed: ${result.exceptionOrNull()?.message}"
                return Result.retry()
            }
            dao.markSynced(batch.map { it.id })
            uploaded += batch.size
        }

        var recordings = 0
        try {
            recordings = RecordingUploader(ctx, prefs).uploadPending()
        } catch (e: Exception) {
            prefs.lastSyncResult = "Events uploaded ($uploaded); recording upload failed: ${e.message}"
            prefs.lastSyncMs = System.currentTimeMillis()
            return Result.retry()
        }

        prefs.lastSyncMs = System.currentTimeMillis()
        prefs.lastSyncResult = "Uploaded $uploaded event(s)" + if (recordings > 0) " and $recordings recording(s)" else ""
        return Result.success()
    }

    companion object {
        private const val BATCH_SIZE = 200
    }
}
