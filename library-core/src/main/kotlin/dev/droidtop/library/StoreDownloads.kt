package dev.droidtop.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The store downloads running right now, by store id ("steam:440"): the one
 * live fact a library entry cannot carry, because it changes every second
 * and is not part of what a scan indexes (docs/SPEC.md 7i, "Capsules and
 * the primary action").
 *
 * The store install jobs fill it ([dev.droidtop.library.stores.StoreInstallJob],
 * every store, Steam included), through a share of their own ([publish]; a
 * second publisher would get its own share). The shell reads [active] to draw a
 * progress badge on a capsule and to turn the primary button into
 * Downloading, with the same one answer for the capsule, the game page and
 * the menu. A plain in-memory map: nothing here touches a disk or the
 * network.
 */
object StoreDownloads {
    /** One running download: how far it is (0 to 1) and whether it is stopped part-way. */
    data class Progress(val fraction: Float, val paused: Boolean) {
        /** Whole percent, for the one line that names it. */
        val percent: Int get() = (fraction.coerceIn(0f, 1f) * 100f).toInt()
    }

    private val shares = HashMap<String, Map<String, Progress>>()
    private val state = MutableStateFlow<Map<String, Progress>>(emptyMap())

    /** Every download in flight, keyed by store id; empty when there is none. */
    val active: StateFlow<Map<String, Progress>> = state

    /**
     * Replaces what [publisher] says is running; the other publishers' shares
     * stay. An identical answer changes nothing, so nothing redraws.
     */
    @Synchronized
    fun publish(publisher: String, downloads: Map<String, Progress>) {
        if (shares[publisher] == downloads) return
        if (downloads.isEmpty()) shares.remove(publisher) else shares[publisher] = downloads
        val merged = HashMap<String, Progress>()
        shares.values.forEach { merged.putAll(it) }
        if (state.value != merged) state.value = merged
    }
}
