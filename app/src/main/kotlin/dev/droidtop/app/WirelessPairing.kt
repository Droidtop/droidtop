package dev.droidtop.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dev.droidtop.pluginhost.ElevatedAccessHost

/**
 * Pairing droidtop with Android's wireless debugging (docs/SPEC.md "Wireless debugging", Droidtop/tracker#816). The
 * pairing code shows in Settings and disappears when Settings goes to the background, so the person types it into
 * droidtop's notification instead, which they can answer from the notification shade while Settings stays in front;
 * Shizuku does the same. [start] opens Developer options at Wireless debugging and posts that notification;
 * [Receiver] takes the code and pairs off the main thread, then says how it went in the same notification.
 */
object WirelessPairing {
    private const val CHANNEL_ID = "wireless_debugging"
    private const val NOTIFICATION_ID = 0x5744
    private const val KEY_CODE = "code"
    const val ACTION_CODE = "dev.droidtop.app.action.WIRELESS_PAIRING_CODE"

    /** Opens Wireless debugging in Developer options and posts the notification the code is typed into. */
    fun start(context: Context): String {
        if (!canNotify(context)) return "Allow droidtop's notifications first: the code is typed into one"
        notify(context, "Pair with wireless debugging", "In Wireless debugging, tap Pair device with pairing code, then reply here with the six digits", withReply = true)
        if (!openDeveloperOptions(context)) return "Turn on Developer options first: Settings > About > tap Build number seven times"
        // Over Settings, a guide the pad can reach takes the code too (Droidtop/tracker#363).
        return if (PairingGuide.show(context)) "Type the code into droidtop's guide over Settings, or reply to its notification"
        else "Reply to droidtop's notification with the code"
    }

    /**
     * Developer options at its Wireless debugging row (the key Settings highlights); Developer options itself where
     * that row is not there. False when Developer options is off.
     */
    fun openDeveloperOptions(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Paired some other way than the notification (the guide): the notification says so instead of asking. */
    fun paired(context: Context) {
        notify(context.applicationContext, "Paired with wireless debugging", "droidtop can now use it while Wireless debugging is on", withReply = false)
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(context: Context, title: String, text: String, withReply: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Wireless debugging", NotificationManager.IMPORTANCE_HIGH))
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOnlyAlertOnce(true)
        if (withReply) {
            val reply = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_CODE).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
            builder.addAction(Notification.Action.Builder(null, "Enter code", reply).addRemoteInput(input).build())
        } else {
            builder.setAutoCancel(true)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /** The code typed into the notification. Pairs on its own thread; the notification then says how it went. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.filter(Char::isDigit).orEmpty()
            val app = context.applicationContext
            if (code.length != 6) {
                notify(app, "Pair with wireless debugging", "That wasn't six digits. Reply again with the code Settings shows", withReply = true)
                return
            }
            notify(app, "Pairing with wireless debugging", "Pairing…", withReply = false)
            val pending = goAsync()
            Thread {
                try {
                    runCatching { ElevatedAccessHost.wireless(app).pair(code) }
                        .onSuccess {
                            paired(app)
                            PairingGuide.hide()
                        }
                        .onFailure { notify(app, "Pair with wireless debugging", (it.message ?: "Pairing failed") + ". Reply with a new code", withReply = true) }
                } finally {
                    pending.finish()
                }
            }.apply { name = "droidtop-wireless-pairing"; isDaemon = true; start() }
        }
    }
}
