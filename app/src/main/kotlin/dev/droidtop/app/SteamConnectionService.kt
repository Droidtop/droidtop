package dev.droidtop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import dev.droidtop.library.social.SocialLink
import dev.droidtop.stores.steam.SteamConnection
import dev.droidtop.stores.steam.SteamConnectionHost
import dev.droidtop.stores.steam.SteamFriendsHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps droidtop's Steam connection alive
 * (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313; the owner, 2026-10-08, did
 * not like the connection closing after the last job because "it leaves us
 * unable to use chat"). It holds nothing itself: the connection and its
 * retries are [SteamConnection]'s. What Android needs is a reason to keep the
 * process, and this is it: one minimal ongoing notification, silent and
 * collapsed, that says whether Steam is connected and opens the Social place.
 * It also tells [SteamConnection] when the network comes back, so a retry
 * waiting out its pause does not wait. Nothing polls: callbacks only.
 *
 * Android 12 and later refuse to start a foreground service from the
 * background; a refusal is not an error here, the connection then runs for as
 * long as the process lives and the service starts the next time droidtop is
 * on screen ([SteamConnection.refresh] is called from every start).
 */
class SteamConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(this, SocialLink.CONNECTING, null))
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                SteamConnection.networkChanged()
            }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
        // The notification follows the connection and the person's name; it is not redrawn for anything else.
        watching = scope.launch {
            combine(SteamFriendsHub.link, SteamFriendsHub.me) { link, name -> link to name }.collect { (link, name) ->
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(this@SteamConnectionService, link, name))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Also how a restarted service finds its connection running again.
        SteamConnection.ensureRunning(applicationContext)
        return START_STICKY
    }

    override fun onDestroy() {
        watching?.cancel()
        scope.cancel()
        networkCallback?.let { callback -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        super.onDestroy()
    }

    /** Starts and stops the service for [SteamConnection]. */
    object Host : SteamConnectionHost {
        override fun start(context: Context) {
            context.startForegroundService(Intent(context, SteamConnectionService::class.java))
        }

        override fun stop(context: Context) {
            context.stopService(Intent(context, SteamConnectionService::class.java))
        }
    }

    companion object {
        private const val CHANNEL_ID = "steam_connection"
        private const val NOTIFICATION_ID = 0x57E4

        /**
         * Hooks the app into the Steam connection once at process start: the service that keeps it
         * alive, then asks [SteamConnection] to bring the connection in line with the person's choice.
         * A message's notification and the unread count are every provider's ([SocialNotifications]).
         */
        fun install(context: Context) {
            val app = context.applicationContext
            SteamConnection.host = Host
            SteamConnection.refresh(app)
        }

        private fun notification(context: Context, link: SocialLink, name: String?): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            // Minimum importance: silent and collapsed, a reason for Android to keep the process and nothing more.
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Steam connection", NotificationManager.IMPORTANCE_MIN))
            val open = PendingIntent.getActivity(
                context,
                0,
                SocialNotifications.openIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("Steam")
                .setContentText(if (link == SocialLink.ONLINE && name != null) name else link.label)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }
}
