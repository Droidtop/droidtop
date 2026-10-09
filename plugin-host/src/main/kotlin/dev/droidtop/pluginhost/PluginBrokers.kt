package dev.droidtop.pluginhost

import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** The binder object one plugin holds: it can only ever speak as [core]'s plugin. */
class PluginHostBroker(private val core: BrokerCore) : IPluginHostBroker.Stub() {
    override fun call(requestJson: String?): String = core.call(requestJson.orEmpty())

    // The descriptor goes back as the return value, which the binder closes on droidtop's side once it is sent.
    override fun open(requestJson: String?, reply: Array<String?>?): android.os.ParcelFileDescriptor? {
        val (text, fd) = core.open(requestJson.orEmpty())
        if (reply != null && reply.isNotEmpty()) reply[0] = text
        return fd
    }
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
            .put("modes", JSONArray(PluginBrokers.modesProvider()))
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

    override fun openLink(uri: String, title: String?): String? {
        val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            if (appContext.packageManager.queryIntentActivities(view, 0).isEmpty()) {
                if (uri.startsWith("magnet:", ignoreCase = true)) "no app opens magnet links" else "no app opens this link"
            } else {
                appContext.startActivity(Intent.createChooser(view, title ?: "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                null
            }
        } catch (e: Exception) {
            "the link could not be opened"
        }
    }

    override fun toast(pluginLabel: String, text: String): Boolean {
        // Posted, never run on the binder thread; the plugin's name leads so it is never mistaken for droidtop's own words.
        return android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(appContext, "$pluginLabel: $text", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun librarySystems(): JSONObject = PluginBrokers.librarySystemsProvider()

    private val pluginVault by lazy { PluginVault.forContext(appContext) }

    override fun vault(): PluginVault = pluginVault

    override fun gamesRoots(): List<String> = dev.droidtop.library.settings.LibraryPaths.roots()

    override fun libraryFilesChanged(pluginId: String, change: dev.droidtop.library.settings.PathChange): Boolean =
        dev.droidtop.library.settings.LibraryPaths.report(appContext, change, source = pluginId) != null

    override fun socialChanged(pluginId: String, change: JSONObject): Boolean = PluginBrokers.socialChanged(pluginId, change)

    // ---- Android permissions droidtop holds for plugins (docs/plugin-api.md 4.1) ----

    override fun holdsAndroid(need: AndroidNeed): Boolean =
        Build.VERSION.SDK_INT < need.fromSdk || appContext.checkSelfPermission(need.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun requestAndroid(need: AndroidNeed): Boolean = BrokerAskActivity.requestPermission(appContext, need.permission)

    override fun notify(pluginId: String, pluginLabel: String, title: String, text: String): Boolean = runCatching {
        val manager = appContext.getSystemService(android.app.NotificationManager::class.java) ?: return@runCatching false
        // One channel per plugin, named after it, so the person can silence one plugin in Android's own settings.
        val channel = "plugin-$pluginId"
        manager.createNotificationChannel(android.app.NotificationChannel(channel, pluginLabel, android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val notification = android.app.Notification.Builder(appContext, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(pluginLabel)
            .setAutoCancel(true)
            .build()
        manager.notify(channel, "$title|$text".hashCode(), notification)
        true
    }.getOrDefault(false)

    // ---- RetroArch's network commands (docs/plugin-api.md 3 B) ----

    override fun retroArchCommand(line: String, replyMs: Long): String? =
        runCatching { RetroArchCommands.send(line, replyMs) }.getOrNull()

    // ---- Network (docs/plugin-api.md 3 D1, D2) ----

    override fun netState(): JSONObject {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
        val online = caps != null &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return JSONObject()
            .put("online", online)
            .put("type", type)
            .put("metered", cm?.isActiveNetworkMetered ?: false)
            .put("vpn", caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true)
    }

    override fun addressesOf(host: String): List<java.net.InetAddress> =
        runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())

    override fun http(call: HttpCall, allow: (String) -> Unit): HttpAnswer {
        val ms = call.timeoutMs.toInt()
        val connection = dev.droidtop.net.Http.openGuarded(call.method, call.url, call.headers, call.body, dev.droidtop.net.Http.Timeouts(ms, ms), allow = allow)
        try {
            val status = connection.responseCode
            val headers = connection.headerFields.entries
                .mapNotNull { (name, values) -> name?.lowercase()?.let { it to values.joinToString(", ") } }
                .toMap()
            val stream = (if (status >= 400) connection.errorStream else runCatching { connection.inputStream }.getOrNull())
            val (body, truncated) = stream?.use { readCapped(it, call.maxBytes) } ?: (ByteArray(0) to false)
            return HttpAnswer(status, connection.url.toString(), headers, body, truncated)
        } finally {
            connection.disconnect()
        }
    }

    private fun readCapped(input: java.io.InputStream, max: Int): Pair<ByteArray, Boolean> {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray() to false
            if (out.size() + n > max) {
                out.write(buffer, 0, max - out.size())
                return out.toByteArray() to true
            }
            out.write(buffer, 0, n)
        }
    }

    override fun startDownload(record: PluginRecord, url: String, file: java.io.File, store: PluginDataStore, allow: (String) -> Unit): String {
        val name = file.relativeTo(store.root).path.replace(java.io.File.separatorChar, '/')
        return PluginJobsCenter.startBrokered(record.manifest.id, record.manifest.label, "droidtop", "Download $name") { jobId ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val part = java.io.File(file.parentFile, ".${file.name}.part")
                try {
                    val connection = dev.droidtop.net.Http.openGuarded("GET", url, emptyMap(), null, dev.droidtop.net.Http.BigFile.timeouts, allow = allow)
                    try {
                        val status = connection.responseCode
                        if (status !in 200..299) return@withContext PluginReply.error(PluginErrorCode.FAILED, "HTTP $status from ${NetScope.hostOf(connection.url.toString())}")
                        val total = connection.contentLengthLong
                        if (total > 0) store.requireRoom(total, file)
                        file.parentFile?.mkdirs()
                        var count = 0L
                        var reported = 0L
                        connection.inputStream.use { input ->
                            part.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    count += n
                                    if (total <= 0 && count > PluginDataStore.LIMIT_BYTES) store.requireRoom(count, file)
                                    out.write(buffer, 0, n)
                                    if (count - reported >= PROGRESS_STEP) {
                                        reported = count
                                        val percent = if (total > 0) (count * 100 / total).toInt() else -1
                                        PluginJobsCenter.progressBrokered(jobId, percent, "${count / (1024 * 1024)} MiB")
                                    }
                                }
                            }
                        }
                        if (!part.renameTo(file)) return@withContext PluginReply.error(PluginErrorCode.FAILED, "$name could not be moved into place")
                        PluginReply.ok(JSONObject().put("name", name).put("size", count))
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: BrokerException) {
                    PluginReply.error(e.code, e.message.orEmpty())
                } catch (e: java.io.IOException) {
                    PluginReply.error(PluginErrorCode.FAILED, e.message ?: "the download failed")
                } finally {
                    part.delete()
                }
            }
        }
    }

    // ---- A plugin's own data and the files it was handed (docs/plugin-api.md 3 H1, D3, D4, D5) ----

    override fun dataStore(pluginId: String): PluginDataStore = PluginDataStore(PluginStore.dataDirFor(appContext, pluginId))

    private val contextStore by lazy { PluginContexts(appContext) }

    override fun contexts(): PluginContexts = contextStore

    private val tokens by lazy { PluginFileTokens.forPluginsRoot(PluginStore.root(appContext)) }

    override fun fileTokens(): PluginFileTokens = tokens

    override fun pickDocument(pluginLabel: String, mode: String, mime: String, name: String?): PickedDocument? {
        val (uri, flags) = BrokerAskActivity.pick(appContext, mode, mime, name) ?: return null
        val writable = mode == "create" || (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        val keep = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        // Kept across restarts, so the token the plugin holds still opens the file tomorrow.
        runCatching { appContext.contentResolver.takePersistableUriPermission(uri, keep) }
        var display = name ?: uri.lastPathSegment.orEmpty()
        var size: Long? = null
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { display = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return PickedDocument(uri.toString(), display, size, writable)
    }

    override fun webSession(request: WebSessionRequest): WebSessionResult? = WebSessionActivity.show(appContext, request)

    override fun openDocument(uri: String, mode: String): android.os.ParcelFileDescriptor? = runCatching {
        val resolverMode = when (mode) {
            "w" -> "wt"
            "a" -> "wa"
            "rw" -> "rw"
            else -> "r"
        }
        appContext.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), resolverMode)
    }.getOrNull()

    override fun sharedFilesAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else {
            appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /**
     * Each mounted volume's root by id ("primary", or the volume's own id). Found from droidtop's own app folder on each
     * volume, which exists on every API level this build runs on, rather than from a call only API 30 has.
     */
    override fun storageRoots(): Map<String, java.io.File> {
        val storage = appContext.getSystemService(android.os.storage.StorageManager::class.java)
        val out = LinkedHashMap<String, java.io.File>()
        @Suppress("DEPRECATION")
        out["primary"] = android.os.Environment.getExternalStorageDirectory()
        appContext.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val root = dir.absolutePath.substringBefore("/Android/data/").takeIf { it != dir.absolutePath }?.let { java.io.File(it) } ?: return@forEach
            val volume = storage?.getStorageVolume(dir) ?: return@forEach
            if (volume.isPrimary) return@forEach
            out[volume.uuid ?: root.name] = root
        }
        return out
    }

    override fun storageVolumes(): JSONArray {
        val storage = appContext.getSystemService(android.os.storage.StorageManager::class.java)
        val out = JSONArray()
        storageRoots().forEach { (id, root) ->
            val volume = storage?.getStorageVolume(root)
            val stat = runCatching { android.os.StatFs(root.path) }.getOrNull()
            out.put(
                JSONObject()
                    .put("id", id)
                    .put("label", volume?.getDescription(appContext) ?: id)
                    .put("primary", id == "primary")
                    .put("removable", volume?.isRemovable ?: false)
                    .put("state", volume?.state ?: "unknown")
                    .put("freeBytes", stat?.availableBytes ?: 0L)
                    .put("totalBytes", stat?.totalBytes ?: 0L),
            )
        }
        return out
    }

    override fun chainServedBy(pluginId: String): List<String> = PluginBrokers.chainServedBy(pluginId)

    override fun forward(provider: PluginRecord, call: PluginCall, timeoutMs: Long, personStarted: Boolean): PluginReply {
        val callerId = call.caller.optString("id")
        val chain = call.caller.optJSONArray("via")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        val policy = PluginCrashPolicy(appContext)
        return try {
            runBlocking {
                PluginBrokers.serving(provider.manifest.id, chain) {
                    val reply = policy.handle(provider, call, timeoutMs = timeoutMs, crashOnTimeout = true, userInitiated = personStarted || PluginBrokers.userInitiated(callerId))
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

    override fun reportProviderLevel(provider: PluginRecord, api: String, level: String): Boolean {
        ProviderLevels.forContext(appContext).set(provider.manifest.id, api, level)
        return true
    }

    override fun cancelBrokeredJob(caller: PluginRecord, jobId: String): Boolean {
        val entry = PluginJobsCenter.find(jobId)?.takeIf { it.pluginId == caller.manifest.id && !it.done } ?: return false
        PluginJobsCenter.cancel(entry.jobId)
        return true
    }

    override fun brokeredJobStatus(caller: PluginRecord, jobId: String): PluginReply {
        val entry = PluginJobsCenter.find(jobId)?.takeIf { it.pluginId == caller.manifest.id }
            ?: return PluginReply.error(PluginErrorCode.NOT_FOUND, "no such job")
        if (!entry.done) return PluginReply.ok(JSONObject().put("done", false).put("status", entry.statusLine).put("percent", entry.percent))
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

        /** How often a download says how far it is. */
        const val PROGRESS_STEP = 1024L * 1024
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

    /** :app sets this at start: the modes the person has switched on (`gaming`, `standard`, `desktop`), for `host.info` `modes`. */
    @Volatile var modesProvider: () -> List<String> = { emptyList() }

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

    fun userInitiated(pluginId: String): Boolean {
        val now = System.currentTimeMillis()
        return active[pluginId]?.any { it.user && now - it.startedMs < it.budgetMs } == true
    }

    private val jobMarks = ConcurrentHashMap<String, Pair<String, Active>>()

    /**
     * A job the person started (a page's Download button) runs after the call that started it has returned, so its
     * host calls would otherwise find no call of theirs in flight and be refused a user-only op, such as opening a
     * site's download page in droidtop's web view (docs/plugin-api.md 3 G3). It counts as their call until it ends,
     * or [JOB_BUDGET_MS] at most.
     */
    fun jobRunning(pluginId: String, jobId: String) {
        val mark = Active(System.currentTimeMillis(), JOB_BUDGET_MS, true)
        active.getOrPut(pluginId) { CopyOnWriteArrayList() }.add(mark)
        jobMarks[jobId] = pluginId to mark
    }

    fun jobEnded(jobId: String) {
        jobMarks.remove(jobId)?.let { (pluginId, mark) -> active[pluginId]?.remove(mark) }
    }

    private const val JOB_BUDGET_MS = 30L * 60 * 1000

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
