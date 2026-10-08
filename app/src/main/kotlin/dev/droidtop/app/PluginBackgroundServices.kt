package dev.droidtop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import dev.droidtop.library.integrations.PluginBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * :app's side of plugin background work (docs/plugin-api.md 3 E8, E9; [PluginBackground] decides what runs):
 * while a plugin service runs, [PluginServicesService] is the reason Android keeps the process, with one silent
 * notification that says how many run; while any schedule is on, [PluginScheduleJobService] is a periodic system job
 * that starts the schedules that are due. With neither, nothing of this runs (a feature that is off runs no code).
 */
object PluginBackgroundHost : PluginBackground.Host {
    const val SCHEDULE_JOB_ID = 0x504C53

    fun install(context: Context) {
        PluginBackground.host = this
        PluginBackground.changed(context)
    }

    override fun servicesRunning(context: Context, count: Int) {
        val intent = Intent(context, PluginServicesService::class.java).putExtra(PluginServicesService.EXTRA_COUNT, count)
        if (count > 0) {
            // Android 12+ refuses a foreground start from the background; the services then run as long as the process does.
            runCatching { context.startForegroundService(intent) }
        } else {
            context.stopService(intent)
        }
    }

    override fun schedulesWanted(context: Context, wanted: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val scheduled = scheduler.getPendingJob(SCHEDULE_JOB_ID) != null
        if (wanted && !scheduled) {
            scheduler.schedule(
                JobInfo.Builder(SCHEDULE_JOB_ID, ComponentName(context, PluginScheduleJobService::class.java))
                    .setPeriodic(JobInfo.getMinPeriodMillis())
                    .setPersisted(false)
                    .build(),
            )
        } else if (!wanted && scheduled) {
            scheduler.cancel(SCHEDULE_JOB_ID)
        }
    }
}

/** Keeps droidtop's process alive while plugin services run; it holds nothing itself ([PluginBackground] does). */
class PluginServicesService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        startForeground(NOTIFICATION_ID, notification(this, count))
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_COUNT = "dev.droidtop.app.extra.PLUGIN_SERVICES"
        private const val CHANNEL_ID = "plugin_services"
        private const val NOTIFICATION_ID = 0x504C56

        private fun notification(context: Context, count: Int): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Minimum importance: silent and collapsed, a reason for Android to keep the process and nothing more.
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Plugins running in the background", NotificationManager.IMPORTANCE_MIN))
            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Plugins")
                .setContentText(if (count == 1) "1 plugin task running" else "$count plugin tasks running")
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}

/** The periodic system job that runs plugin schedules that are due (docs/plugin-api.md 3 E9). */
class PluginScheduleJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            val app = applicationContext
            val battery = app.getSystemService(BatteryManager::class.java)
            val charging = battery?.isCharging == true
            val connectivity = app.getSystemService(ConnectivityManager::class.java)
            val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            val unmetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            runCatching { PluginBackground.runDueSchedules(app, charging, unmetered) }
            // Nothing left to schedule (the last one was switched off or uninstalled): stop asking Android to wake us.
            if (PluginBackground.schedules(app).isEmpty()) PluginBackgroundHost.schedulesWanted(app, false)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }
}
