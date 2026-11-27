package dev.droidtop.pluginhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    class Lease internal constructor(val runtime: IPluginRuntime, private val release: () -> Unit) : AutoCloseable {
        private val open = AtomicBoolean(true)
        override fun close() { if (open.compareAndSet(true, false)) release() }
    }

    /** Retains the exact runtime generation that owns an accepted response, not a later replacement under its id. */
    fun retainLease(context: Context, pluginId: String, runtime: IBinder): Lease? = synchronized(this) {
        val entry = bound[pluginId]?.takeIf { it.runtime?.asBinder() === runtime && runtime.isBinderAlive } ?: return null
        entry.users++
        Lease(entry.runtime!!) { releaseEntry(context, pluginId, entry) }
    }

    /** Each caller owns a lease to this exact service connection, even if the app later reconnects. */
    suspend fun acquireLease(context: Context, record: PluginRecord): Lease? = acquireHeld(context, record)?.let { (entry, runtime) ->
        Lease(runtime) { releaseEntry(context, record.manifest.id, entry) }
    }

    private suspend fun acquireHeld(context: Context, record: PluginRecord): Pair<Bound, IPluginRuntime>? {
        val id = record.manifest.id
        val target = record.manifest.app ?: return null
        val app = context.applicationContext ?: context
        val entry = synchronized(this) {
            bound[id]?.takeIf { appBindingReusable(it.runtime?.asBinder()?.isBinderAlive, it.ready.isCompleted) }?.also { it.users++ } ?: run {
                bound.remove(id)?.let { old -> runCatching { app.unbindService(old.connection) } }
                val ready = CompletableDeferred<IPluginRuntime?>()
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val runtime = service?.let { IPluginRuntime.Stub.asInterface(it) }
                        val current = synchronized(AppInterfaceConnections) {
                            bound[id]?.takeIf { it.connection === this }?.also { it.runtime = runtime }
                        }
                        ready.complete(runtime.takeIf { current != null })
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        synchronized(AppInterfaceConnections) { bound[id]?.takeIf { it.connection === this }?.runtime = null }
                    }

                    override fun onBindingDied(name: ComponentName?) = forgetConnection(app, id, this)

                    override fun onNullBinding(name: ComponentName?) {
                        ready.complete(null)
                        forgetConnection(app, id, this)
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
        return try {
            val runtime = entry.runtime ?: withTimeoutOrNull(PluginRunner.CALL_TIMEOUT_MS) { entry.ready.await() }
            if (runtime == null || bound[id] !== entry) { releaseEntry(app, id, entry); null } else entry to runtime
        } catch (error: Throwable) { releaseEntry(app, id, entry); throw error }
    }

    private fun releaseEntry(context: Context, pluginId: String, entry: Bound) {
        val app = context.applicationContext ?: context
        synchronized(this) {
            if (bound[pluginId] !== entry) return
            entry.users = (entry.users - 1).coerceAtLeast(0)
            if (entry.users > 0) return
        }
        main.postDelayed({
            synchronized(this) {
                if (bound[pluginId] === entry && entry.users == 0) forgetConnection(app, pluginId, entry.connection)
            }
        }, IDLE_MS)
    }

    /** Lets [pluginId]'s app go now: its plugin was turned off, denied or uninstalled, or its binding died. */
    fun forget(context: Context, pluginId: String) {
        val entry = bound.remove(pluginId) ?: return
        dispose(context, entry)
    }

    private fun forgetConnection(context: Context, pluginId: String, expected: ServiceConnection) {
        val entry = synchronized(this) {
            bound[pluginId]?.takeIf { it.connection === expected }?.also { bound.remove(pluginId, it) }
        } ?: return
        dispose(context, entry)
    }

    private fun dispose(context: Context, entry: Bound) {
        entry.ready.complete(null)
        runCatching { (context.applicationContext ?: context).unbindService(entry.connection) }
    }
}

/**
 * Whether a binding can be used again: its runtime is alive, or it is still connecting for the first time. One whose
 * app went away (disconnected, runtime null) after it had connected is not: its first answer is spent, so reusing it
 * waited on nothing or handed back the dead runtime. Before, `isBinderAlive != false` let a null runtime through.
 */
internal fun appBindingReusable(runtimeAlive: Boolean?, firstAnswerSpent: Boolean): Boolean =
    runtimeAlive == true || (runtimeAlive == null && !firstAnswerSpent)
