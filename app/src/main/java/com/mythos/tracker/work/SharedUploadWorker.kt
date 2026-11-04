package com.mythos.tracker.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mythos.tracker.Prefs
import com.mythos.tracker.net.BlobClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Uploads a file the user shared into the app (copied to private storage first), retrying until it works. */
class SharedUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val path = inputData.getString("path") ?: return Result.failure()
        val file = File(path)
        if (!file.exists()) return Result.success()
        val prefs = Prefs(applicationContext)
        if (prefs.serverUrl.isBlank()) return Result.retry()
        return try {
            file.inputStream().use { input ->
                BlobClient(prefs).upload(
                    sourceKey = inputData.getString("source_key") ?: "share:${file.name}",
                    kind = inputData.getString("kind") ?: "shared_note",
                    occurredAtMs = inputData.getLong("time", System.currentTimeMillis()),
                    metaJson = inputData.getString("meta") ?: "{}",
                    fileName = inputData.getString("name") ?: file.name,
                    mime = inputData.getString("mime") ?: "",
                    input = input,
                )
            }
            file.delete()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount >= 12) Result.failure() else Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, file: File, name: String, mime: String, kind: String, key: String, meta: String) {
            val data = Data.Builder()
                .putString("path", file.absolutePath).putString("name", name).putString("mime", mime)
                .putString("kind", kind).putString("source_key", key).putString("meta", meta)
                .putLong("time", System.currentTimeMillis())
                .build()
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<SharedUploadWorker>()
                    .setInputData(data)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
