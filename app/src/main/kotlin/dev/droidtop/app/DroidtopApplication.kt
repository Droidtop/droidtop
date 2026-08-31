package dev.droidtop.app

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.android.launcher3.LauncherApplication

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
// PluviaApp's process bootstrap runs from onCreate below through the
// fork's own single static init path (PluviaApp.bootstrap), instead of
// inheriting an onCreate written for a different app's lifecycle.
@dagger.hilt.android.HiltAndroidApp
class DroidtopApplication : LauncherApplication(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // The vendored gamenative backbone's own init (prefs, download
        // service, Steam service prereqs, container-file preload) --
        // the fork's single bootstrap path, invoked from droidtop's
        // Application. Crash handling stays droidtop's own, hence the
        // explicit false.
        app.gamenative.PluviaApp.bootstrap(this, installCrashHandler = false)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
}
