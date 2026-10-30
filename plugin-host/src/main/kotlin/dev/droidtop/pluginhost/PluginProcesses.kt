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
    private val OPEN_SLOTS: List<Class<out PluginProcessService>> = listOf(
        PluginOpenSlot0::class.java, PluginOpenSlot1::class.java, PluginOpenSlot2::class.java, PluginOpenSlot3::class.java,
        PluginOpenSlot4::class.java, PluginOpenSlot5::class.java, PluginOpenSlot6::class.java, PluginOpenSlot7::class.java,
    )

    /**
     * Single-plugin slots (open, and contained below API 29) whose process was ended ([endSingleLocked]) and has not died
     * yet. A bind to such a slot could land in the old process, under the old plugin's filter and broker, so the slot is
     * not used until its death is seen.
     */
    private val dying = HashSet<String>()

    /** When each single-plugin slot's process was last ended, so a fresh process goes to the slot ended longest ago. */
    private val endedAt = HashMap<String, Long>()

    /** [network]: an isolated process whose sockets droidtop's broker makes (an open plugin's net.sockets, 5.6). */
    private class Process(
        val key: String,
        val tier: PluginTier,
        val service: Class<out PluginProcessService>,
        val instance: String?,
        val network: Boolean = false,
    ) {
        var runtime: IPluginRuntime? = null
        var connection: ServiceConnection? = null
        var connecting: CompletableDeferred<IPluginRuntime?>? = null
        val plugins = LinkedHashSet<String>()
        var users = 0
        var lastUsedMs = 0L
        var idle: Runnable? = null
        var app: Context? = null
    }

    private val lock = Any()
    private val processes = HashMap<String, Process>()
    private val byPlugin = HashMap<String, Process>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The runtime of [pluginId]'s process for [tier], bound and started when needed, counted as one user until
     * [release]. Null when the process could not be started (or, below API 29, every contained slot is busy).
     * [network]: a contained process whose sockets droidtop's broker makes, never the same process as one without.
     */
    suspend fun acquire(context: Context, pluginId: String, tier: PluginTier, network: Boolean = false): IPluginRuntime? {
        val app = context.applicationContext
        val (process, wait) = synchronized(lock) {
            val process = processFor(pluginId, tier, network) ?: return null
            process.users++
            process.app = app
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

    /**
     * Counts one more user of [pluginId]'s process when one is running, without starting one: droidtop's web view open
     * for the plugin's site keeps it loaded, so the request the person is passing the check for goes on in the process
     * that met it (Droidtop/tracker#783). Whether it counted; when it did, [release] lets go.
     */
    fun keep(pluginId: String): Boolean = synchronized(lock) {
        val process = byPlugin[pluginId]?.takeIf { it.runtime != null } ?: return false
        process.users++
        process.lastUsedMs = System.currentTimeMillis()
        process.idle?.let { main.removeCallbacks(it) }
        process.idle = null
        true
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

    /**
     * [pluginId]'s code or trust changed (an install of other bytes, an uninstall, an approval or denial): whatever
     * process holds it stops holding it, so the next call loads what is on disk now. A process only loads a plugin once
     * ("already loaded is already loaded", [PluginProcessService]), so without this the old version answered until droidtop
     * restarted (Droidtop/tracker#539). A contained plugin's own isolated process is ended; a plugin in a shared slot is
     * unloaded and the slot goes on. No-op when no process holds it. Safe from any thread.
     */
    fun retire(pluginId: String) {
        val ended = synchronized(lock) {
            val process = byPlugin[pluginId] ?: return
            Log.i("droidtop.plugin", "$pluginId: changed on disk or in trust, dropping it from ${process.key} so the next call loads it afresh")
            if (process.tier == PluginTier.CONTAINED && process.instance != null) {
                processes.remove(process.key)
                process.plugins.forEach { if (byPlugin[it] === process) byPlugin.remove(it) }
                process.plugins.clear()
                process.runtime = null
                process.connecting?.complete(null)
                process.connecting = null
                process.idle?.let { main.removeCallbacks(it) }
                process.idle = null
                val connection = process.connection
                process.connection = null
                // The isolated process ends with its last binding; the death recipient ignores a process no longer registered.
                connection to process.app
            } else {
                detach(pluginId, process)
                return
            }
        }
        val (connection, context) = ended
        if (connection != null && context != null) runCatching { context.unbindService(connection) }
    }

    /** Which plugin processes exist now and what each holds, for the containment check. */
    fun describe(pluginId: String): String? = synchronized(lock) {
        byPlugin[pluginId]?.let {
            val where = when (it.tier) {
                PluginTier.CONTAINED -> "sealed, in a process of its own"
                PluginTier.OPEN -> "an open process of its own, sharing droidtop's user id, with only the holes it was granted"
                PluginTier.FULL_TRUST -> "a process of its own, sharing droidtop's user id"
            }
            "${it.key} ($where)"
        }
    }

    private fun processFor(pluginId: String, tier: PluginTier, network: Boolean = false): Process? {
        byPlugin[pluginId]?.let { current ->
            if (current.tier == tier && current.network == network) return current
            // Full access or the network was allowed or taken away: the plugin leaves the process of its old tier.
            detach(pluginId, current)
        }
        val process = if (tier == PluginTier.CONTAINED && Build.VERSION.SDK_INT >= 29) {
            val key = if (network) "contained-net:$pluginId" else "contained:$pluginId"
            processes.getOrPut(key) { Process(key, tier, PluginSandboxService::class.java, instanceName(if (network) "$pluginId#net" else pluginId), network) }
        } else {
            // Open and full-trust processes are not isolated, so there is no per-instance bindIsolatedService form; below
            // API 29 a contained plugin falls here too. All three draw from a fixed pool of declared slot services.
            pooled(tier) ?: return null
        }
        process.plugins += pluginId
        byPlugin[pluginId] = process
        return process
    }

    /** A free slot, else the least recently used idle one (its plugins leave it), else the least recently used one, shared. */
    private fun pooled(tier: PluginTier): Process? {
        if (tier == PluginTier.OPEN) return pooledSingle(OPEN_SLOTS, "open-slot", tier)
        // Below API 29 a contained slot is one plugin's for its whole life, as an open one is (docs/plugin-api.md 5.6).
        if (tier == PluginTier.CONTAINED) return pooledSingle(SANDBOX_SLOTS, "contained-slot", tier)
        val (services, prefix) = FULL_TRUST_SLOTS to "full-slot"
        val slots = services.indices.map { processes["$prefix$it"] }
        val index = slots.indexOfFirst { it == null || it.plugins.isEmpty() }.takeIf { it >= 0 }
            ?: slots.indices.filter { slots[it]!!.users == 0 }.minByOrNull { slots[it]!!.lastUsedMs }?.also { i ->
                slots[i]!!.plugins.toList().forEach { detach(it, slots[i]!!) }
            }
            ?: slots.indices.minByOrNull { slots[it]!!.lastUsedMs }!!
        return processes.getOrPut("$prefix$index") { Process("$prefix$index", tier, services[index], null) }
    }

    /**
     * A slot for one plugin alone (docs/plugin-api.md 5.3 "Open processes", 5.6): an open process, or a contained one
     * below API 29. Never shared and never reused while its process lives, since its filter and broker are installed
     * once and would stay the first plugin's. A slot with no process and none dying, the one ended longest ago; else the
     * least recently used idle slot is ended to free one for the next call, and this call gets none.
     */
    private fun pooledSingle(services: List<Class<out PluginProcessService>>, prefix: String, tier: PluginTier): Process? {
        val free = services.indices.map { "$prefix$it" }.filter { processes[it] == null && it !in dying }
        val key = PluginSlots.freshest(free, endedAt)
        if (key == null) {
            services.indices.mapNotNull { processes["$prefix$it"] }.filter { it.users == 0 }.minByOrNull { it.lastUsedMs }?.let(::endSingleLocked)
            return null
        }
        val index = key.removePrefix(prefix).toInt()
        return Process(key, tier, services[index], null).also { processes[key] = it }
    }

    /** True when [process] serves one plugin for its whole life and ends with it: open, and a contained slot below API 29. */
    private fun single(process: Process): Boolean =
        PluginSlots.single(process.tier, perInstance = process.instance != null)

    /**
     * Ends a single-plugin process: it leaves the pool at once (so its death is not reported as a crash), its plugins are unloaded
     * and then it is unbound, which ends it ([PluginOpenService], [PluginSandboxSlotService]). Its slot stays out of use until the process has died.
     * Under [lock].
     */
    private fun endSingleLocked(process: Process) {
        processes.remove(process.key)
        endedAt[process.key] = System.currentTimeMillis()
        val held = process.plugins.toList()
        held.forEach { if (byPlugin[it] === process) byPlugin.remove(it) }
        process.plugins.clear()
        val runtime = process.runtime
        process.runtime = null
        process.connecting?.complete(null)
        process.connecting = null
        process.idle?.let { main.removeCallbacks(it) }
        process.idle = null
        val connection = process.connection
        process.connection = null
        val app = process.app
        if (runtime != null) {
            val key = process.key
            dying += key
            val watched = runCatching { runtime.asBinder().linkToDeath({ synchronized(lock) { dying -= key } }, 0) }.isSuccess
            if (!watched) dying -= key
        }
        unloads.execute {
            held.forEach { id -> runCatching { runtime?.unloadPlugin(id) } }
            if (connection != null && app != null) runCatching { app.unbindService(connection) }
        }
    }

    private fun detach(pluginId: String, process: Process) {
        // A single-plugin process is its plugin's alone: leaving it ends it, so no hole, filter or broker outlives the plugin.
        if (single(process)) {
            endSingleLocked(process)
            return
        }
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
            // A single-plugin process ends with its plugin's last use; the next call starts it afresh with the holes granted then.
            if (single(process)) {
                endSingleLocked(process)
                return
            }
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

/** The slot rules of [PluginProcesses] that need no Android; pure, so they are unit-tested. */
internal object PluginSlots {
    /** True when a process of [tier] is one plugin's for its whole life: open, and contained without a per-instance process. */
    fun single(tier: PluginTier, perInstance: Boolean): Boolean = tier == PluginTier.OPEN || (tier == PluginTier.CONTAINED && !perInstance)

    /** Of the [free] slots, the one whose process was ended longest ago (never ended counts as longest). */
    fun freshest(free: List<String>, endedAt: Map<String, Long>): String? = free.minByOrNull { endedAt[it] ?: 0L }
}
