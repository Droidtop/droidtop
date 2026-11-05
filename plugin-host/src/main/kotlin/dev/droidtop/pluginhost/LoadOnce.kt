package dev.droidtop.pluginhost

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A plugin loaded once per process, not once per call (Droidtop/tracker#884). [PluginCrashPolicy] asks its runner to
 * load a plugin before every call, which is how a stopped or restarted process is started again. A plugin already
 * loaded in its live process returned true only after droidtop had acquired the process, read the grants and every
 * installed record, opened every file the plugin needs, started a new file broker and made a blocking binder call; one
 * search across N sources did that N times at once, on BlueStacks with the main thread starved of page-ins.
 *
 * So a load is remembered: under [key] (the tier and the installed bundle, so a new grant or an update loads again)
 * with the [H] handle it gave (the process's runtime). A later load of the same key returns true at once while [alive]
 * says the handle is still the plugin's live process; callers at the same time wait for the one load in progress
 * instead of each starting their own. A failed load is never remembered. [forget] drops one plugin's load, for an
 * unload or a crash. Pure apart from the coroutine lock, for the tests.
 */
class LoadOnce<H : Any> {
    private class Done<H>(val key: String, val handle: H)

    private val done = ConcurrentHashMap<String, Done<H>>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * True when [pluginId] is loaded under [key]: at once when an earlier load of that key is still [alive], else after
     * [block], which loads it and gives its handle, or null when it did not load.
     */
    suspend fun load(pluginId: String, key: String, alive: (H) -> Boolean, block: suspend () -> H?): Boolean =
        locks.getOrPut(pluginId) { Mutex() }.withLock {
            done[pluginId]?.takeIf { it.key == key && alive(it.handle) }?.let { return@withLock true }
            done.remove(pluginId)
            val handle = block() ?: return@withLock false
            done[pluginId] = Done(key, handle)
            true
        }

    /** [pluginId]'s load is no longer to be trusted: the next [load] loads it again. */
    fun forget(pluginId: String) {
        done.remove(pluginId)
    }
}
