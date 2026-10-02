package dev.droidtop.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The store downloads running right now, by store id ("steam:440"): the one
 * live fact a library entry cannot carry, because it changes every second
 * and is not part of what a scan indexes (docs/SPEC.md 7i, "Capsules and
 * the primary action").
 *
 * `:runtime-windows` writes it from the vendored store services' own
 * download state ([publish]); the shell reads [active] to draw a progress
 * badge on a capsule and to turn the primary button into Downloading, with
 * the same one answer for the capsule, the game page and the menu. A plain
 * in-memory map: nothing here touches a disk or the network.
 */
object StoreDownloads {
    /** One running download: how far it is (0 to 1) and whether it is stopped part-way. */
    data class Progress(val fraction: Float, val paused: Boolean) {
        /** Whole percent, for the one line that names it. */
        val percent: Int get() = (fraction.coerceIn(0f, 1f) * 100f).toInt()
    }

    private val state = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Every download in flight, keyed by store id; empty when there is none. */
    val active: StateFlow<Map<String, Progress>> = state

    /** Replaces what is running; an identical answer changes nothing, so nothing redraws. */
    fun publish(downloads: Map<String, Progress>) {
        if (state.value != downloads) state.value = downloads
    }
}
