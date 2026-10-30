package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the source plugins started while a search can use them (Droidtop/tracker#848). The owner: "We need to optimize
 * our python runner somehow". A plugin's process is let go a minute after its last call ([dev.droidtop.pluginhost.PluginProcesses]),
 * so a search a little later started every source cold, and a slow start (about 10 s on BlueStacks, #103) used up the
 * search's whole budget, so the source read "did not answer in time".
 *
 * While held ([hold]: the search screen, Get games, a search in flight), one runner keeps each source it started
 * loaded, so its process stays up; [prewarm] starts them all at once when the screen opens, while the person types.
 * Let go ([release]), they stay for [LINGER_MS] more, then the usual idle applies.
 */
object SourceWarmth {
    /** How long the sources stay started after the last search screen closes. */
    const val LINGER_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var holders = 0
    private var policy: PluginCrashPolicy? = null
    private var letGo: Job? = null

    /** One more user; returns at once. Pair each with [release]. */
    fun hold(context: Context) {
        synchronized(lock) {
            holders++
            letGo?.cancel()
            letGo = null
            if (policy == null) policy = PluginCrashPolicy(context.applicationContext)
        }
    }

    /** One user fewer; the last one starts the [LINGER_MS] countdown. */
    fun release() {
        synchronized(lock) {
            if (holders > 0) holders--
            if (holders > 0 || policy == null) return
            letGo?.cancel()
            letGo = scope.launch {
                delay(LINGER_MS)
                val ending = synchronized(lock) {
                    if (holders > 0) return@launch
                    policy.also { policy = null; letGo = null }
                }
                ending?.shutdown()
            }
        }
    }

    /**
     * Starts [record] as a source, in the held runner when there is one (so it stays up), else in one of its own let go
     * at once. Null when ready, else why not.
     */
    suspend fun warm(context: Context, record: PluginRecord): String? {
        val held = synchronized(lock) { policy }
        if (held != null) return held.prepare(record, POINT)
        val own = PluginCrashPolicy(context.applicationContext)
        return try {
            own.prepare(record, POINT)
        } finally {
            own.shutdown()
        }
    }

    /**
     * The search screen or Get games opened: holds the sources and starts the ones for [platform] at once, all together,
     * while the person types. [release] when the screen closes.
     */
    fun prewarm(context: Context, platform: String?) {
        hold(context)
        val app = context.applicationContext
        scope.launch {
            GameSources.plugins(app, platform).forEach { source -> launch { runCatching { source.warm(app) } } }
        }
    }

    private const val POINT = "library.sources"
}
