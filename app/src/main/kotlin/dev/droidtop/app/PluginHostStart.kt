package dev.droidtop.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The plugin host's start, after droidtop's and by droidtop (docs/SPEC.md 12a "Start order", Droidtop/tracker#775). The
 * owner: "droidtop initializes. Then, it initializes the plugin host. which initializes the plugins." droidtop's own start
 * ([DroidtopApplication.onCreate]) needs nothing here and starts none of it: no plugin, no plugin process and no plugin
 * file is touched on the way to droidtop's first screen, so a plugin that is missing, slow or broken never stops it.
 *
 * [armAfterStart] runs [start] once droidtop's first screen has drawn, or [NO_SCREEN_DELAY_MS] after a start with no
 * screen (a job or a service started droidtop). [start] then brings up, each on its own so one failure stays its own,
 * what start-up used to: plugins an older build disabled tried again, the person's background services and schedules,
 * Standard's plugin entries, the apps that carry a plugin, and the elevated grants (a backend may be the Shizuku plugin).
 */
object PluginHostStart {
    private const val TAG = "droidtop.pluginhost"

    /** How long a start with no screen waits before the host starts: droidtop's own start is done by then. */
    const val NO_SCREEN_DELAY_MS = 10_000L

    private val started = AtomicBoolean(false)

    /** From [DroidtopApplication.onCreate], in droidtop's main process only. Sets up when [start] runs; starts nothing. */
    fun armAfterStart(application: Application) {
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != application.packageName) return
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                application.unregisterActivityLifecycleCallbacks(this)
                // Posted behind the first frame's traversal, so the screen draws before the host starts.
                activity.window?.decorView?.post { start(application) } ?: start(application)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        Handler(Looper.getMainLooper()).postDelayed({ start(application) }, NO_SCREEN_DELAY_MS)
    }

    /** Starts the plugin host, once per process, off the main thread. Safe to call from anywhere droidtop needs it up. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        kotlin.concurrent.thread(name = "plugin-host-start", isDaemon = true) {
            step("retry") {
                // A plugin an older droidtop disabled after a failure is tried again by this build.
                dev.droidtop.pluginhost.PluginStore.retryAfterHostUpdate(app)
                // One disabled by a check that ran mid-update, before installs were atomic, comes back once it verifies.
                dev.droidtop.pluginhost.PluginStore.recoverVerified(app)
            }
            // Plugins in every mode (docs/plugin-api.md 1.9): "Plugins" on Standard's home-screen menu.
            step("standard hooks") { dev.droidtop.library.integrations.PluginStandardHooks.install(app) }
            // The plugin services and schedules the person switched on (E8, E9).
            step("background") { PluginBackgroundHost.install(app) }
            // Apps that carry a droidtop plugin (Droidtop/tracker#547): told to the person once per app version when the
            // app is installed or updated while droidtop runs, or when it changed while droidtop did not run.
            step("app interfaces") {
                dev.droidtop.pluginhost.AppInterfaceAnnouncements.watch(app)
                dev.droidtop.pluginhost.AppInterfaceAnnouncements.catchUp(app)
            }
            // With Shizuku, the grants the system controls need are taken now, so they switch in place rather than
            // opening Android's screens (docs/SPEC.md "Privileged actions", tracker#581). Here, not in droidtop's start:
            // the backend may be the Shizuku plugin.
            step("elevated grants") { dev.droidtop.runtime.tasks.ElevatedGrants.takeAll(app, dev.droidtop.runtime.tasks.TaskManager.shell) }
        }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "plugin host start: $name failed; the rest go on", t)
        }
    }
}
