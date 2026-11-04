package com.mythos.tracker.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mythos.tracker.Permissions
import com.mythos.tracker.ui.MainActivity

/** Posts the nightly "how was your day?" reminder that opens the diary tab. */
class DiaryReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        ensureChannel(ctx)
        if (!Permissions.hasPostNotifications(ctx)) return Result.success()
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra("tab", 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("How was your day?")
            .setContentText("Write a few lines in your Mythos diary.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1001, n)
        return Result.success()
    }

    companion object {
        const val CHANNEL = "diary"
        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Diary reminder", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }
    }
}
