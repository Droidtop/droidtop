package dev.droidtop.runtime

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The launch-static experiment (docs/SPEC.md "Launch audio hand-off",
 * Droidtop/tracker#160): which of four ways droidtop treats its own sound
 * between the A press on a game and the launch itself. The owner listens to
 * each on the console and says which one stops the burst; every line logged
 * under [AudioHandOff.TAG] carries the letter. The setting is a row in the
 * Shell group of Settings, default [A].
 */
enum class LaunchSoundVariant(val label: String) {
    /** The launch sample plays at the A press; the preview video keeps playing behind the screen question. */
    A("A: as it works now"),

    /** The launch sample waits until the launch is really dispatched, after the screen question. */
    B("B: launch sound after the screen question"),

    /** The launch sample plays at the A press; preview video and theme sounds are silenced while the screen question is up. */
    C("C: silence the preview while asking which screen"),

    /** Nothing of droidtop's sound from the A press on (a control: no launch sample, preview silenced). */
    D("D: no droidtop sound while launching"),
}

/** What each [LaunchSoundVariant] does; pure, so the table is unit-tested. */
object LaunchSoundPlan {
    /** The theme's launch sample is played the moment A is pressed. */
    fun launchSoundAtPress(variant: LaunchSoundVariant) =
        variant == LaunchSoundVariant.A || variant == LaunchSoundVariant.C

    /** The theme's launch sample is held back until the launch is really dispatched. */
    fun launchSoundAtDispatch(variant: LaunchSoundVariant) = variant == LaunchSoundVariant.B

    /** The preview video is paused and the navigation sounds are muted while "Launch on which screen?" is up. */
    fun quietWhileChooser(variant: LaunchSoundVariant) =
        variant == LaunchSoundVariant.C || variant == LaunchSoundVariant.D

    /** The same silence, from the A press itself (before any screen question). */
    fun quietFromPress(variant: LaunchSoundVariant) = variant == LaunchSoundVariant.D

    /** The stored value back to a variant; anything unknown is [LaunchSoundVariant.A]. */
    fun parse(stored: String?): LaunchSoundVariant =
        LaunchSoundVariant.entries.firstOrNull { it.name == stored } ?: LaunchSoundVariant.A
}

/** The stored choice of [LaunchSoundVariant], in droidtop's one preferences file. */
object LaunchSoundExperiment {
    private const val KEY = "droidtop_launch_sound_variant"

    /** Reads the choice (an in-memory preferences read) and tells the log which letter is in force. */
    fun variant(context: Context): LaunchSoundVariant =
        LaunchSoundPlan.parse(
            context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).getString(KEY, null),
        ).also { AudioHandOff.variantTag = it.name }

    fun set(context: Context, variant: LaunchSoundVariant) {
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, variant.name).apply()
        AudioHandOff.variantTag = variant.name
    }
}

/**
 * The one launch audio hand-off (docs/SPEC.md "Launch audio hand-off",
 * Droidtop/tracker#160): before another app comes in front, droidtop
 * closes EVERY audio output stream of its own, and opens them again when
 * the user is back.
 *
 * Pausing is not enough. A paused ExoPlayer keeps its AudioTrack, SoundPool
 * keeps one AudioTrack per stream after a sample ends, and the Desktop
 * session's PulseAudio keeps a low-latency AAudio stream running and
 * writing silence for as long as the container is up. When the launched
 * app opens its own output, the audio HAL reconfigures the mixer and
 * route underneath every stream that is still open; on the Retroid
 * Pocket 5 that is heard as a burst of static.
 *
 * Every owner of an output stream registers a [Holder]. [release] runs
 * them all before a launch intent is dispatched and from any droidtop
 * activity's onPause (DroidtopApplication); [reopen] runs them when a
 * droidtop activity is resumed or regains top focus. Each release is
 * logged under [TAG] with what was actually closed, so a console logcat
 * shows the hand-off happened.
 */
object AudioHandOff {
    const val TAG = "droidtop.audio"

    interface Holder {
        /** Short name for the log, e.g. "navigation sounds". */
        val name: String

        /**
         * Closes every output stream this holder has open. Called on the
         * main thread; [fade] asks for a short ramp to silence first (false
         * when the other app is already coming in, e.g. from onPause).
         * Returns what was closed, or null when nothing was open.
         */
        suspend fun release(fade: Boolean): String?

        /** Opens again what [release] closed. Called on the main thread. */
        suspend fun reopen()

        /**
         * Silences (true) or restores (false) what this holder has open,
         * without closing it: the launch-static experiment's "quiet while
         * asking which screen" ([LaunchSoundVariant.C], [LaunchSoundVariant.D]).
         * Called on the main thread.
         */
        fun setQuiet(quiet: Boolean) = Unit
    }

    /** The experiment letter every line of the timeline carries; see [LaunchSoundExperiment]. */
    @Volatile
    var variantTag: String = LaunchSoundVariant.A.name
        internal set

    /**
     * One line of the launch audio timeline: a monotonic timestamp
     * (uptimeMillis) and the experiment letter, so one logcat shows the
     * exact order and gaps of every audio open, start, stop and release,
     * the launch dispatch and droidtop's activity lifecycle
     * (Droidtop/tracker#160).
     */
    fun mark(what: String) {
        Log.i(TAG, "t=${SystemClock.uptimeMillis()} [$variantTag] $what")
    }

    private val holders = CopyOnWriteArrayList<Holder>()
    private val mutex = Mutex()
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private val traceScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /** Silences or restores every holder's open streams without closing them (see [Holder.setQuiet]). Main thread. */
    fun setQuiet(reason: String, quiet: Boolean) {
        mark("droidtop sounds ${if (quiet) "silenced" else "restored"} ($reason)")
        for (holder in holders) {
            try {
                holder.setQuiet(quiet)
            } catch (e: Exception) {
                Log.w(TAG, "${holder.name}: could not ${if (quiet) "silence" else "restore"}: ${e.message}")
            }
        }
    }

    private val PLAYER_SNAPSHOTS_MS = longArrayOf(100L, 300L, 1000L)

    /**
     * Logs what the audio framework says is playing and recording, now and
     * 100, 300 and 1000 ms later: the playback and recording configurations
     * with their uid, pid and state (read-only, a few binder calls, called
     * only as a launch is dispatched). The snapshots run off the main thread.
     */
    fun traceLaunch(context: Context) {
        val app = context.applicationContext
        val start = SystemClock.uptimeMillis()
        traceScope.launch {
            snapshotPlayers(app, "at hand-off")
            for (after in PLAYER_SNAPSHOTS_MS) {
                val wait = start + after - SystemClock.uptimeMillis()
                if (wait > 0) delay(wait)
                snapshotPlayers(app, "+$after ms")
            }
        }
    }

    private fun snapshotPlayers(context: Context, label: String) {
        val text = try {
            val am = context.getSystemService(AudioManager::class.java)
            val playing = am.activePlaybackConfigurations
            val recording = am.activeRecordingConfigurations
            "playback ${playing.size} [${playing.joinToString(" | ")}]; recording ${recording.size} [${recording.joinToString(" | ")}]"
        } catch (e: Exception) {
            "unreadable (${e.message})"
        }
        mark("audio players $label: $text")
    }

    private val handedOffState = MutableStateFlow(false)

    /** True while another app has the audio; nothing of droidtop's may open an output stream. */
    val handedOff: StateFlow<Boolean> = handedOffState.asStateFlow()

    fun register(holder: Holder) {
        if (holder !in holders) holders += holder
    }

    fun unregister(holder: Holder) {
        holders -= holder
    }

    /**
     * Closes every registered output stream; returns once they are all
     * closed. Call it on the main thread before dispatching a launch.
     */
    suspend fun release(reason: String, fade: Boolean = true): Unit = mutex.withLock {
        mark("hand-off ($reason) begins")
        handedOffState.value = true
        // Each holder starts undispatched, so all the synchronous closes
        // run before the first holder that moves work off the main thread
        // gets to suspend.
        val closed = coroutineScope {
            holders.map { holder ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    val what = try {
                        holder.release(fade)
                    } catch (e: Exception) {
                        "failed to close: ${e.message}"
                    }
                    "${holder.name}: ${what ?: "nothing open"}"
                }
            }.awaitAll()
        }
        // Whatever the experiment silenced is gone now; the flags must not
        // outlive the streams, or the sounds opened on return stay muted.
        for (holder in holders) holder.setQuiet(false)
        mark("hand-off ($reason): " + closed.ifEmpty { listOf("no audio holders registered") }.joinToString("; "))
    }

    /**
     * [release] for callers that cannot suspend (onPause). Runs on the main
     * thread at once, so every synchronous close (players, SoundPool) has
     * happened before this returns; only work a holder moves off the main
     * thread finishes later.
     */
    fun releaseNow(reason: String) {
        scope.launch { release(reason, fade = false) }
    }

    /** Opens again what [release] closed; a no-op when nothing was handed off. */
    fun reopen(reason: String) {
        scope.launch {
            mutex.withLock {
                if (!handedOffState.value) return@withLock
                handedOffState.value = false
                for (holder in holders) {
                    try {
                        holder.reopen()
                    } catch (e: Exception) {
                        Log.w(TAG, "${holder.name}: could not reopen: ${e.message}")
                    }
                }
                mark("back ($reason): reopened ${holders.joinToString { it.name }.ifEmpty { "nothing" }}")
            }
        }
    }
}
