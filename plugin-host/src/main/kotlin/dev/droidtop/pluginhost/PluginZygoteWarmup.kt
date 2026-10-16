package dev.droidtop.pluginhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Starts droidtop's app zygote before a plugin needs it (docs/plugin-api.md 5.3, "The rigs"): on the BlueStacks rig the
 * zygote's first library load waits 10 s, and a search that started it would run out of time for every source. droidtop
 * binds one empty sandbox process, which makes Android start the zygote and run its preload, when the one search opens
 * and once droidtop's start has settled. It is dropped as soon as a plugin's own process from the zygote is running
 * (which keeps the zygote up) or [HOLD_MS] after the last request, whichever comes first. Android 10 (API 29) and
 * later only; below that there is no app zygote and nothing to warm.
 */
object PluginZygoteWarmup {
    /** How long the empty process is kept for one request when no plugin process takes over. */
    const val HOLD_MS = 120_000L

    private const val INSTANCE = "warm"
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var connection: ServiceConnection? = null
    private var app: Context? = null
    private var deadline = 0L

    private val expire = Runnable { drop("held for ${HOLD_MS / 1000} s") }

    /** Starts the zygote, or keeps it warm [HOLD_MS] longer. Cheap and safe to call from any thread, as often as wanted. */
    fun warm(context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val appContext = context.applicationContext
        synchronized(lock) {
            deadline = SystemClock.uptimeMillis() + HOLD_MS
            main.removeCallbacks(expire)
            main.postAtTime(expire, deadline)
            if (connection != null) return
            if (PluginProcesses.containedRunning()) return
            val started = SystemClock.uptimeMillis()
            val bound = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    Log.i("droidtop.plugin", "app zygote warm: an empty sandbox process started in ${SystemClock.uptimeMillis() - started} ms")
                }

                override fun onServiceDisconnected(name: ComponentName?) = Unit

                override fun onBindingDied(name: ComponentName?) = drop("its process died")
            }
            val ok = runCatching {
                appContext.bindIsolatedService(Intent(appContext, PluginSandboxService::class.java), Context.BIND_AUTO_CREATE, INSTANCE, appContext.mainExecutor, bound)
            }.getOrDefault(false)
            if (ok) {
                connection = bound
                app = appContext
            } else {
                runCatching { appContext.unbindService(bound) }
            }
        }
    }

    /** A plugin's own contained process is running: it keeps the zygote up, so the empty one goes. */
    internal fun pluginProcessStarted() {
        if (connection != null) main.post { drop("a plugin's own process took over") }
    }

    private fun drop(why: String) {
        val (gone, context) = synchronized(lock) {
            val pair = connection to app
            connection = null
            app = null
            main.removeCallbacks(expire)
            pair
        }
        if (gone == null || context == null) return
        runCatching { context.unbindService(gone) }
        Log.i("droidtop.plugin", "app zygote warm: the empty sandbox process is dropped ($why)")
    }
}
