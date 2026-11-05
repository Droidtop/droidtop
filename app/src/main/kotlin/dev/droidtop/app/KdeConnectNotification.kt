package dev.droidtop.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.droidtop.app.settings.KdeConnectCatalog
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.net.kdeconnect.KdcEvent
import dev.droidtop.net.kdeconnect.KdeConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * KDE Connect's notices (docs/SPEC.md "KDE Connect"): a device asking to pair, with the code to compare, and a
 * ping. Either opens the KDE Connect screen, where the request is accepted or refused. Like
 * [LaunchWatchNotification] it posts only when the person has allowed droidtop's notifications.
 */
object KdeConnectNotification {
    private const val CHANNEL_ID = "kdeconnect"
    private const val PAIR_ID = 0x4B01
    private const val PING_ID = 0x4B02
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts the device when it was left on, and follows its events for the life of the process. Called once at process start. */
    fun start(context: Context) {
        val app = context.applicationContext
        KdeConnect.resume(app)
        scope.launch {
            KdeConnect.events.collect { e -> runCatching { show(app, e) } }
        }
    }

    private fun show(context: Context, e: KdcEvent) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val (id, title, text) = when (e) {
            is KdcEvent.PairRequest -> Triple(PAIR_ID, "${e.name} asks to pair", "Pair only if it shows ${e.key}. Open to accept or refuse")
            is KdcEvent.Paired, is KdcEvent.PairFailed -> {
                manager.cancel(PAIR_ID)
                return
            }
            is KdcEvent.Ping -> Triple(PING_ID, "Ping", e.message ?: "A paired device pinged this one")
            is KdcEvent.Other -> return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "KDE Connect", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context,
            id,
            CatalogScreenLink.intent(context, KdeConnectCatalog.SCREEN_ID),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            id,
            Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }
}
