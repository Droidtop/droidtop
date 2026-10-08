package dev.droidtop.app

import android.app.Activity
import android.os.Bundle
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.android.launcher3.LauncherApplication
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
    override fun onCreate() {
        super.onCreate()
        // The scan log goes to logcat AND to a file droidtop owns, and
        // says which build is writing it -- shared core, every mode, so
        // a scan is diagnosable from a rig whatever the device's logcat
        // buffer did with the lines (see ScanLog).
        dev.droidtop.library.ScanLog.install(this)
        // Crash notes and crash-loop safe mode (SPEC 10c): after ScanLog so
        // a note can carry its tail, before anything that could crash.
        dev.droidtop.library.diagnostics.CrashRecovery.install(this)
        // OkHttp's androidx.startup initializer never runs (shell-default's
        // manifest removes the startup provider), so its public suffix list
        // is unreadable until it gets the context here (SPEC 10b).
        dev.droidtop.runtime.windows.OkHttpPlatform.install(this)
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
        // Colour-vision filter and text size, on every activity (SPEC, Accessibility).
        AccessibilityPrefs.install(this)
        // What `host.info` tells a plugin about the mode droidtop is in (docs/plugin-api.md 3 J4).
        // One spelling for a mode wherever a plugin meets it (docs/plugin-api.md 1.9): `standard`, `gaming`, `desktop`.
        dev.droidtop.pluginhost.PluginBrokers.modeProvider = { dev.droidtop.library.settings.Modes.lastMode(this) }
        // Standard is always there (the Home button's screen); Gaming and Desktop when the person has them on.
        dev.droidtop.pluginhost.PluginBrokers.modesProvider = {
            val on = dev.droidtop.library.settings.Modes.enabled.map { it.id }.toSet() + dev.droidtop.pluginhost.PluginModes.STANDARD
            dev.droidtop.pluginhost.PluginModes.ALL.filter { it in on }
        }
        // Plugins in every mode (docs/plugin-api.md 1.9): "Plugins" on Standard's home-screen menu, and the plugin
        // services and schedules the person switched on (E8, E9). Both read manifests off the main thread.
        dev.droidtop.library.integrations.PluginStandardHooks.install(this)
        PluginBackgroundHost.install(this)
        // `library.read` `systems` (docs/plugin-api.md 3 A1): the broker runs on a binder thread, which may block here;
        // the answer comes from the library's in-memory index, never a folder walk.
        dev.droidtop.pluginhost.PluginBrokers.librarySystemsProvider = {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                dev.droidtop.library.integrations.PluginLibraryRead.snapshot(this@DroidtopApplication, LibraryCore.library(this@DroidtopApplication))
            }
        }
        // `social.changed` (docs/plugin-api.md 3 C19): a social provider plugin says something changed, and the
        // social hub asks it again off the binder thread (SPEC "Social").
        dev.droidtop.pluginhost.PluginBrokers.socialChanged = { pluginId, change ->
            dev.droidtop.library.integrations.PluginSocialProviders.changed(this, pluginId, change)
        }
        // The task manager asks one privileged shell to force-stop an app and to read the system's task list:
        // the Shizuku app (or Sui) or the Shizuku plugin, whichever the user picked; with none, it says what to
        // enable (docs/SPEC.md "The task manager"). Runs in every process: the binder is shared across them.
        dev.droidtop.pluginhost.ElevatedAccessHost.install(this)
        // Typing on the add-on display (SPEC 4c, tracker#314): with elevated access Android's own keyboard is set to
        // show on each second display; without it droidtop draws its own keyboard over an app there. Main process only.
        AddonKeyboardHost.install(this)
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
                if (!activity.isParkedSurface()) AudioHandOff.releaseNow("${activity.javaClass.simpleName} paused")
            }
            override fun onActivityResumed(activity: Activity) {
                activity.note("resumed")
                // Elevated access may have been granted while droidtop was away; a pass with nothing new runs no command.
                if (!activity.isParkedSurface()) AddonKeyboardHost.resync(activity, force = false)
                // Every droidtop text field on a screen Android draws no keyboard on gets droidtop's (SPEC 4c).
                dev.droidtop.shell.gamepad.InWindowKeyboard.attach(activity)
                if (!activity.isParkedSurface()) AudioHandOff.reopen("${activity.javaClass.simpleName} resumed")
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
