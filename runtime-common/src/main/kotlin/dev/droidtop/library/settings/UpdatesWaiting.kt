package dev.droidtop.library.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How many updates are waiting, everything the Updates page lists (droidtop, plugins, apps, games), as one number every
 * entry to the page reads (docs/SPEC.md 10b "The Updates page", Droidtop/tracker#792): the left menu's badge on
 * Updates, the Quick Menu's "Updates · N available" row, the companion's Home. The page publishes it each time it lists
 * its rows; [refresh] (installed by `:app`, where the page is) lists them again in the background, for an entry opened
 * before the page was.
 */
object UpdatesWaiting {
    private val waiting = MutableStateFlow(0)
    val count: StateFlow<Int> = waiting

    fun publish(n: Int) {
        waiting.value = n.coerceAtLeast(0)
    }

    /** Lists the updates again off the main thread and publishes their number; set by `:app`. */
    @Volatile
    var refresh: ((Context) -> Unit)? = null

    /** "Updates · 3 available" and the like: what an entry to the page says. Pure. */
    fun line(n: Int): String = if (n <= 0) "Up to date" else "$n available"
}
