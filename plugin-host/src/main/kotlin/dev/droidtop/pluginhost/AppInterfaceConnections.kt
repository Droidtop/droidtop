package dev.droidtop.pluginhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * droidtop's bindings to app interface plugins' apps (docs/plugin-api.md 1.3): where the other kinds get a droidtop
 * process from [PluginProcesses], an app's plugin is its app's own service, bound by its explicit component. A binding
 * is shared by the runners that use it and let go a while after the last one releases it, so the app is not kept
 * running for nothing. The broker droidtop hands the app does not depend on the binding: the app keeps it.
 */
internal object AppInterfaceConnections {
    /** How long a binding nobody uses is kept before droidtop lets the app go. */
    private const val IDLE_MS = 2 * 60_000L

    private class Bound(val connection: ServiceConnection, val ready: CompletableDeferred<IPluginRuntime?>) {
        @Volatile var runtime: IPluginRuntime? = null
        var users = 0
    }

    private val bound = ConcurrentHashMap<String, Bound>()
    private val main = Handler(Looper.getMainLooper())

    /** Whether droidtop holds a binding for [pluginId] here, so the runner releases it here and not in [PluginProcesses]. */
    fun holds(pluginId: String): Boolean = bound.containsKey(pluginId)

    /** [record]'s app's runtime, bound if it is not yet; null when the app's service cannot be reached in time. */
    suspend fun acquire(context: Context, record: PluginRecord): IPluginRuntime? {
        val id = record.manifest.id
        val target = record.manifest.app ?: return null
        val app = context.applicationContext ?: context
        val entry = synchronized(this) {
            bound[id]?.takeIf { it.runtime?.asBinder()?.isBinderAlive != false }?.also { it.users++ } ?: run {
                bound.remove(id)?.let { old -> runCatching { app.unbindService(old.connection) } }
                val ready = CompletableDeferred<IPluginRuntime?>()
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val runtime = service?.let { IPluginRuntime.Stub.asInterface(it) }
                        bound[id]?.runtime = runtime
                        ready.complete(runtime)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        bound[id]?.runtime = null
                    }

                    override fun onBindingDied(name: ComponentName?) = forget(app, id)

                    override fun onNullBinding(name: ComponentName?) {
                        ready.complete(null)
                        forget(app, id)
                    }
                }
                val created = Bound(connection, ready).also { it.users = 1 }
                bound[id] = created
                val intent = Intent(AppInterfaceTrust.ACTION).setClassName(target.packageName, target.service)
                val ok = runCatching { app.bindService(intent, connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
                if (!ok) {
                    bound.remove(id)
                    runCatching { app.unbindService(connection) }
                    return null
                }
                created
            }
        }
        return entry.runtime ?: withTimeoutOrNull(PluginRunner.CALL_TIMEOUT_MS) { entry.ready.await() }
    }

    /** One user of [pluginId]'s binding is done; the binding goes [IDLE_MS] after the last. */
    fun release(context: Context, pluginId: String) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            val entry = bound[pluginId] ?: return
            entry.users = (entry.users - 1).coerceAtLeast(0)
            if (entry.users > 0) return
        }
        main.postDelayed({
            synchronized(this) {
                val entry = bound[pluginId] ?: return@postDelayed
                if (entry.users == 0) forget(app, pluginId)
            }
        }, IDLE_MS)
    }

    /** Lets [pluginId]'s app go now: its plugin was turned off, denied or uninstalled, or its binding died. */
    fun forget(context: Context, pluginId: String) {
        val entry = bound.remove(pluginId) ?: return
        entry.ready.complete(null)
        runCatching { (context.applicationContext ?: context).unbindService(entry.connection) }
    }
}
