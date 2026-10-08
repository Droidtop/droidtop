package dev.droidtop.pluginhost

import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The binder object one plugin holds: it can only ever speak as [core]'s plugin. */
class PluginHostBroker(private val core: BrokerCore) : IPluginHostBroker.Stub() {
    override fun call(requestJson: String?): String = core.call(requestJson.orEmpty())
}

/**
 * The first-use sheet's state (docs/plugin-api.md 4.3). The broker calls
 * [ask] from a binder thread and blocks; a surface that can show the sheet
 * attaches itself ([attachHost]), observes [pending] and answers with
 * [answer]. With no surface attached the answer is Not now at once: a
 * plugin can never make a prompt appear where nothing can draw it, and the
 * permission is granted from the plugin's Permissions screen instead.
 */
object PluginGrantPrompts {
    class Pending(val request: GrantPromptRequest) {
        internal val answer = CompletableDeferred<GrantAnswer>()
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    private val turn = Any()
    private var hosts = 0

    @Synchronized fun attachHost() {
        hosts++
    }

    @Synchronized fun detachHost() {
        if (hosts > 0) hosts--
    }

    @Synchronized fun hasHost(): Boolean = hosts > 0

    fun answer(pending: Pending, answer: GrantAnswer) {
        pending.answer.complete(answer)
    }

    /** Shows the sheet and waits for the answer, one prompt at a time; Not now when nothing can show it or nobody answers in [timeoutMs]. */
    fun ask(request: GrantPromptRequest, timeoutMs: Long = 60_000L): GrantAnswer {
        if (!hasHost()) return GrantAnswer.NOT_NOW
        return synchronized(turn) {
            val pending = Pending(request)
            _pending.value = pending
            try {
                runBlocking { withTimeoutOrNull(timeoutMs) { pending.answer.await() } } ?: GrantAnswer.NOT_NOW
            } finally {
                if (_pending.value === pending) _pending.value = null
            }
        }
    }
}

/**
 * The production [BrokerEnvironment]: droidtop's own state and code, in the
 * :app process. It builds every intent itself from validated data (a plugin
 * hands over strings, never an object), which is the rule docs/plugin-api.md
 * 1.4 makes universal.
 */
class AppBrokerEnvironment(context: Context) : BrokerEnvironment {
    private val appContext = context.applicationContext
    private val grantStore = PluginGrants.forContext(appContext)
    private val auditLog = PluginAudit.forContext(appContext)

    init {
        auditLog.purgeExpired()
    }

    override fun nowMs(): Long = System.currentTimeMillis()

    override fun record(pluginId: String): PluginRecord? = PluginBundleInstaller.readRecord(PluginStore.root(appContext), pluginId)

    override fun resolution(): ApiResolution = PluginApiResolver.current(appContext)

    override fun providerChoice(api: String): String? = PluginProviderChoices.forContext(appContext).chosen(api)

    override fun grants(pluginId: String): PluginGrants.Snapshot = grantStore.read(pluginId)

    override fun setGrant(pluginId: String, permission: String, state: GrantState) {
        grantStore.set(pluginId, permission, state)
    }

    override fun noteWanted(pluginId: String, permission: String) {
        grantStore.noteWanted(pluginId, permission)
    }

    override fun audit(pluginId: String, entry: AuditEntry) {
        auditLog.append(pluginId, entry)
    }

    override fun userInitiated(pluginId: String): Boolean = PluginBrokers.userInitiated(pluginId)

    override fun remainingMs(pluginId: String): Long? = PluginBrokers.remainingMs(pluginId)

    override fun prompt(request: GrantPromptRequest): GrantAnswer = PluginGrantPrompts.ask(request)

    override fun isOfficial(origin: String): Boolean = PluginOriginKeys.isOfficial(origin)

    override fun trustBadge(origin: String): String = when {
        PluginOriginKeys.isOfficial(origin) -> "Official"
        else -> UserOriginKeys.load(UserOriginKeys.storeFile(appContext))[origin]
            ?.let { entry -> entry.repo?.let { "Verified by: $it" } ?: "Added by you" }
            ?: "Not verified"
    }

    override fun installId(pluginId: String): String = grantStore.installIdFor(pluginId)

    override fun hostFacts(): JSONObject {
        val version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull()
        return JSONObject()
            .put("droidtopVersion", version ?: "unknown")
            .put("mode", PluginBrokers.modeProvider())
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
    }

    override fun appInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    override fun launchApp(packageName: String): Boolean = runCatching {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)

    override fun launchAppWithExtras(packageName: String, extras: Map<String, String>, action: String?): Boolean = runCatching {
        val intent = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return@runCatching false
        action?.let { intent.action = it }
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        true
    }.getOrDefault(false)

    override fun toast(pluginLabel: String, text: String): Boolean {
        // Posted, never run on the binder thread; the plugin's name leads so it is never mistaken for droidtop's own words.
        return android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(appContext, "$pluginLabel: $text", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun librarySystems(): JSONObject = PluginBrokers.librarySystemsProvider()

    override fun gamesRoots(): List<String> = dev.droidtop.library.settings.LibraryPaths.roots()

    override fun libraryFilesChanged(pluginId: String, change: dev.droidtop.library.settings.PathChange): Boolean =
        dev.droidtop.library.settings.LibraryPaths.report(appContext, change, source = pluginId) != null

    override fun socialChanged(pluginId: String, change: JSONObject): Boolean = PluginBrokers.socialChanged(pluginId, change)

    override fun chainServedBy(pluginId: String): List<String> = PluginBrokers.chainServedBy(pluginId)

    override fun forward(provider: PluginRecord, call: PluginCall, timeoutMs: Long): PluginReply {
        val callerId = call.caller.optString("id")
        val chain = call.caller.optJSONArray("via")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        val policy = PluginCrashPolicy(appContext)
        return try {
            runBlocking {
                PluginBrokers.serving(provider.manifest.id, chain) {
                    val reply = policy.handle(provider, call, timeoutMs = timeoutMs, crashOnTimeout = true, userInitiated = PluginBrokers.userInitiated(callerId))
                    // A provider that timed out or died is reported as crashed, not as the caller's own failure (docs/plugin-api.md 2.5).
                    if (!reply.ok && reply.code == PluginErrorCode.FAILED && record(provider.manifest.id)?.disabledReason != null) {
                        PluginReply.error(PluginErrorCode.PROVIDER_CRASHED, reply.message.orEmpty())
                    } else {
                        reply
                    }
                }
            }
        } finally {
            policy.shutdown()
        }
    }

    override fun startBrokeredJob(caller: PluginRecord, provider: PluginRecord, call: PluginCall): String? {
        val op = call.op
        return PluginJobsCenter.startBrokered(caller.manifest.id, caller.manifest.label, provider.manifest.label, "${call.point.removePrefix("api:")}.$op") {
            val policy = PluginCrashPolicy(appContext)
            try {
                val chain = call.caller.optJSONArray("via")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
                PluginBrokers.serving(provider.manifest.id, chain) {
                    policy.handle(provider, call, timeoutMs = JOB_CALL_TIMEOUT_MS, crashOnTimeout = false, userInitiated = false)
                }
            } finally {
                policy.shutdown()
            }
        }
    }

    override fun brokeredJobStatus(caller: PluginRecord, jobId: String): PluginReply {
        val entry = PluginJobsCenter.find(jobId)?.takeIf { it.pluginId == caller.manifest.id }
            ?: return PluginReply.error(PluginErrorCode.NOT_FOUND, "no such job")
        if (!entry.done) return PluginReply.ok(JSONObject().put("done", false).put("status", entry.statusLine))
        val reply = PluginJobsCenter.brokeredReply(caller.manifest.id, jobId)
            ?: return PluginReply.error(PluginErrorCode.NOT_FOUND, "no such brokered job")
        return if (reply.ok) {
            PluginReply.ok(JSONObject().put("done", true).put("ok", true).put("data", reply.data))
        } else {
            PluginReply.ok(JSONObject().put("done", true).put("ok", false).put("code", reply.code?.name ?: "FAILED").put("message", reply.message.orEmpty()))
        }
    }

    private companion object {
        /** A job has no per-call bound (docs/plugin-api.md 8); this is the ceiling that still catches a provider that never answers. */
        const val JOB_CALL_TIMEOUT_MS = 30L * 60 * 1000
    }
}

/**
 * The process-wide side of the broker: one binder object per plugin
 * ([binderFor]), and the bookkeeping only the host can know, which host to
 * plugin calls are in flight for a plugin and whether the user started
 * them ([during]), and which chain a provider is serving ([serving]).
 */
object PluginBrokers {
    /** :app sets this at start: the mode droidtop is in (`gaming`, `android`, `desktop`), for `host.info`. */
    @Volatile var modeProvider: () -> String = { "unknown" }

    /**
     * :app sets this at start: the library's systems for `library.read` `systems` (the library lives in :library-core,
     * which depends on this module). Called on a binder thread, never the main thread.
     */
    @Volatile var librarySystemsProvider: () -> JSONObject = { JSONObject().put("ready", false).put("systems", JSONArray()) }

    /**
     * :app sets this at start: a social provider's `social.changed` (docs/plugin-api.md 3 C19), handed to the library's
     * social hub, which lives in :library-core above this module. Called on a binder thread; it must not block.
     */
    @Volatile var socialChanged: (pluginId: String, change: JSONObject) -> Boolean = { _, _ -> false }

    private val binders = ConcurrentHashMap<String, IPluginHostBroker>()
    private var environment: AppBrokerEnvironment? = null

    @Synchronized
    private fun environmentFor(context: Context): AppBrokerEnvironment =
        environment ?: AppBrokerEnvironment(context).also { environment = it }

    /** The broker object for [pluginId]: always the same one, so the plugin's process keeps a stable identity. */
    fun binderFor(context: Context, pluginId: String): IPluginHostBroker =
        binders.getOrPut(pluginId) { PluginHostBroker(BrokerCore(pluginId, environmentFor(context))) }

    /** droidtop's own calls to provider plugins (Quit to Library's force-stop, for one): see [HostApiCaller]. */
    fun hostCaller(context: Context): HostApiCaller = HostApiCaller(environmentFor(context))

    fun forget(pluginId: String) {
        binders.remove(pluginId)
        active.remove(pluginId)
        served.remove(pluginId)
    }

    private class Active(val startedMs: Long, val budgetMs: Long, val user: Boolean)

    private val active = ConcurrentHashMap<String, CopyOnWriteArrayList<Active>>()
    private val served = ConcurrentHashMap<String, CopyOnWriteArrayList<List<String>>>()

    /** Runs [block], a host to plugin call, and records it as in flight so a broker call made meanwhile knows whether the user started it and how long is left. */
    suspend fun <T> during(pluginId: String, userInitiated: Boolean, budgetMs: Long, block: suspend () -> T): T {
        val mark = Active(System.currentTimeMillis(), budgetMs, userInitiated)
        val list = active.getOrPut(pluginId) { CopyOnWriteArrayList() }
        list.add(mark)
        try {
            return block()
        } finally {
            list.remove(mark)
        }
    }

    /** Plugin ids with host-to-plugin calls currently in flight. */
    internal fun inFlightPluginIds(): Set<String> = active.filterValues { it.isNotEmpty() }.keys.toSet()

    fun userInitiated(pluginId: String): Boolean = active[pluginId]?.any { it.user } == true

    fun remainingMs(pluginId: String): Long? =
        active[pluginId]?.lastOrNull()?.let { it.budgetMs - (System.currentTimeMillis() - it.startedMs) }

    /** Runs [block] while [pluginId], a provider, serves a call that came through [chain]; nested calls it makes carry that chain. */
    suspend fun <T> serving(pluginId: String, chain: List<String>, block: suspend () -> T): T {
        val list = served.getOrPut(pluginId) { CopyOnWriteArrayList() }
        list.add(chain)
        try {
            return block()
        } finally {
            list.remove(chain)
        }
    }

    fun chainServedBy(pluginId: String): List<String> = served[pluginId]?.lastOrNull().orEmpty()
}
