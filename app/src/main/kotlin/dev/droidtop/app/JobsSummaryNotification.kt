package dev.droidtop.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.droidtop.library.settings.Place
import dev.droidtop.pluginhost.JobsSummary
import dev.droidtop.pluginhost.PluginJobsCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The one droidtop notification that says how many background jobs are running ("3 jobs running",
 * docs/SPEC.md 12a "Downloads"): library scrapes, plugin work jobs and the like. Tapping it opens
 * "Downloads and installs" in the mode in use ([PlaceLinks]). Single-file downloads are not counted:
 * Android shows its own notification for each of those.
 *
 * It only posts when the person has already allowed notifications for droidtop (the permission is
 * asked once, with its reason, where [DesktopNotificationPermission] asks it); without it the
 * summary is simply not shown and nothing else changes. Updates arrive only when the count
 * changes, never per progress tick.
 */
object JobsSummaryNotification {
    private const val CHANNEL_ID = "jobs_summary"
    private const val NOTIFICATION_ID = 0x4A0B
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts following the jobs list for the life of the process. Called once at process start. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            PluginJobsCenter.entries().map { JobsSummary.runningCount(it) }.distinctUntilChanged().collect { count ->
                runCatching { show(appContext, count) }
            }
        }
    }

    private fun show(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (count == 0) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Background jobs", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            context,
            0,
            PlaceLinks.intent(context, Place.DOWNLOADS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(JobsSummary.text(count))
                .setContentText("Tap to open Downloads and installs")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build(),
        )
    }
}
