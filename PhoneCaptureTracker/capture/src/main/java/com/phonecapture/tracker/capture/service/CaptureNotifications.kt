package com.phonecapture.tracker.capture.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phonecapture.tracker.capture.R
import com.phonecapture.tracker.core.model.ScheduleState
import com.phonecapture.tracker.core.text.Formatters
import java.time.ZoneId

data class ScheduleNotice(
    val state: ScheduleState,
    val nextAttemptUtc: Long?,
    val lastCycleUtc: Long?,
    val screenSharingActive: Boolean,
    val cycleRunning: Boolean,
    /** e.g. "front camera, rear camera and screen" */
    val categoriesDescription: String,
)

/**
 * All notifications are explicit about what is being captured and always offer Stop.
 * They are visible on the lock screen on purpose: capture must never be hidden.
 */
class CaptureNotifications(context: Context) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    fun ensureChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_STATUS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Capture status")
                    .setDescription("Shown the whole time the 30-minute capture schedule is active")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_SCREEN, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Screen sharing")
                    .setDescription("Shown while screen sharing for screenshots is active")
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ATTENTION, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Needs attention")
                    .setDescription("Problems that stop captures, such as screen sharing ending")
                    .build(),
            ),
        )
    }

    fun scheduleNotification(notice: ScheduleNotice): Notification {
        val zone = ZoneId.systemDefault()
        val title = when {
            notice.cycleRunning -> "Capturing now…"
            notice.state == ScheduleState.RUNNING -> "Phone capture is ON"
            notice.state == ScheduleState.PAUSED -> "Phone capture is paused"
            else -> "Phone capture is stopped"
        }
        val facts = buildList {
            if (notice.state == ScheduleState.RUNNING) {
                notice.nextAttemptUtc?.let { add("Next ${Formatters.shortTime(it, zone)}") }
            }
            notice.lastCycleUtc?.let { add("Last ${Formatters.shortTime(it, zone)}") }
            add(if (notice.screenSharingActive) "Screen sharing on" else "Screen sharing off")
        }.joinToString(" · ")
        val explanation = if (notice.state == ScheduleState.RUNNING) {
            "Every 30 minutes this app captures: ${notice.categoriesDescription}. $facts"
        } else {
            facts
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.pct_ic_stat_capture)
            .setContentTitle(title)
            .setContentText(facts)
            .setStyle(NotificationCompat.BigTextStyle().bigText(explanation))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
        when (notice.state) {
            ScheduleState.RUNNING -> builder.addAction(0, "Pause", controlIntent(CaptureControlReceiver.ACTION_PAUSE))
            ScheduleState.PAUSED -> builder.addAction(0, "Resume", controlIntent(CaptureControlReceiver.ACTION_RESUME))
            ScheduleState.STOPPED -> Unit
        }
        builder.addAction(0, "Stop", controlIntent(CaptureControlReceiver.ACTION_STOP))
        return builder.build()
    }

    fun updateSchedule(notice: ScheduleNotice) = notifyIfAllowed(ID_SCHEDULE, scheduleNotification(notice))

    fun screenSharingNotification(): Notification =
        NotificationCompat.Builder(appContext, CHANNEL_SCREEN)
            .setSmallIcon(R.drawable.pct_ic_stat_capture)
            .setContentTitle("Screen sharing is ON")
            .setContentText("One screenshot is taken at each 30-minute capture.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "One screenshot of the entire screen is taken at each 30-minute capture while the phone is " +
                        "unlocked. Android also shows its own screen-sharing indicator. Tap Stop sharing to end it.",
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop sharing", controlIntent(CaptureControlReceiver.ACTION_STOP_SHARING))
            .build()

    fun showProjectionEnded(detail: String) = showAttention(
        ID_PROJECTION_ENDED,
        "Screenshots paused",
        "$detail Open the app and tap 'Start screen sharing' to approve a new session.",
    )

    fun cancelProjectionEnded() = manager.cancel(ID_PROJECTION_ENDED)

    fun showServiceInterrupted(detail: String) = showAttention(
        ID_SERVICE_INTERRUPTED,
        "Phone capture interrupted",
        "$detail Open the app to resume the schedule.",
    )

    fun cancelServiceInterrupted() = manager.cancel(ID_SERVICE_INTERRUPTED)

    private fun showAttention(id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.pct_ic_stat_capture)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .build()
        notifyIfAllowed(id, notification)
    }

    @SuppressLint("MissingPermission") // checked explicitly below
    private fun notifyIfAllowed(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.notify(id, notification)
    }

    private fun controlIntent(action: String): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        action.hashCode(),
        Intent(appContext, CaptureControlReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openAppIntent(): PendingIntent? {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            appContext,
            REQUEST_OPEN_APP,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val CHANNEL_STATUS = "pct_capture_status"
        const val CHANNEL_SCREEN = "pct_screen_sharing"
        const val CHANNEL_ATTENTION = "pct_needs_attention"

        const val ID_SCHEDULE = 7301
        const val ID_SCREEN_SHARING = 7302
        const val ID_PROJECTION_ENDED = 7303
        const val ID_SERVICE_INTERRUPTED = 7304
        private const val REQUEST_OPEN_APP = 7300
    }
}
