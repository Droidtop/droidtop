package dev.droidtop.runtime

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    }

    private val holders = CopyOnWriteArrayList<Holder>()
    private val mutex = Mutex()
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

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
    suspend fun release(reason: String, fade: Boolean = true) = mutex.withLock {
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
        Log.i(TAG, "hand-off ($reason): " + closed.ifEmpty { listOf("no audio holders registered") }.joinToString("; "))
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
                Log.i(TAG, "back ($reason): reopened ${holders.joinToString { it.name }.ifEmpty { "nothing" }}")
            }
        }
    }
}
