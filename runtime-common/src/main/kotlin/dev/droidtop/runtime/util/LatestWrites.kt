package dev.droidtop.runtime.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Writes of which only the newest matters, run off the caller's thread one at a time: a slider dragged across its
 * track asks for a write at every step, and Android's setting (a volume, a brightness, a helper's command) need only
 * end at where the finger stopped. Each write has a key (the control's id); a write waiting under a key is replaced by
 * a newer one, and different keys all run, in the order they first asked. The companion's one write path for live
 * controls (docs/SPEC.md "The companion's tabs", Droidtop/tracker#786 S2a: "Slider writes go through a background
 * write queue"). A write that throws is dropped; the next one still runs.
 */
class LatestWrites(private val scope: CoroutineScope) {
    private val pending = LinkedHashMap<String, suspend () -> Unit>()
    private var draining: Job? = null

    fun submit(key: String, write: suspend () -> Unit) {
        synchronized(pending) {
            pending[key] = write
            if (draining == null) draining = scope.launch { drain() }
        }
    }

    /** Waits until nothing is waiting or running. For tests and for a page that must read back what it wrote. */
    suspend fun idle() {
        while (true) {
            val job = synchronized(pending) { draining } ?: return
            job.join()
        }
    }

    private suspend fun drain() {
        while (true) {
            val next = synchronized(pending) {
                val first = pending.entries.firstOrNull()
                if (first == null) {
                    draining = null
                    null
                } else {
                    pending.remove(first.key)
                    first.value
                }
            } ?: return
            runCatching { next() }
        }
    }

    companion object {
        /** The app's one queue, on droidtop's process scope. */
        val app: LatestWrites by lazy { LatestWrites(AppScopes.io) }
    }
}
