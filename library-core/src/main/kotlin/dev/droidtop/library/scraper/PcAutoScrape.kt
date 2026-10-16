package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import dev.droidtop.runtime.prefs.PrefsFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PC and engine games get their details by themselves (docs/SPEC.md 7h, Droidtop/tracker#568; the owner, 2026-10-16:
 * "can we add an automatic scraping system? We have LOTS of games with no metadata", then "make sure pc game scraping
 * and stuff is enabled"). On by default: shortly after droidtop starts and after a store's library is read, the
 * one library scrape job runs over the PC and engine games (`LibraryScrapeJob.start(pcOnly = true)`), with the
 * sources a PC scrape already uses and that need no account (Lutris, the Steam store's appdetails) plus any the person
 * set up. Games that already have their details and art are skipped by the scrape's own filter, so a run over a
 * finished library asks nothing. It runs at most every [MIN_INTERVAL_MS] and is a job in Downloads like any scrape (it
 * can be paused or cancelled; a paused one is resumed by the next run). It is turned off in Settings, Scraper.
 */
object PcAutoScrape {
    private const val KEY_ON = "droidtop_scrape_pc_auto"
    private const val STATE = "pc_auto_scrape"
    private const val KEY_LAST = "last_start_ms"
    private const val START_DELAY_MS = 2L * 60 * 1000
    const val MIN_INTERVAL_MS = 12L * 60 * 60 * 1000
    const val TITLE = "Get details for PC games"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) = PrefsFile(context, LAUNCHER_PREFS_FILE_NAME)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).putBoolean(KEY_ON, value)
    }

    /** Whether a run is due: on, and none started within [MIN_INTERVAL_MS] of [now]. Pure. */
    internal fun due(enabled: Boolean, lastMs: Long, now: Long): Boolean =
        enabled && (lastMs <= 0L || now - lastMs >= MIN_INTERVAL_MS)

    /** Asks for a run soon after start: the library index and everything else starting need a moment. */
    fun requestLater(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(START_DELAY_MS)
            request(app)
        }
    }

    /** Asks for a run now (after a store sync); nothing happens when it is off or ran recently. Off the main thread. */
    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val now = System.currentTimeMillis()
            val state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE)
            if (!due(enabled(app), state.getLong(KEY_LAST, 0L), now)) return@launch
            state.edit().putLong(KEY_LAST, now).apply()
            runCatching { LibraryScrapeJob.start(app, TITLE, pcOnly = true) }
        }
    }
}
