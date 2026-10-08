package dev.droidtop.app.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.droidtop.library.settings.Modes
import kotlinx.coroutines.launch

/**
 * droidtop's process-start hook, and the shared core only.
 *
 * A manifest-declared ContentProvider's onCreate runs before Application.
 * onCreate and before ANY Activity in the process, so this is where the
 * mode snapshot is taken: everything downstream -- including
 * [dev.droidtop.app.ModeStartup], which runs from Application.onCreate --
 * asks [Modes] rather than reading preferences of its own.
 *
 * What runs here runs in every mode: the settings-catalog registration
 * (:shell-default's SettingsActivity renders these catalogs but cannot
 * depend on :app, so they must be registered before it can resolve a
 * screen by id), and the release probe. Anything a single mode owns is in
 * [dev.droidtop.app.ModeStartup] instead. Provides no content; the
 * provider mechanism is only the earliest ordered process-start hook
 * Android offers an app module.
 */
class SettingsCatalogInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        // First, because everything else asks it what to start. Also
        // where the one-time Handheld -> Gaming preference migration runs.
        Modes.load(appContext)
        // Native jobs register before attach restores any, so a paused scrape found on
        // disk can be resumed (docs/SPEC.md 12a "Jobs").
        dev.droidtop.library.scraper.LibraryScrapeJob.register(appContext)
        // The stores droidtop runs itself, then their installs and updates,
        // a job each (docs/SPEC.md 7g, "Stores").
        dev.droidtop.stores.BuiltInStores.register()
        dev.droidtop.library.stores.StoreInstallJob.register(appContext)
        // A store game's cloud saves, uploaded when the game ends, a job each (7g, "Stores").
        dev.droidtop.library.stores.StoreSaves.register(appContext)
        // The one single-file download runner and the post step of the plugin catalog's bundles; a
        // download that finished while the process was dead is re-attached and finished by these.
        dev.droidtop.pluginhost.DownloadJobs.register(appContext)
        dev.droidtop.library.integrations.PluginCatalog.registerDownloadPost()
        dev.droidtop.library.consoles.RetroArchCores.registerDownloadPost()
        dev.droidtop.pluginhost.PluginJobsCenter.attach(appContext)
        dev.droidtop.app.JobsSummaryNotification.start(appContext)
        dev.droidtop.app.LaunchWatchNotification.start(appContext)
        AppSettingsCatalogs.ensureRegistered()
        dev.droidtop.library.settings.LibraryRescan.handler = { ctx, onStatus ->
            onStatus("Looking for new or changed games and apps\u2026")
            val started = android.os.SystemClock.elapsedRealtime()
            val library = dev.droidtop.app.LibraryCore.library(ctx)
            // What the walks say they are doing ("Looking at PC game
            // folders: 12 of 21"), live, while they run. A walk over a slow
            // card is minutes, and a row that says nothing for minutes
            // reads as a hang (Droidtop/tracker#275).
            val games = kotlinx.coroutines.coroutineScope {
                val progress = launch {
                    dev.droidtop.library.ScanActivity.state.collect { running ->
                        dev.droidtop.library.ScanActivity.describe(running)
                            ?.let { onStatus("$it. Select again to cancel.") }
                    }
                }
                try {
                    library.rescanNow(dev.droidtop.library.LibraryKinds.GAMES)
                } finally {
                    progress.cancel()
                }
            }
            onStatus("Games done, looking at apps\u2026")
            val apps = library.rescanNow(dev.droidtop.library.LibraryKinds.APPS)
            val seconds = (android.os.SystemClock.elapsedRealtime() - started + 500) / 1000
            "Rescan finished in $seconds s: $games game folders and store entries, $apps apps."
        }
        // The at-most-daily release probe (one small unauthenticated
        // download, off switch in Settings > Software updates). Process
        // start is the honest trigger: droidtop is a launcher, so its
        // process starts roughly once per boot rather than per use.
        dev.droidtop.app.update.AppSelfUpdate.maybeCheck(appContext)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
