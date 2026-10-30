package dev.droidtop.app

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.android.launcher3.LauncherApplication
import kotlinx.coroutines.launch
import dev.droidtop.runtime.AudioHandOff

/**
 * Real fix for a real, confirmed-on-device bug: theme decorative art
 * (DEcaffe's own carousel outline/fade images, `carborout.svg`/
 * `carborin.svg`) rendered as broken/garbled shapes instead of the real
 * artwork. `coil-svg` was already a dependency (`shell-gamepad/build.
 * gradle.kts`, added specifically for this), but Coil3 doesn't
 * auto-discover decoder artifacts the way Coil2 did with its
 * ContentProvider-based `ImageLoaderFactory` — a decoder has to be
 * explicitly added to a real [ImageLoader]'s component registry, which
 * nothing in the app ever did. Every `AsyncImage` call anywhere in the
 * app uses Coil3's default [SingletonImageLoader] unless one is supplied
 * explicitly, so this is the one real place to fix it app-wide rather
 * than threading an `imageLoader` param through every themed image call
 * site.
 *
 * Extends [LauncherApplication] (`shell-default`'s own forked-in Murine
 * Launcher `Application` subclass, declared as `android:name` in that
 * module's own manifest) rather than plain `android.app.Application` --
 * it does real, load-bearing init (Bugsink crash reporting, backup
 * restore, night-mode sync, first-run onboarding gate) that must keep
 * running when `shell-default`'s Standard shell is active. The manifest
 * merger needs `android:name` added to `:app`'s own `tools:replace` list
 * (see AndroidManifest.xml) so this subclass wins over the plain
 * `LauncherApplication` declaration merged in from that module.
 */
class DroidtopApplication : LauncherApplication(), SingletonImageLoader.Factory {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // Catalog words are string resources read through Words (Droidtop/tracker#177); the content providers that
        // register the catalogs start before onCreate, so it gets the Context here, first.
        dev.droidtop.library.settings.Words.init(base)
    }

    override fun onCreate() {
        super.onCreate()
        // A contained plugin's isolated process (docs/plugin-api.md 5.3) starts nothing of droidtop's: it has no files,
        // no preferences, no network and no permissions, and the plugin in it reaches droidtop only through its broker.
        // Nor does a gpu.render plugin's graphics process, which has droidtop's UID: nothing of droidtop's is kept in
        // memory beside the plugin's code there.
        // The download engine's process (docs/SPEC.md 12a "One download engine") is the same: libaria2 reads untrusted
        // network data there, so droidtop starts nothing beside it, and in the default `:aria2_open` process the UID is droidtop's.
        if (dev.droidtop.runtime.util.IsolatedProcess.runsPluginCode() || dev.droidtop.runtime.util.IsolatedProcess.runsDownloadEngine()) return
        // A full-access plugin's process (`:pluginhost*`) runs none of droidtop's start (Droidtop/tracker#775): droidtop
        // starts in its own process, then starts the plugin host, which starts the plugins. Run here, droidtop's start
        // would restore backups, start modes and start plugins of its own from a plugin's process. It gets only what the
        // plugin's code there uses: the network platform, the shared Shizuku binder, and the look of its own screen.
        if (dev.droidtop.runtime.util.IsolatedProcess.runsFullAccessPlugin()) {
            DebugStrictMode.install(this)
            dev.droidtop.runtime.windows.OkHttpPlatform.install(this)
            dev.droidtop.pluginhost.ElevatedAccessHost.install(this)
            AccessibilityPrefs.install(this)
            dev.droidtop.ui.shared.MotionWatch.install(this)
            return
        }
        // Main-thread file, database and network work is logged in debuggable builds, from here on (Droidtop/tracker#729).
        DebugStrictMode.install(this)
        // The console table, for the shell's input pipeline, which does not depend on :library-core (Droidtop/tracker#827 F1a).
        dev.droidtop.library.controller.ConsoleLookup.forThisDevice = dev.droidtop.library.controller.HardwareDatabase::forThisDevice
        // The feature steps, before onboarding or What's new reads them (docs/SPEC.md 7b, "Update onboarding").
        WhatsNew.registerSteps()
        // Every entry to Updates says how many are waiting (Droidtop/tracker#792): the page lists them, off the main thread.
        dev.droidtop.library.settings.UpdatesWaiting.refresh = { context ->
            val app = context.applicationContext
            dev.droidtop.runtime.util.AppScopes.io.launch { runCatching { dev.droidtop.app.settings.UpdatesPage.publishCount(app) } }
        }
        // A restore staged by "Restore droidtop" goes into place before anything opens a database (Droidtop/tracker#625).
        dev.droidtop.app.settings.DroidtopBackup.finishPendingRestore(this)
        // The scan log goes to logcat AND to a file droidtop owns, and
        // says which build is writing it -- shared core, every mode, so
        // a scan is diagnosable from a rig whatever the device's logcat
        // buffer did with the lines (see ScanLog).
        dev.droidtop.library.ScanLog.install(this)
        // Kid's maximum volume holds against the volume keys too (one prefs read, then a settings observer).
        dev.droidtop.runtime.systemstatus.VolumeControl.watchKidCap(this)
        // Kid's play-time limits: counts play while a game runs and closes it at the limit (SPEC 7f, tracker#421).
        PlayTimeLimiter.install(this)
        // Game modes droidtop changed and did not put back before it stopped (docs/SPEC.md "Power policy", the journal,
        // Droidtop/tracker#841): put back first, once the elevated shell has had its moment to connect.
        val powerApp = this
        dev.droidtop.runtime.util.AppScopes.io.launch {
            if (dev.droidtop.runtime.power.PowerJournal.entries(powerApp).isEmpty()) return@launch
            val shell = dev.droidtop.runtime.tasks.TaskManager.shell
            runCatching { shell.connect(POWER_RESTORE_WAIT_MS) }
            runCatching { dev.droidtop.runtime.power.StandardPowerLayer.restoreAll(powerApp, shell) }
        }
        // Crash notes and crash-loop safe mode (SPEC 10c): after ScanLog so
        // a note can carry its tail, before anything that could crash.
        dev.droidtop.library.diagnostics.CrashRecovery.install(this)
        // OkHttp's androidx.startup initializer never runs (shell-default's
        // manifest removes the startup provider), so its public suffix list
        // is unreadable until it gets the context here (SPEC 10b).
        dev.droidtop.runtime.windows.OkHttpPlatform.install(this)
        // What passing a site's browser check earned is kept once for every request droidtop makes (SPEC 12a,
        // Droidtop/tracker#751); the file is read on first use, not here.
        val clearanceFile = java.io.File(filesDir, "site-clearance.txt")
        dev.droidtop.net.sitecheck.SiteClearances.install {
            dev.droidtop.net.sitecheck.SiteClearanceStore(dev.droidtop.net.sitecheck.SiteClearanceStore.FileStorage(clearanceFile))
        }
        // Everything mode-specific this process starts, and nothing else:
        // the mode snapshot was already taken by
        // SettingsCatalogInitProvider (a ContentProvider's onCreate runs
        // before this), and ModeStartup turns it into what actually runs.
        // The Windows runtime starts only for the modes that use it. See ModeStartup.
        ModeStartup.install(this)
        // PC game folders get their ids from droidtop's own storage, never from a
        // .gamenative file written into the user's folder (SPEC 7g, tracker#269).
        dev.droidtop.runtime.windows.DroidtopGameIdStore.install(this)
        ScreenOrientationPrefs.install(this)
        // A second-screen surface that becomes the top activity hands the pad back to the
        // shell instead of leaving keys without a window (SPEC 4c, console build 1386 ANR).
        ForegroundShell.installPadReturn(this)
        // Every seam droidtop's modules call back through, installed in one place before anything can ask one (Seams).
        Seams.install(this)
        // Colour-vision filter and text size, on every activity (SPEC, Accessibility).
        AccessibilityPrefs.install(this)
        // Android's Remove animations reaches every surface's motion, not only Gaming's (Droidtop/tracker#643).
        dev.droidtop.ui.shared.MotionWatch.install(this)
        // Which release line each catalog and plugin is offered on (SPEC 12a "Channels", Droidtop/tracker#179).
        dev.droidtop.library.integrations.PluginChannels.install(this)
        // The plugin host starts after droidtop, by droidtop (Droidtop/tracker#775): PluginHostStart, once droidtop's
        // first screen has drawn, or a little after a start with no screen. Nothing above or below needs it.
        PluginHostStart.armAfterStart(this)
        // What Enginehost can run decides a game's runner: its list is asked at start (docs/plugin-api.md 3 A7b).
        dev.droidtop.library.integrations.EngineCoreList.refreshAll(this)
        // The task manager asks one privileged shell to force-stop an app and to read the system's task list:
        // the Shizuku app (or Sui) or the Shizuku plugin, whichever the user picked; with none, it says what to
        // enable (docs/SPEC.md "The task manager"). Runs in every process: the binder is shared across them.
        dev.droidtop.pluginhost.ElevatedAccessHost.install(this)
        // Open on a finished job and on a line of its report (docs/SPEC.md "Jobs and their results").
        JobOpener.install()
        // Typing on the add-on display (SPEC 4c, tracker#314): with elevated access Android's own keyboard is set to
        // show on each second display; without it droidtop draws its own keyboard over an app there. Main process only.
        AddonKeyboardHost.install(this)
        // The in-game performance overlay (SPEC "Performance overlay", tracker#83): drawn over the game while its level
        // (Quick Menu > Performance) is not Off. Main process only.
        PerformanceOverlayHost.install(this)
        // The launch watchdog's alert above the game on its own display (SPEC "The launch watchdog"): the shell's
        // dialog is behind the game. Main process only.
        LaunchAlertOverlay.install(this)
        // The in-game overlays (SPEC "In-game overlays: HOME chords", tracker#740): the Quick Menu over the app in front on
        // HOME + R2, without backgrounding it. Main process only.
        InGameOverlays.install(this)
        // A screen connected for the first time is asked about once (docs/SPEC.md 4c, "What each screen is for",
        // Droidtop/tracker#776). Main process only.
        ScreenRoleAsk.install(this)
        // The download policy (docs/SPEC.md "Download rules", tracker#226): every download job is held or let run by
        // the person's rules, and games set to keep themselves up to date are checked a little after start.
        // Main process only: the jobs registry lives there.
        if (Build.VERSION.SDK_INT < 28 || Application.getProcessName() == packageName) {
            // Apps that carry a droidtop plugin (Droidtop/tracker#547): told to the person once per app version when the app
            // announces itself at the door; the plugin host's start (PluginHostStart) watches installs and catches up.
            dev.droidtop.pluginhost.AppInterfaceAnnouncements.notify = { context, app -> AppInterfaceNotification.show(context, app) }
            dev.droidtop.pluginhost.DownloadGate.install(this)
            dev.droidtop.library.stores.StoreAutoUpdates.requestLater(this)
            // PC and engine games get their details by themselves (docs/SPEC.md 7h, Droidtop/tracker#568).
            dev.droidtop.library.scraper.PcAutoScrape.requestLater(this)
        }
        // Shared core too: a games folder added in onboarding or Settings
        // is walked at once, not when Gaming first opens (SPEC 2c).
        LibraryCore.followGamesRoots(this)
        // Launch audio hand-off (SPEC "Launch audio hand-off", tracker#160):
        // whatever brings another app in front pauses a droidtop activity
        // first, and the next app is not resumed until onPause returns, so
        // every output stream of droidtop's own is closed by then. Coming
        // back to any droidtop activity opens them again. The surfaces
        // droidtop parks on the other screen (the companion, the cover a
        // launch places on a display it vacates) come and go around a
        // launch without the user leaving or returning, so they count
        // for neither.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun Activity.isParkedSurface() =
                this is CompanionActivity || this is dev.droidtop.display.SecondaryDisplayActivity
            // The launch audio timeline (tracker#160): every droidtop activity transition, with the screen it is on.
            private fun Activity.note(what: String) =
                AudioHandOff.mark("${javaClass.simpleName} $what (display ${window?.decorView?.display?.displayId})")
            override fun onActivityPaused(activity: Activity) {
                activity.note("paused")
                WhatsNew.onScreenPaused(activity)
                if (!activity.isParkedSurface()) AudioHandOff.releaseNow("${activity.javaClass.simpleName} paused")
            }
            override fun onActivityResumed(activity: Activity) {
                activity.note("resumed")
                // Elevated access may have been granted while droidtop was away; a pass with nothing new runs no command.
                if (!activity.isParkedSurface()) AddonKeyboardHost.resync(activity, force = false)
                // Every droidtop text field on a screen Android draws no keyboard on gets droidtop's (SPEC 4c).
                dev.droidtop.shell.gamepad.InWindowKeyboard.attach(activity)
                if (!activity.isParkedSurface()) AudioHandOff.reopen("${activity.javaClass.simpleName} resumed")
                // The first of droidtop's own screens in front after an update opens What's new (SPEC 7b, "Update onboarding").
                if (activity is MainActivity || activity is com.android.launcher3.Launcher || activity is CatalogScreenActivity) {
                    WhatsNew.onShellResumed(activity)
                }
                // Another of droidtop's screens over the shell is not a game: coming back from it is not a return (ShellReturn).
                if (!activity.isParkedSurface() && activity !is MainActivity && activity !is dev.droidtop.runtime.windows.WineGameActivity) {
                    ShellReturn.otherScreenResumed()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = activity.note("started")
            override fun onActivityStopped(activity: Activity) = activity.note("stopped")
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
}

/** How long the start-up restore of the power journal waits for the elevated shell's binder. */
private const val POWER_RESTORE_WAIT_MS = 5_000L
