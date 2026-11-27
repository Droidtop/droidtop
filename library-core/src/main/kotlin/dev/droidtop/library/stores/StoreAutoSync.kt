package dev.droidtop.library.stores

import android.content.Context

/**
 * Reads the signed-in stores' libraries again when the Gaming shell opens, if the last good read
 * is older than the person's interval and Download rules allow this network (docs/SPEC.md 7g, "Stores",
 * mytop-project/tracker#225). There is no timer, loop or worker: it runs once per opening, from the
 * shell, through the one [StoreSyncs.run], and a store whose read was recent is not asked.
 */
object StoreAutoSync {
    private const val PREFS = "store_library_sync_settings"
    private const val KEY_INTERVAL = "interval"

    /** How often the libraries are read without being asked; [hours] is 0 for Off. */
    enum class Interval(val label: String, val hours: Int) {
        OFF("Off", 0),
        SIX_HOURS("Every 6 hours", 6),
        DAILY("Daily", 24),
    }

    /** The setting; Every 6 hours until the person picks another. */
    fun interval(context: Context): Interval =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_INTERVAL, null)
            ?.let { name -> Interval.entries.firstOrNull { it.name == name } } ?: Interval.SIX_HOURS

    fun setInterval(context: Context, interval: Interval) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_INTERVAL, interval.name).apply()
    }

    /** Whether a store last read at [lastMs] (null: never) is due at [nowMs]. Pure. */
    fun due(interval: Interval, lastMs: Long?, nowMs: Long): Boolean = when {
        interval == Interval.OFF -> false
        lastMs == null -> true
        // A clock set back must not hold a store off until it catches up.
        lastMs > nowMs -> true
        else -> nowMs - lastMs >= interval.hours * 3_600_000L
    }

    /** A store asked a moment ago is not asked again for this long, so reopening the shell does not hammer one that is down. */
    private const val RETRY_MS = 15 * 60_000L
    private val lastAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Reads every due, signed-in store, one after the other, off the main thread, and announces the
     * change once if any read worked. Does nothing offline, on a network Download rules keep it off, or with the setting Off.
     */
    suspend fun runIfDue(context: Context) {
        val app = context.applicationContext
        val interval = interval(app)
        if (interval == Interval.OFF || !networkAllows(app)) return
        var changed = false
        // A plugin's stores are read again first, so one installed since droidtop started is synced too (A13).
        runCatching { dev.droidtop.library.integrations.PluginStores.refresh(context) }
        for (store in StoreLibraries.all()) {
            val now = System.currentTimeMillis()
            if (!runCatching { store.signedIn(app) }.getOrDefault(false)) continue
            if (!due(interval, StoreSyncs.lastSynced(app, store.id), now)) continue
            if (now - (lastAsked[store.id] ?: 0L) < RETRY_MS) continue
            lastAsked[store.id] = now
            if (StoreSyncs.run(app, store).isSuccess) changed = true
        }
        if (changed) StoreChanges.announce(app)
    }

    /**
     * Whether this network may be used for a sync: online, and on a metered one only when Download rules' network rule
     * allows checks there (a sync is a few small reads, like an update check: only Wi-Fi only stops it). One rule for
     * every job (mytop-project/tracker#964); this used to be "unmetered only" whatever Download rules said.
     */
    private fun networkAllows(context: Context): Boolean {
        dev.droidtop.net.DownloadPolicy.load(context)
        return when (dev.droidtop.net.DownloadPolicy.networkNow(context)) {
            dev.droidtop.net.NetworkClass.OFFLINE -> false
            dev.droidtop.net.NetworkClass.UNMETERED -> true
            dev.droidtop.net.NetworkClass.METERED -> dev.droidtop.net.DownloadPolicy.checksOnMetered(dev.droidtop.net.DownloadPolicy.settings.value)
        }
    }
}
