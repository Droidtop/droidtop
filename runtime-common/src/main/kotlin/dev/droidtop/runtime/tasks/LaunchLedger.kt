package dev.droidtop.runtime.tasks

import java.util.concurrent.ConcurrentHashMap

/**
 * Every app droidtop started, and on which display: the one running-apps source that needs no privilege,
 * and how the Quick Menu knows which app is the one in front. Process memory only; a restart forgets it,
 * which is the honest direction (an empty list, never an invented one). `LaunchDisplay`'s one dispatch
 * point notes each launch, so no launch path is missed.
 */
object LaunchLedger {
    /** A launch older than this is dropped from the list: nothing confirms it is still there. */
    const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    data class Launched(val packageName: String, val displayId: Int, val atMs: Long)

    private val byPackage = ConcurrentHashMap<String, Launched>()

    @Volatile
    var last: Launched? = null
        private set

    fun note(packageName: String, displayId: Int, nowMs: Long = System.currentTimeMillis()) {
        val launched = Launched(packageName, displayId, nowMs)
        byPackage[packageName] = launched
        last = launched
    }

    /** Forget [packageName] after it was closed. */
    fun forget(packageName: String) {
        byPackage.remove(packageName)
        if (last?.packageName == packageName) last = null
    }

    /** What is still believed open, most recent first. */
    fun entries(nowMs: Long = System.currentTimeMillis()): List<Launched> =
        byPackage.values.filter { nowMs - it.atMs <= MAX_AGE_MS }.sortedByDescending { it.atMs }

    internal fun clearForTest() {
        byPackage.clear()
        last = null
    }
}
