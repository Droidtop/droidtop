package dev.droidtop.pluginhost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The plugin processes :app is connected to (docs/plugin-api.md 5.3 and 8): one process per plugin.
 *
 * - **Contained** plugins get an isolated process each. From API 29 that is one `bindIsolatedService` instance of
 *   [PluginSandboxService] per plugin, as many as are in use. Below it, Android has no per-instance isolated process, so
 *   eight isolated services are declared ([PluginSandboxSlot0] to [PluginSandboxSlot7]) and each holds one plugin; a
 *   ninth contained plugin in use at once waits for a slot to fall idle rather than share one.
 * - **Full-trust** plugins get one of eight declared slots under droidtop's UID ([PluginRuntimeService], `:pluginhost`,
 *   and [FullTrustSlot1] to [FullTrustSlot7]). With more than eight in use at once the least recently used slot is
 *   shared; they share droidtop's UID anyway (docs/plugin-api.md 5.2, T8).
 *
 * A process is bound while a runner uses it and for [IDLE_MS] after the last one lets go (the idle unload of
 * docs/plugin-api.md 1.5). Its death is reported once, here, to [PluginCrashPolicy.processDied], with the plugins it held.
 */
internal object PluginProcesses {
    const val IDLE_MS = 60_000L

    private val FULL_TRUST_SLOTS: List<Class<out PluginProcessService>> = listOf(
        PluginRuntimeService::class.java, FullTrustSlot1::class.java, FullTrustSlot2::class.java, FullTrustSlot3::class.java,
        FullTrustSlot4::class.java, FullTrustSlot5::class.java, FullTrustSlot6::class.java, FullTrustSlot7::class.java,
    )
    private val SANDBOX_SLOTS: List<Class<out PluginProcessService>> = listOf(
        PluginSandboxSlot0::class.java, PluginSandboxSlot1::class.java, PluginSandboxSlot2::class.java, PluginSandboxSlot3::class.java,
        PluginSandboxSlot4::class.java, PluginSandboxSlot5::class.java, PluginSandboxSlot6::class.java, PluginSandboxSlot7::class.java,
    )

    private class Process(val key: String, val tier: PluginTier, val service: Class<out PluginProcessService>, val instance: String?) {
        var runtime: IPluginRuntime? = null
        var connection: ServiceConnection? = null
        var connecting: CompletableDeferred<IPluginRuntime?>? = null
        val plugins = LinkedHashSet<String>()
        var users = 0
        var lastUsedMs = 0L
        var idle: Runnable? = null
    }

    private val lock = Any()
    private val processes = HashMap<String, Process>()
    private val byPlugin = HashMap<String, Process>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The runtime of [pluginId]'s process for [tier], bound and started when needed, counted as one user until
     * [release]. Null when the process could not be started (or, below API 29, every contained slot is busy).
     */
    suspend fun acquire(context: Context, pluginId: String, tier: PluginTier): IPluginRuntime? {
        val app = context.applicationContext
        val (process, wait) = synchronized(lock) {
            val process = processFor(pluginId, tier) ?: return null
            process.users++
            process.lastUsedMs = System.currentTimeMillis()
            process.idle?.let { main.removeCallbacks(it) }
            process.idle = null
            process.runtime?.let { return it }
            val wait = process.connecting ?: CompletableDeferred<IPluginRuntime?>().also { deferred ->
                process.connecting = deferred
                if (!bindLocked(app, process)) {
                    process.connecting = null
                    deferred.complete(null)
                }
            }
            process to wait
        }
        val runtime = withTimeoutOrNull(PluginRunner.CALL_TIMEOUT_MS) { wait.await() }
        if (runtime == null) {
            Log.w("droidtop.plugin", "the process for $pluginId (${process.key}) did not start")
            release(app, pluginId)
        }
        return runtime
    }

    /** One user of [pluginId]'s process is done with it; the last one starts the idle countdown. */
    fun release(context: Context, pluginId: String) {
        val app = context.applicationContext
        synchronized(lock) {
            val process = byPlugin[pluginId] ?: return
            if (process.users > 0) process.users--
            if (process.users == 0 && process.idle == null) {
                val countdown = Runnable { letGo(app, process) }
                process.idle = countdown
                main.postDelayed(countdown, IDLE_MS)
            }
        }
    }

    /** Which plugin processes exist now and what each holds, for the containment check. */
    fun describe(pluginId: String): String? = synchronized(lock) {
        byPlugin[pluginId]?.let { "${it.key} (${if (it.tier == PluginTier.CONTAINED) "isolated" else "droidtop's UID"})" }
    }

    private fun processFor(pluginId: String, tier: PluginTier): Process? {
        byPlugin[pluginId]?.let { current ->
            if (current.tier == tier) return current
            // Full access was allowed or taken away: the plugin leaves the process of its old tier.
            detach(pluginId, current)
        }
        val process = if (tier == PluginTier.CONTAINED && Build.VERSION.SDK_INT >= 29) {
            val key = "contained:$pluginId"
            processes.getOrPut(key) { Process(key, tier, PluginSandboxService::class.java, instanceName(pluginId)) }
        } else {
            pooled(tier) ?: return null
        }
        process.plugins += pluginId
        byPlugin[pluginId] = process
        return process
    }

    /** A free slot, else the least recently used idle one (its plugins leave it), else for full trust the least recently used one, shared. */
    private fun pooled(tier: PluginTier): Process? {
        val services = if (tier == PluginTier.CONTAINED) SANDBOX_SLOTS else FULL_TRUST_SLOTS
        val prefix = if (tier == PluginTier.CONTAINED) "contained-slot" else "full-slot"
        val slots = services.indices.map { processes["$prefix$it"] }
        val index = slots.indexOfFirst { it == null || it.plugins.isEmpty() }.takeIf { it >= 0 }
            ?: slots.indices.filter { slots[it]!!.users == 0 }.minByOrNull { slots[it]!!.lastUsedMs }?.also { i ->
                slots[i]!!.plugins.toList().forEach { detach(it, slots[i]!!) }
            }
            ?: if (tier == PluginTier.CONTAINED) return null else slots.indices.minByOrNull { slots[it]!!.lastUsedMs }!!
        return processes.getOrPut("$prefix$index") { Process("$prefix$index", tier, services[index], null) }
    }

    private fun detach(pluginId: String, process: Process) {
        process.plugins.remove(pluginId)
        if (byPlugin[pluginId] === process) byPlugin.remove(pluginId)
        // Off the lock: a plugin's onUnload is its own code and may be slow; nothing waits for it.
        process.runtime?.let { runtime -> unloads.execute { runCatching { runtime.unloadPlugin(pluginId) } } }
    }

    private val unloads = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** `bindIsolatedService` names the instance; the plugin id hashed keeps it short and free of odd characters. */
    private fun instanceName(pluginId: String): String =
        "p" + java.security.MessageDigest.getInstance("SHA-256").digest(pluginId.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun bindLocked(context: Context, process: Process): Boolean {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    synchronized(lock) { process.connecting.also { process.connecting = null } }?.complete(null)
                    return
                }
                val runtime = IPluginRuntime.Stub.asInterface(binder)
                val linked = runCatching { binder.linkToDeath({ died(context, process, binder, "the plugin process died") }, 0) }.isSuccess
                if (!linked) {
                    died(context, process, binder, "the plugin process died as it started")
                    return
                }
                val waiting = synchronized(lock) {
                    process.runtime = runtime
                    process.connecting.also { process.connecting = null }
                }
                waiting?.complete(runtime)
            }

            // The death recipient reports the process dying; this only means the connection is gone with it.
            override fun onServiceDisconnected(name: ComponentName?) = Unit

            override fun onBindingDied(name: ComponentName?) {
                died(context, process, null, "the plugin process could not be kept running")
            }

            override fun onNullBinding(name: ComponentName?) {
                synchronized(lock) { process.connecting.also { process.connecting = null } }?.complete(null)
            }
        }
        val intent = Intent(context, process.service)
        val bound = runCatching {
            val instance = process.instance
            if (instance != null && Build.VERSION.SDK_INT >= 29) {
                context.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, instance, context.mainExecutor, connection)
            } else {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }.getOrDefault(false)
        if (bound) process.connection = connection
        return bound
    }

    /**
     * A process is gone: its connection is dropped (so Android does not restart it behind droidtop's back) and the
     * plugins it held are reported once. A stale report, for a binder this process no longer uses, is ignored.
     */
    private fun died(context: Context, process: Process, binder: IBinder?, reason: String) {
        val held = synchronized(lock) {
            if (processes[process.key] !== process) return
            if (binder != null && process.runtime != null && process.runtime?.asBinder() !== binder) return
            processes.remove(process.key)
            process.plugins.forEach { if (byPlugin[it] === process) byPlugin.remove(it) }
            process.runtime = null
            process.connecting?.complete(null)
            process.connecting = null
            process.idle?.let { main.removeCallbacks(it) }
            process.idle = null
            process.connection?.let { runCatching { context.unbindService(it) } }
            process.connection = null
            process.plugins.toSet().also { process.plugins.clear() }
        }
        PluginCrashPolicy.processDied(context, held, reason)
    }

    /**
     * The idle unload: nobody used the process for [IDLE_MS]. An isolated process ends with its last binding, so a
     * contained plugin starts afresh next time. A full-trust slot keeps its plugins assigned: Android keeps the process
     * cached until it needs the memory, and the next use binds the same slot.
     */
    private fun letGo(context: Context, process: Process) {
        synchronized(lock) {
            process.idle = null
            if (process.users > 0 || processes[process.key] !== process) return
            val connection = process.connection
            process.connection = null
            process.runtime = null
            if (process.tier == PluginTier.CONTAINED) {
                processes.remove(process.key)
                process.plugins.forEach { if (byPlugin[it] === process) byPlugin.remove(it) }
                process.plugins.clear()
            }
            connection?.let { runCatching { context.unbindService(it) } }
        }
    }
}
