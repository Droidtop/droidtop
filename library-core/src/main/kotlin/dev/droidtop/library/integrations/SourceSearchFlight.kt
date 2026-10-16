package dev.droidtop.library.integrations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * At most one `search` per source in flight (docs/plugin-api.md "Fetchers answer in the one search", Droidtop/tracker#218):
 * no kind can interrupt a plugin's call, so a search the person typed past is not cancelled at the plugin. Instead a
 * source is sent nothing more while its call runs; the call itself runs in [scope], apart from the search that asked,
 * so when that search is left (the query moved on) the source stays busy until it answers and its reply is dropped.
 * A later search waits for it and then asks; one that was itself left while waiting never asks, so of the queries
 * typed meanwhile only the newest is sent.
 */
internal class SourceSearchFlight(private val scope: CoroutineScope) {
    private val running = HashMap<String, Deferred<*>>()

    suspend fun <T> run(key: String, call: suspend () -> T): T {
        while (true) {
            currentCoroutineContext().ensureActive()
            val mine = scope.async(start = CoroutineStart.LAZY) { call() }
            val other = synchronized(running) {
                val now = running[key]
                if (now == null || now.isCompleted) {
                    running[key] = mine
                    null
                } else {
                    now
                }
            }
            if (other != null) {
                mine.cancel()
                other.join()
                continue
            }
            mine.invokeOnCompletion { synchronized(running) { if (running[key] === mine) running.remove(key) } }
            mine.start()
            return mine.await()
        }
    }

    companion object {
        /** The one search's: every source's calls, process-wide. */
        val shared = SourceSearchFlight(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
