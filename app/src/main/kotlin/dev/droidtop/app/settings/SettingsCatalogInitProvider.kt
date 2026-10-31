package dev.droidtop.app.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import dev.droidtop.library.settings.Modes
import kotlinx.coroutines.flow.lastOrNull
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
    /** The Appearance preference's listener: SharedPreferences keeps listeners weakly, so this holds it. */
    private var appearanceWatch: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return true
        // First, because everything else asks it what to start. Also
        // where the one-time Handheld -> Gaming preference migration runs.
        Modes.load(appContext)
        // Native jobs register before attach restores any, so a paused scrape found on
        // disk can be resumed (docs/SPEC.md 12a "Jobs").
        dev.droidtop.library.scraper.LibraryScrapeJob.register(appContext)
        // The games the scrape of PC and engine games works through: the library's own index, walked
        // only when it has none yet (never a second scan of its own).
        dev.droidtop.library.scraper.LibraryScrapeJob.libraryGames = { ctx ->
            dev.droidtop.app.LibraryCore.library(ctx).scanKindsProgressive(dev.droidtop.library.LibraryKinds.GAMES).lastOrNull().orEmpty()
        }
        // The stores droidtop runs itself, then their installs and updates,
        // a job each (docs/SPEC.md 7g, "Stores").
        dev.droidtop.stores.BuiltInStores.register()
        // A plugin's stores beside them (docs/plugin-api.md 3 A13), once the plugins are read: off this thread.
        dev.droidtop.library.integrations.PluginStores.refreshAsync(appContext)
        dev.droidtop.library.stores.StoreInstallJob.register(appContext)
        // The desktop's fonts for all languages, a job of its own (Droidtop/tracker#390).
        dev.droidtop.app.DesktopFontsJob.register(appContext)
        // A store game's cloud saves, uploaded when the game ends, a job each (7g, "Stores").
        dev.droidtop.library.stores.StoreSaves.register(appContext)
        // Copying a game's version between this device and a computer (docs/SPEC.md 7o, "Game updates").
        dev.droidtop.library.computers.ComputerGames.register(appContext) { dev.droidtop.app.LibraryCore.library(it) }
        // The paired devices plugins see (docs/plugin-api.md D21): they hear of a change, and the paired handhelds
        // are served again while a plugin has announced something to them; a file read and a listener, off this thread.
        dev.droidtop.net.peer.PairedDevices.onChanged = { ctx -> dev.droidtop.library.integrations.notifyDevicesChanged(ctx) }
        // A synced copy of a plugin's context arrived (docs/plugin-api.md F8): its plugin hears of it (Droidtop/tracker#794).
        dev.droidtop.pluginhost.PluginContexts.onReceived = { plugin, contextId, generation ->
            dev.droidtop.library.integrations.notifyContextReceived(appContext, plugin, contextId, generation)
        }
        Thread({ runCatching { dev.droidtop.net.peer.PairedDevices.serve(appContext) } }, "devices-serve").start()
        // The role tokens UI droidtop does not draw follows (docs/SPEC.md 7k, Droidtop/tracker#559): plugins hear when
        // they change, looked at again on Light or dark, on Android's night mode, and by the Gaming shell on a theme.
        // droidtop's home-screen widgets are drawn in the same tokens, so they are drawn again too (Droidtop/tracker#563).
        dev.droidtop.library.theme.RoleTokens.onChanged = { ctx, set ->
            dev.droidtop.library.integrations.notifyThemeChanged(ctx, set.light)
            dev.droidtop.app.DroidtopWidgets.refreshAll(ctx)
        }
        // The Downloads widget follows the jobs while the process lives (Droidtop/tracker#563).
        dev.droidtop.app.DownloadsWidgetProvider.watch(appContext)
        dev.droidtop.app.DesktopWidgetProvider.watch(appContext)
        dev.droidtop.app.CompanionWidgetProvider.watch(appContext)
        // The one icon pack choice, Standard's launcher's, shown in the shared catalog's Appearance (#508).
        dev.droidtop.library.settings.IconPacks.source = StandardIconPacks
        // A press on a Plugin widget redraws it with a fresh snapshot (PluginWidgetActionReceiver).
        dev.droidtop.library.integrations.PluginWidgets.onActed = { context, id ->
            dev.droidtop.app.PluginWidgetProvider.requestUpdate(context, id, force = true)
        }
        dev.droidtop.library.theme.RoleTokens.attach(appContext)
        // The Gaming theme's colours for everything outside Gaming (docs/SPEC.md 7k, "Colours outside Gaming",
        // Droidtop/tracker#827): read back or worked out now, off the main thread, and again whenever the theme or what
        // its colours depend on changes, whether or not Gaming ever opens.
        dev.droidtop.library.theme.ThemeColorStore.install(appContext)
        appearanceWatch = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == dev.droidtop.library.settings.AppearancePrefs.KEY_APPEARANCE) dev.droidtop.library.theme.RoleTokens.checkSoon()
        }.also { dev.droidtop.library.settings.CatalogPrefs.prefs(appContext).registerOnSharedPreferenceChangeListener(it) }
        appContext.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = dev.droidtop.library.theme.RoleTokens.checkSoon()

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
        // Every social provider's message notifications and the Quick Menu's unread count
        // (SPEC "Social"), then Steam, which stays connected while signed in for friends and
        // chat: its service, then the connection itself (7g, "Stores").
        dev.droidtop.app.SocialNotifications.install(appContext)
        dev.droidtop.app.SteamConnectionService.install(appContext)
        // The one single-file download runner and the post step of the plugin catalog's bundles; a
        // download that finished while the process was dead is re-attached and finished by these.
        dev.droidtop.pluginhost.DownloadJobs.register(appContext)
        dev.droidtop.library.integrations.PluginCatalog.registerDownloadPost()
        dev.droidtop.library.integrations.PluginBios.registerDownloadPost()
        // The one APK install path's post step: a catalog's app is checked, then installed (docs/SPEC.md 10b).
        dev.droidtop.app.apps.ApkInstaller.registerDownloadPost()
        // droidtop's own update: the same download job, finished by asking the installer (docs/SPEC.md 10b).
        dev.droidtop.app.update.UpdateDownload.register()
        // Links (docs/SPEC.md 12a "Links", "Action links"): droidtop's own action links are the router's built-in
        // handler, an app catalog's link opens its source review, and the scheme aliases follow what the installed
        // plugins register.
        dev.droidtop.library.integrations.LinkRouter.registerBuiltIn(dev.droidtop.library.integrations.ActionLinks.routerHandler)
        dev.droidtop.library.integrations.LinkRouter.registerPoint(
            dev.droidtop.library.integrations.AppCatalogs.POINT,
            dev.droidtop.library.integrations.AppCatalogScreen.linkHandler,
        )
        // A magnet link can go to droidtop's own Downloads (Add download), beside any plugin that takes magnets.
        dev.droidtop.library.integrations.LinkRouter.registerKind(
            dev.droidtop.library.integrations.ManualDownloads.LINK_KIND,
            dev.droidtop.library.integrations.ManualDownloads.linkKind,
        )
        dev.droidtop.library.integrations.LinkRouter.registerSource(dev.droidtop.library.integrations.ManualDownloads.linkSource)
        dev.droidtop.app.LinkSchemes.start(appContext)
        // The installed build's own APK and older partial updates are dead weight, and a restored job
        // must not fetch an old build again (tracker#445); runs once the jobs are restored.
        dev.droidtop.pluginhost.PluginJobsCenter.onRestored = {
            dev.droidtop.app.update.AppSelfUpdate.cleanStaleDownloads(appContext)
        }
        dev.droidtop.pluginhost.PluginJobsCenter.attach(appContext)
        // The engines database decides the "Engine" badge (runsInEnginehost, docs/SPEC.md after the 7e2b table): read
        // it now, off the main thread, so the first cards drawn already have it.
        dev.droidtop.runtime.util.AppScopes.io.launch { dev.droidtop.library.EnginesDatabase.defs(appContext) }
        dev.droidtop.app.JobsSummaryNotification.start(appContext)
        // A download ending is one short flash, grouped while it is up (Droidtop/tracker#643).
        dev.droidtop.library.integrations.JobNotices.start()
        dev.droidtop.app.LaunchWatchNotification.start(appContext)
        AppSettingsCatalogs.ensureRegistered()
        dev.droidtop.library.settings.HomeRows.forgetLaunchLog = { ctx -> dev.droidtop.app.LibraryCore.library(ctx).forgetStarts() }
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
        // "Rescan PC game folders" (docs/SPEC.md 7j "Places", Droidtop/tracker#397 slice E): the PC and engine
        // walks only, never the ROM walk, and the answer says how many games it found that were not listed.
        dev.droidtop.library.settings.LibraryRescan.pcFoldersHandler = { ctx, onStatus ->
            onStatus("Looking at your PC game folders\u2026")
            val started = android.os.SystemClock.elapsedRealtime()
            val library = dev.droidtop.app.LibraryCore.library(ctx)
            val kinds = dev.droidtop.library.LibraryKinds.PC_GAMES
            val before = library.entryIdsOf(kinds)
            kotlinx.coroutines.coroutineScope {
                val progress = launch {
                    dev.droidtop.library.ScanActivity.state.collect { running ->
                        dev.droidtop.library.ScanActivity.describe(running)
                            ?.let { onStatus("$it. Select again to cancel.") }
                    }
                }
                try {
                    library.rescanNow(kinds)
                } finally {
                    progress.cancel()
                }
            }
            val added = (library.entryIdsOf(kinds) - before).size
            val seconds = (android.os.SystemClock.elapsedRealtime() - started + 500) / 1000
            "Rescan of PC game folders finished in $seconds s: " + if (added == 0) "nothing new." else "$added new."
        }
        // Rescans run as a job in the one jobs center (Droidtop/tracker#595), once both walks are registered.
        dev.droidtop.library.LibraryRescanJob.register(appContext)
        dev.droidtop.app.update.UpdateChecks.register(appContext)
        // "These files changed" (docs/SPEC.md 7g, "Targeted indexing"): a finished download, a store install or
        // removal and a plugin's `library.files` report all end here, and the library looks at exactly those paths.
        // Reports left by a process that ended before it indexed them, or made before this ran, are indexed now.
        dev.droidtop.library.settings.LibraryPaths.roots = {
            dev.droidtop.library.GamesRoots.current(appContext).map { it.absolutePath }
        }
        dev.droidtop.library.settings.LibraryPaths.install(appContext) { change ->
            dev.droidtop.app.LibraryCore.library(appContext).indexPaths(
                added = change.added.map { java.io.File(it) },
                removed = change.removed.map { java.io.File(it) },
                changed = change.changed.map { java.io.File(it) },
            )
        }
        // Every kind of update is checked by a job on droidtop's own schedule (Updates > Update settings, Check for
        // updates; Droidtop/tracker#570). The job is not persisted, so each process start makes sure it is there. Off the
        // main thread: it reads the download rules, and moves a retired update setting into them first (#833).
        dev.droidtop.runtime.util.AppScopes.io.launch {
            dev.droidtop.app.update.AppSelfUpdate.migrateUnmeteredOnly(appContext)
            dev.droidtop.app.update.UpdateChecks.schedule(appContext)
        }
        // Its preferences are read off the main thread.
        dev.droidtop.runtime.util.AppScopes.io.launch { dev.droidtop.app.ContainerBackups.schedule(appContext) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
