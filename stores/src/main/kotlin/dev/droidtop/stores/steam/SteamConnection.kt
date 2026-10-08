package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.stores.SocialLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** The retry delays of a connection that dropped or could not be made: doubling, capped, never in lock step. */
internal object SteamBackoff {
    const val FIRST_MS = 5_000L
    const val CAP_MS = 5L * 60 * 1000

    /** The wait before attempt number [attempt] (0 for the first retry); [jitter] in 0..1 spreads it over 80..120 percent. */
    fun delayMs(attempt: Int, jitter: Double): Long {
        val base = FIRST_MS shl attempt.coerceIn(0, 16)
        val capped = base.coerceAtMost(CAP_MS)
        return (capped * (0.8 + 0.4 * jitter.coerceIn(0.0, 1.0))).toLong()
    }
}

/** What keeps the Steam connection running as an Android foreground service; the app fills it in. */
interface SteamConnectionHost {
    /** Starts the service that keeps the process alive and shows the ongoing notification. */
    fun start(context: Context)

    /** Stops it. */
    fun stop(context: Context)
}

/**
 * Keeps droidtop connected to Steam while the person is signed in and has not
 * chosen Offline (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313; the owner,
 * 2026-10-08: the connection closing after the last job "leaves us unable to
 * use chat"). One more holder of [SteamSession], so it never closes by itself,
 * and a loop that logs on again whenever the link drops: after a pause that
 * doubles up to five minutes, cut short when the network comes back
 * ([networkChanged]). Nothing is polled; JavaSteam's callbacks say when the
 * link is gone. The foreground service that stops Android ending the process
 * is the app's ([SteamConnectionHost]).
 */
object SteamConnection {
    private const val TAG = "SteamConnection"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var job: Job? = null

    @Volatile var host: SteamConnectionHost? = null

    /**
     * Brings the connection in line with the person's choice: kept (and the
     * service started) while signed in and not Offline, closed (and the service
     * stopped) otherwise. Safe to call from anywhere, any time; off the main thread.
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (SteamCredentials.exists(app) && SteamPrefs.stayConnected(app)) {
                runCatching { host?.start(app) }.onFailure { Timber.tag(TAG).w(it, "The connection service could not start") }
                ensureRunning(app)
                SteamFriendsHub.pushPresence(app)
            } else {
                stop(app)
            }
        }
    }

    /** Starts the keeping loop when it is not running. The service calls this when the system restarts it. */
    @Synchronized
    fun ensureRunning(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { keep(app) }
    }

    /** Ends the loop, closes the connection and the service. */
    fun stop(context: Context) {
        val app = context.applicationContext
        val ending = synchronized(this) { job.also { it?.cancel(); job = null } }
        runCatching { host?.stop(app) }
        SteamFriendsHub.setLink(SocialLink.OFF)
        // The loop lets go of the connection as it ends; only then is it idle.
        scope.launch {
            ending?.join()
            SteamSession.closeIfIdle()
        }
    }

    /** The network came back (or changed): a connection waiting to retry retries now. */
    fun networkChanged() {
        wake.trySend(Unit)
    }

    private suspend fun keep(context: Context) {
        SteamSession.acquire(context)
        var attempt = 0
        var everUp = false
        try {
            while (currentCoroutineContext().isActive && SteamCredentials.exists(context) && SteamPrefs.stayConnected(context)) {
                SteamFriendsHub.setLink(if (everUp) SocialLink.RECONNECTING else SocialLink.CONNECTING)
                val logOn = runCatching {
                    SteamSession.connectedClient(context)
                    SteamSession.logOn(context).getOrThrow()
                }
                if (logOn.isSuccess) {
                    everUp = true
                    attempt = 0
                    SteamFriendsHub.setLink(SocialLink.ONLINE)
                    SteamFriendsHub.online(context)
                    // Sleeps until the link drops; Steam's callbacks wake it, nothing polls.
                    SteamSession.link.first { it == SteamSession.Link.DOWN }
                    SteamFriendsHub.setLink(SocialLink.RECONNECTING)
                } else {
                    Timber.tag(TAG).w(logOn.exceptionOrNull(), "Steam did not connect (try ${attempt + 1})")
                    SteamFriendsHub.setLink(SocialLink.RECONNECTING)
                }
                // A short pause also after a link that dropped, so a server that keeps refusing is not hammered.
                val pause = SteamBackoff.delayMs(attempt, Math.random())
                if (!logOn.isSuccess) attempt++
                withTimeoutOrNull(pause) { wake.receive() }
                // Leave time for the network that just came back to be usable.
                if (!logOn.isSuccess) delay(250)
            }
        } finally {
            SteamSession.release()
            SteamFriendsHub.setLink(SocialLink.OFF)
            // Ended because the sign-in is gone or the person went Offline: the service has nothing left to keep.
            if (!SteamCredentials.exists(context) || !SteamPrefs.stayConnected(context)) {
                runCatching { host?.stop(context) }
            }
        }
    }
}
