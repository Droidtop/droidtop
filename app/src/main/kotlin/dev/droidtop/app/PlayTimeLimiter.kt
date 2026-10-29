package dev.droidtop.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notices
import dev.droidtop.library.settings.PlayEnforcer
import dev.droidtop.library.settings.PlayLogStore
import dev.droidtop.library.settings.PlayStep
import dev.droidtop.library.settings.PlayTime
import dev.droidtop.library.settings.PlayTimePrefs
import dev.droidtop.library.settings.UiMode
import dev.droidtop.library.settings.UiModePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Counts play and holds the play-time limits (docs/SPEC.md 7f "Play-time limits", Droidtop/tracker#421): while
 * a game runs ([LaunchDisplay.running]) and Kid mode is on, every [TICK_MS] it adds the time since the last
 * tick to the ledger ([PlayTimePrefs]) and asks [PlayEnforcer] what to do: warn five minutes before a limit,
 * say so at the limit, and close the game a minute later through the one quit path
 * ([dev.droidtop.library.Library.quitRunning]), so the emulator saves the way a Quit from the Quick Menu does.
 * Watched from the process, not from a screen: the game is another app and no shell of droidtop is in front.
 * Nothing runs for a person who has set no limit beyond one prefs read per tick, and nothing at all while no
 * game runs. In every mode it also adds the session to the game's own play time ([PlayLogStore], docs/SPEC.md 7f
 * "Your play", Droidtop/tracker#597).
 */
object PlayTimeLimiter {
    private const val TAG = "droidtop.PlayTime"
    private const val TICK_MS = 15_000L
    private const val CHANNEL_ID = "play_time"
    private const val NOTIFICATION_ID = 0x4A0D

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** Starts watching, once per process. Called from [DroidtopApplication.onCreate]. */
    @Synchronized
    fun install(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            LaunchDisplay.running.collectLatest { session ->
                if (session == null) return@collectLatest
                watch(app, session.context)
            }
        }
    }

    /** One game session: runs until the game ends (the collector cancels it). */
    private suspend fun watch(app: Context, game: dev.droidtop.library.LaunchContext) {
        val enforcer = PlayEnforcer()
        var last = System.currentTimeMillis()
        val (title, system) = withContext(Dispatchers.IO) { nameOf(app, game) }
        try {
            while (true) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                val kid = UiModePrefs.get(app) == UiMode.KID
                if (kid) PlayTimePrefs.record(app, last, now)
                PlayLogStore.record(app, game.gameId, title, system, last, now)
                last = now
                if (!kid) continue
                val (limits, today, week) = PlayTimePrefs.snapshot(app, now)
                if (!limits.any) continue
                when (val step = enforcer.step(now, PlayTime.remainingSeconds(limits, today, week))) {
                    PlayStep.None -> Unit
                    is PlayStep.Warn -> tell(app, "Play time is almost up", "${step.minutesLeft} minutes left. Save your game.")
                    is PlayStep.TimeUp -> tell(app, "Play time is up", "The game closes in ${step.graceSeconds} seconds. Save your game.")
                    PlayStep.Quit -> quit(app)
                }
            }
        } finally {
            // The time since the last tick: the game ended (or droidtop is going) between two ticks.
            val end = System.currentTimeMillis()
            if (UiModePrefs.get(app) == UiMode.KID) PlayTimePrefs.record(app, last, end)
            PlayLogStore.record(app, game.gameId, title, system, last, end)
        }
    }

    /**
     * The name the person knows [game] by (their own name for it, else its title) and its system's name, read once
     * per session from the library; the id itself when the library does not hold it.
     */
    private suspend fun nameOf(app: Context, game: dev.droidtop.library.LaunchContext): Pair<String, String?> {
        val entry = runCatching {
            LibraryCore.library(app).currentEntriesOf(dev.droidtop.library.LibraryEntryKind.entries.toSet()).firstOrNull { it.id == game.gameId }
        }.getOrNull()
        val systemId = game.systemId ?: entry?.systemId
        val system = systemId?.let { id ->
            runCatching { dev.droidtop.library.consoles.PlatformsDatabase.builtIns(app).firstOrNull { it.id == id }?.displayName }.getOrNull() ?: id
        }
        return (entry?.gameName?.takeIf { it.isNotBlank() } ?: entry?.title ?: game.gameId) to system
    }

    private suspend fun quit(app: Context) {
        val library = LibraryCore.library(app)
        val outcome = library.quitRunningGame(app)
        Log.i(TAG, "play time up, quit: $outcome")
    }

    /**
     * A caution flash, which is a platform toast over a running game (no droidtop surface is started then), and a
     * notification where the person allowed them.
     */
    private fun tell(app: Context, title: String, text: String) {
        Notices.flash("$title. $text", tone = NoticeTone.Caution, context = app)
        runCatching { notify(app, title, text) }
    }

    private fun notify(app: Context, title: String, text: String) {
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Play time", NotificationManager.IMPORTANCE_HIGH))
        val back = PendingIntent.getActivity(
            app,
            0,
            // Back to the mode in use, never the default one over it (Droidtop/tracker#639).
            ModeReturn.intent(app),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(app, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setAutoCancel(true)
                .setContentIntent(back)
                .build(),
        )
    }
}
