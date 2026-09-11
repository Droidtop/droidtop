package dev.droidtop.runtime.windows

import android.app.Application
import android.content.Context
import app.gamenative.PluviaApp

/**
 * The vendored gamenative backbone's process bootstrap, as something the
 * rest of droidtop can name. The vendored tree is compiled INTO this
 * module (`runtime-windows/build.gradle.kts` srcDirs), so `app.gamenative`
 * is this module's own compile classpath and nobody else's: `:app` depends
 * on `:runtime-windows`, not on gamenative, and mode gating in
 * `dev.droidtop.app.ModeStartup` reaches the bootstrap through here rather
 * than through a class it cannot see.
 *
 * What the bootstrap does: preferences, the download service, Steam
 * prerequisites, the container migration and the container-file preload,
 * telemetry setup, and a native library preload. It is the heaviest thing
 * droidtop starts and it reaches the network, so only the modes that use
 * it (Gaming's PC surface, Desktop's containers and Wine) and the PC
 * launch path itself call this.
 *
 * Idempotent by gamenative's own design -- `PluviaApp.bootstrap` returns
 * immediately once it has run -- which is what makes calling it from
 * several places honest rather than a race.
 */
object WindowsBackbone {

    /**
     * Starts the backbone if it is not already up. Crash handling stays
     * droidtop's own (Bugsink, installed by `LauncherApplication`), hence
     * the explicit `installCrashHandler = false`.
     */
    @JvmStatic
    fun ensureStarted(context: Context) {
        val app = context.applicationContext as? Application ?: return
        PluviaApp.bootstrap(app, installCrashHandler = false)
    }
}
