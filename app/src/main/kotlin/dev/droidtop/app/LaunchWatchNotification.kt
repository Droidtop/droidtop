package dev.droidtop.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dev.droidtop.library.LaunchWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The launch watchdog's alert as a notification (docs/SPEC.md "The launch watchdog"): when a launched
 * app is not responding the shell may be hidden behind it, so the dialog the shell shows cannot be
 * seen. A notification is drawn by the system over any app, and tapping it brings droidtop back, where
 * the dialog offers Close it. Like [JobsSummaryNotification] it posts only when the person has already
 * allowed notifications for droidtop, and without that permission nothing else changes.
 */
object LaunchWatchNotification {
    private const val CHANNEL_ID = "launch_watchdog"
    private const val NOTIFICATION_ID = 0x4A0C
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts following the watchdog's alert for the life of the process. Called once at process start. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            LaunchWatchdog.alert.collect { alert ->
                runCatching { show(appContext, alert) }
            }
        }
    }

    private fun show(context: Context, alert: dev.droidtop.library.LaunchAlert?) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (alert == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Launch problems", NotificationManager.IMPORTANCE_HIGH))
        val back = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(alert.message)
                .setContentText("Tap to return to droidtop, where you can close ${alert.appName}")
                .setSubText(alert.logPath)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setAutoCancel(true)
                .setContentIntent(back)
                .build(),
        )
    }
}
