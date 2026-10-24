package dev.droidtop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import dev.droidtop.library.credentials.handoff.RemoteInputState
import dev.droidtop.library.credentials.handoff.consoleLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps "Type from another device" alive while the person is in another app (docs/SPEC.md,
 * "Type from another device", Droidtop/tracker#645): Android keeps the process, and so the listener, only for a reason
 * the person can see, and this is it, one notification that says what [RemoteInputs.state] says. The same
 * notification is the console when droidtop is not in front: Confirm and Refuse for the code, Close at every point.
 * The session lives and dies with the service, so no listener survives it: it stops itself when the session ends, and
 * its destruction closes the session.
 */
class RemoteInputService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(this, RemoteInputState.Idle))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONFIRM -> RemoteInputs.confirm()
            ACTION_REFUSE -> RemoteInputs.refuse()
            ACTION_CLOSE -> RemoteInputs.close()
            else -> if (watching == null) {
                watching = scope.launch {
                    RemoteInputs.state.collect { state ->
                        if (state is RemoteInputState.Closed) {
                            stopSelf()
                        } else {
                            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this@RemoteInputService, state))
                        }
                    }
                }
                // Binding a port is not for the main thread.
                Thread({ RemoteInputs.begin(applicationContext) }, "droidtop-remote-input").apply { isDaemon = true; start() }
            }
        }
        // A notification button pressed after the session ended starts this service for nothing.
        if (watching == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        RemoteInputs.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.droidtop.action.REMOTE_INPUT_START"
        private const val ACTION_CONFIRM = "dev.droidtop.action.REMOTE_INPUT_CONFIRM"
        private const val ACTION_REFUSE = "dev.droidtop.action.REMOTE_INPUT_REFUSE"
        private const val ACTION_CLOSE = "dev.droidtop.action.REMOTE_INPUT_CLOSE"
        private const val CHANNEL_ID = "remote_input"
        private const val NOTIFICATION_ID = 0x7E45

        private fun notification(context: Context, state: RemoteInputState): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            // High importance: the code appears on the screen in front, as a heads-up, wherever the person is.
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Type from another device", NotificationManager.IMPORTANCE_HIGH))
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Type from another device")
                .setContentText(state.consoleLine())
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setOngoing(true)
                .setOnlyAlertOnce(state !is RemoteInputState.Confirming)
                .setContentIntent(PendingIntent.getActivity(context, 0, RemoteInputActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            if (state is RemoteInputState.Confirming) {
                builder.addAction(action(context, "Confirm", ACTION_CONFIRM)).addAction(action(context, "Refuse", ACTION_REFUSE))
            }
            return builder.addAction(action(context, "Close", ACTION_CLOSE)).build()
        }

        private fun action(context: Context, title: String, what: String): Notification.Action {
            val intent = Intent(context, RemoteInputService::class.java).setAction(what)
            val pending = PendingIntent.getService(context, what.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_launcher_monochrome), title, pending).build()
        }
    }
}
