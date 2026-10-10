package dev.droidtop.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.droidtop.app.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach

/**
 * The foreground service the whole update pass runs in (docs/SPEC.md 10b, Droidtop/tracker#445):
 * the feed check, the download and the request to Android's installer, with a progress
 * notification. A pass started over adb with droidtop in the background used to run on a bare
 * thread, and Android froze the cached process seven seconds later, mid-download; a foreground
 * service is what keeps the process running. The pass itself is [UpdateNow.runPass], the one
 * update mechanism: the UPDATE_NOW receiver and the Check now row both come through [start], and
 * the service stops when the pass ends, whether it installed, failed or found nothing newer.
 *
 * Type dataSync: it is a network transfer, and unlike shortService it has no 3 minute limit
 * (the APK is about 130 MB). The notification is low importance, with no sound.
 */
class UpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_LOW))
        val initial = notification(this, "Starting...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initial)
        }
        val application = applicationContext
        val waitForOutcome = intent?.getBooleanExtra(EXTRA_WAIT_FOR_OUTCOME, false) ?: false
        UpdateNow.startDetached({
            try {
                UpdateNow.runPass(application, waitForOutcome) { line ->
                    manager?.notify(NOTIFICATION_ID, notification(application, line))
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        })
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "app_update"
        private const val NOTIFICATION_ID = 0x0DA7
        private const val EXTRA_WAIT_FOR_OUTCOME = "wait_for_outcome"

        /**
         * Runs an update pass in the foreground service and returns at once. A pass already running
         * is joined, not doubled. Where Android refuses a foreground service start (the app is
         * cached and the system does not allow it), the same pass runs on a thread of its own, as
         * it did before the service: it works while the process lives.
         */
        fun start(context: Context, waitForOutcome: Boolean = false) {
            val application = context.applicationContext
            if (!UpdateNow.begin()) return
            try {
                application.startForegroundService(
                    Intent(application, UpdateService::class.java).putExtra(EXTRA_WAIT_FOR_OUTCOME, waitForOutcome),
                )
            } catch (error: RuntimeException) {
                Log.w(UpdateNow.TAG, "no foreground service allowed (" + error.javaClass.simpleName + "): running the pass on a thread")
                UpdateNow.startDetached({ UpdateNow.runPass(application, waitForOutcome) })
            }
        }

        /**
         * Starts a pass (or joins the one running) and waits for it, narrating through [onStatus]:
         * the Check now row.
         */
        suspend fun startAndAwait(context: Context, onStatus: (String) -> Unit): String {
            start(context, waitForOutcome = true)
            val done = UpdateNow.pass
                .onEach { if (it.line.isNotEmpty()) onStatus(it.line) }
                .first { !it.running }
            return done.outcome ?: done.line
        }

        private fun notification(context: Context, line: String): Notification =
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Updating droidtop")
                .setContentText(line)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build()
    }
}
