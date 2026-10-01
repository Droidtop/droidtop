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
// Also the app's Hilt application: the vendored gamenative tree's
// activities are @AndroidEntryPoint and need the object graph rooted
// here. Hilt's bytecode transform works over any base class, so
// extending LauncherApplication is not a conflict. gamenative's own
// PluviaApp process bootstrap is reached through the fork's single
// static init path (PluviaApp.bootstrap) from ModeStartup, instead of
// inheriting an onCreate written for a different app's lifecycle.
@dagger.hilt.android.HiltAndroidApp
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
        // Everything mode-specific this process starts, and nothing else:
        // the mode snapshot was already taken by
        // SettingsCatalogInitProvider (a ContentProvider's onCreate runs
        // before this), and ModeStartup turns it into what actually runs.
        // The vendored gamenative backbone used to be bootstrapped here
        // unconditionally, in every mode; it now starts only for the two
        // modes that use it. See ModeStartup.
        ModeStartup.install(this)
        ScreenOrientationPrefs.install(this)
        // Colour-vision filter and text size, on every activity (SPEC, Accessibility).
        AccessibilityPrefs.install(this)
        // What `host.info` tells a plugin about the mode droidtop is in (docs/plugin-api.md 3 J4).
        dev.droidtop.pluginhost.PluginBrokers.modeProvider = {
            when (val id = dev.droidtop.library.settings.Modes.lastMode(this)) {
                "standard" -> "android"
                else -> id
            }
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
            override fun onActivityPaused(activity: Activity) {
                if (!activity.isParkedSurface()) AudioHandOff.releaseNow("${activity.javaClass.simpleName} paused")
            }
            override fun onActivityResumed(activity: Activity) {
                if (!activity.isParkedSurface()) AudioHandOff.reopen("${activity.javaClass.simpleName} resumed")
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
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
