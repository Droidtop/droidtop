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
    override fun call(requestJson: String?): String {
        val request = requestJson.orEmpty()
        val reply = core.call(request)
        if (request.length > BrokerTraffic.LARGE_CHARS || reply.length > BrokerTraffic.LARGE_CHARS) {
            BrokerTraffic.note(core.pluginId, request, reply.length)
        }
        return reply
    }

    // The descriptor goes back as the return value, which the binder closes on droidtop's side once it is sent.
    override fun open(requestJson: String?, reply: Array<String?>?): android.os.ParcelFileDescriptor? {
        val (text, fd) = core.open(requestJson.orEmpty())
        if (reply != null && reply.isNotEmpty()) reply[0] = text
        return fd
    }
}

/**
 * Names the plugin and the op behind bursts of large broker calls. Android warns "Large reply transaction" for
 * anything over about 200 KB without saying whose it is (the one search showed dozens of 320 KB replies a second,
 * Droidtop/tracker#485): this logs, at most once every [WINDOW_MS] per plugin, how many large calls it made and the
 * last one's api, op and sizes, so a rig run's logcat names the plugin and the op instead of leaving it a guess.
 */
internal object BrokerTraffic {
    /** A request or reply of more characters than this (two bytes each in a Parcel) is large. */
    const val LARGE_CHARS = 64 * 1024
    private const val WINDOW_MS = 5_000L

    private class Window(var lastLog: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()
    private val API_OP = Regex("\"api\"\\s*:\\s*\"([^\"]*)\".{0,120}?\"op\"\\s*:\\s*\"([^\"]*)\"")

    fun note(pluginId: String, request: String, replyChars: Int, now: Long = System.currentTimeMillis()) {
        val window = windows.getOrPut(pluginId) { Window(0L, 0) }
        synchronized(window) {
            window.count++
            if (now - window.lastLog < WINDOW_MS) return
            val match = API_OP.find(request.take(300))
            android.util.Log.w(
                "droidtop.broker",
                "$pluginId made ${window.count} large broker calls since the last note; last " +
                    "${match?.groupValues?.get(1) ?: "?"} ${match?.groupValues?.get(2) ?: "?"}, request ${request.length} chars, reply $replyChars chars",
            )
            window.lastLog = now
            window.count = 0
        }
    }
}

/**
 * The first-use sheet's state (docs/plugin-api.md 4.3). The broker calls
 * [ask] from a binder thread and blocks; [ask] publishes the question in
 * [pending] and opens the one window that draws the sheet ([setSurface]:
 * :app's sheet activity, over whatever is on screen in Gaming, Standard
 * and Desktop alike), which answers with [answer]. With no surface, or one
 * that could not open, the answer is Not now at once: a plugin can never
 * make a prompt appear where nothing can draw it, and the permission is
 * granted from the plugin's Permissions screen instead.
 */
object PluginGrantPrompts {
    class Pending(val request: GrantPromptRequest) {
        internal val answer = CompletableDeferred<GrantAnswer>()
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending

    private val turn = Any()

    @Volatile private var surface: (() -> Boolean)? = null

    /** :app sets this at start: opens the sheet's window, which reads [pending]; false when it could not be opened. */
    fun setSurface(open: (() -> Boolean)?) {
        surface = open
    }

    fun answer(pending: Pending, answer: GrantAnswer) {
        pending.answer.complete(answer)
    }

    /** Shows the sheet and waits for the answer, one prompt at a time; Not now when nothing can show it or nobody answers in [timeoutMs]. */
    fun ask(request: GrantPromptRequest, timeoutMs: Long = 60_000L): GrantAnswer {
        val open = surface ?: return GrantAnswer.NOT_NOW
        return synchronized(turn) {
            val pending = Pending(request)
            _pending.value = pending
            try {
                if (!runCatching { open() }.getOrDefault(false)) {
                    GrantAnswer.NOT_NOW
                } else {
                    runBlocking { withTimeoutOrNull(timeoutMs) { pending.answer.await() } } ?: GrantAnswer.NOT_NOW
                }
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
    private val callCounts = PluginAudit.countsForContext(appContext)

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

    override fun countCall(pluginId: String, key: String) {
        callCounts.count(pluginId, key)
    }

    override fun notNow(pluginId: String, permission: String): Boolean = PluginRefusals.isNotNow(pluginId, permission)

    override fun rememberNotNow(pluginId: String, permission: String) = PluginRefusals.rememberNotNow(pluginId, permission)

    override fun noteRefused(pluginId: String, permission: String) = PluginRefusals.noteRefused(pluginId, permission)

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

    override fun linkFallback(pluginLabel: String, uri: String, why: String): Boolean {
        val copied = runCatching {
            val clipboard = appContext.getSystemService(android.content.ClipboardManager::class.java) ?: return@runCatching false
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(pluginLabel, uri))
            true
        }.getOrDefault(false)
        val sentence = if (copied) "$why. droidtop copied the link: paste it where you want it" else "$why. The link is: $uri"
        dev.droidtop.library.settings.Notices.flash(sentence, tone = dev.droidtop.library.settings.NoticeTone.Caution, context = appContext)
        return copied
    }

    override fun toast(pluginLabel: String, text: String): Boolean {
        // A flash led by the plugin's name, so it is never mistaken for droidtop's own words (docs/SPEC.md 7k, "Notices");
        // the whole text goes to Recent. Over a game, where no droidtop surface is on screen, it is a platform toast.
        dev.droidtop.library.settings.Notices.flash(text, source = pluginLabel, context = appContext)
        return true
    }

    override fun confirm(notice: dev.droidtop.library.settings.Notice, proceed: String, cancel: String): Boolean =
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                dev.droidtop.library.settings.NoticeDialogs.confirm(notice, cancelLabel = cancel, proceedLabel = proceed)
            } == dev.droidtop.library.settings.ConfirmAnswer.Proceed
        }

    override fun librarySystems(): JSONObject = PluginBrokers.librarySystemsProvider()

    override fun savesWhere(pluginId: String, args: JSONObject): JSONObject =
        PluginBrokers.saves?.where(pluginId, args) ?: super.savesWhere(pluginId, args)

    override fun savesChanged(pluginId: String, args: JSONObject): JSONObject =
        PluginBrokers.saves?.changed(pluginId, args) ?: super.savesChanged(pluginId, args)

    override fun libraryEngineGames(args: JSONObject): JSONObject =
        PluginBrokers.saves?.engineGames(args) ?: super.libraryEngineGames(args)

    private val pluginVault by lazy { PluginVault.forContext(appContext) }

    override fun vault(): PluginVault = pluginVault

    override fun sharedSites(pluginId: String, declared: List<String>): Map<String, String> =
        runCatching { DownloadSites.sharedWith(appContext, pluginId, declared) }.getOrDefault(emptyMap())

    override fun renewSharedSites(cookies: Map<String, String>) {
        cookies.forEach { (domain, header) -> DownloadSites.renew(appContext, domain, header) }
    }

    override fun gamesRoots(): List<String> = dev.droidtop.library.settings.LibraryPaths.roots()

    override fun libraryFilesChanged(pluginId: String, change: dev.droidtop.library.settings.PathChange): Boolean =
        dev.droidtop.library.settings.LibraryPaths.report(appContext, change, source = pluginId) != null

    override fun socialChanged(pluginId: String, change: JSONObject): Boolean = PluginBrokers.socialChanged(pluginId, change)

    override fun enginesChanged(pluginId: String): Boolean = PluginBrokers.enginesChanged(pluginId)

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

    override fun roleTokens(): dev.droidtop.library.theme.RoleTokens.Set = dev.droidtop.library.theme.RoleTokens.current(appContext)

    override fun powerState(): PowerState {
        // The battery broadcast is sticky: registering no receiver reads its last value.
        val battery = appContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val present = battery?.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, false) == true
        val level = if (!present) null else HostPowerApis.level(
            battery!!.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1),
            battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1),
        )
        val plugged = battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = plugged != 0 || status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
        val saver = appContext.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode == true
        return PowerState(level, charging, saver)
    }

    override fun pairedDevices(pluginId: String): List<dev.droidtop.net.peer.PairedDevices.Device> =
        dev.droidtop.net.peer.PairedDevices.forPlugin(appContext, pluginId)

    override fun announceToDevices(pluginId: String, value: JSONObject?) {
        dev.droidtop.net.peer.PairedDevices.problem(pluginId, value)?.let { throw BrokerException(PluginErrorCode.INVALID_ARGS, it) }
        // Reaching every paired device can take far longer than a call may: the value is kept and sent on its own thread.
        Thread({ runCatching { dev.droidtop.net.peer.PairedDevices.announce(appContext, pluginId, value) } }, "devices-announce").start()
    }

    override fun netLinks(): List<NetLinks.Link> {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return emptyList()
        val active = cm.activeNetwork
        @Suppress("DEPRECATION") // allNetworks is the one call that lists every network up, not only the default.
        val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(listOfNotNull(active))
        return networks.mapNotNull { network ->
            val props = cm.getLinkProperties(network) ?: return@mapNotNull null
            val caps = cm.getNetworkCapabilities(network)
            val type = when {
                caps == null -> "other"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            NetLinks.Link(
                name = props.interfaceName.orEmpty(),
                type = type,
                active = network == active,
                metered = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
                addresses = props.linkAddresses.mapNotNull { la -> la.address.hostAddress?.let { NetLinks.Address(it.substringBefore('%'), la.prefixLength) } },
                dns = props.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') },
                searchDomains = props.domains?.split(' ', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            )
        }
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

    override fun github(call: HttpCall): HttpAnswer {
        val ms = call.timeoutMs.toInt()
        val token = dev.droidtop.net.GitHubTokenStore.get(appContext)
        val connection = dev.droidtop.net.GitHubAuth.open(call.url, token, ms, ms, call.headers, everyHop = true)
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
            // The download engine fetches it like every other download (docs/SPEC.md 12a "One download engine"), into
            // droidtop's downloads area, and only a whole file is moved into the plugin's data. Every address, the
            // redirects included, is put to the plugin's `net.domains` check before the engine is given it.
            val area = DownloadJobs.fileFor(appContext, "plugin_${System.nanoTime()}_" + file.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trimStart('.', '_', '-').take(80).ifEmpty { "file" })
            try {
                DownloadJobs.fetchWithEngine(
                    appContext, url, emptyMap(), area, PluginDataStore.LIMIT_BYTES, DownloadJobs.TransferNumbers(JobHandle.current()),
                    { percent, line, _ -> PluginJobsCenter.progressBrokered(jobId, percent, line) },
                    allow,
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    store.requireRoom(area.length(), file)
                    file.parentFile?.mkdirs()
                    DownloadJobs.moveFile(area, file)
                }
                PluginReply.ok(JSONObject().put("name", name).put("size", file.length()))
            } catch (e: BrokerException) {
                PluginReply.error(e.code, e.message.orEmpty())
            } catch (e: IllegalStateException) {
                PluginReply.error(PluginErrorCode.FAILED, e.message ?: "the download failed")
            } catch (e: java.io.IOException) {
                PluginReply.error(PluginErrorCode.FAILED, e.message ?: "the download failed")
            } finally {
                area.delete()
                dev.droidtop.net.engine.TransferRequest.controlFile(area).delete()
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

    override fun hiddenWebSession(request: WebSessionRequest, expiredText: List<String>): HiddenWebSession.Outcome? =
        HiddenWebSession.open(appContext, request, expiredText)

    override fun webFetch(url: String, cookies: String?, timeoutMs: Long, maxBytes: Int): WebFetch.Result? =
        WebFetch.fetch(appContext, url, cookies, timeoutMs, maxBytes)

    override fun openDocument(uri: String, mode: String): android.os.ParcelFileDescriptor? = runCatching {
        val resolverMode = when (mode) {
            "w" -> "wt"
            "a" -> "wa"
            "rw" -> "rw"
            else -> "r"
        }
        appContext.contentResolver.openFileDescriptor(android.net.Uri.parse(uri), resolverMode)
    }.getOrNull()

    override fun sharedFilesAllowed(): Boolean = SharedFiles.allowed(appContext)

    override fun storageRoots(): Map<String, java.io.File> = SharedFiles.roots(appContext)

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

    override fun apkInfo(file: java.io.File): ApkInfo? = runCatching {
        val pm = appContext.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        val app = info.applicationInfo ?: return null
        // The archive's own resources name it; without these two the label is read from nowhere.
        app.sourceDir = file.absolutePath
        app.publicSourceDir = file.absolutePath
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        ApkInfo(info.packageName, pm.getApplicationLabel(app).toString().ifBlank { info.packageName }, info.versionName, versionCode)
    }.getOrNull()

    override fun installRefusal(packageName: String): String? {
        if (packageName == appContext.packageName) return "droidtop does not replace itself through a plugin."
        val home = runCatching {
            appContext.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()
        if (packageName == home) return "$packageName is the home screen in use."
        val system = runCatching {
            val flags = appContext.packageManager.getApplicationInfo(packageName, 0).flags
            flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(false)
        if (system) return "$packageName is part of the system."
        return null
    }

    override fun stageApk(file: java.io.File): java.io.File? = runCatching {
        PrivInstall.stage(file, java.io.File(appContext.cacheDir, "priv-install"), System.currentTimeMillis())
    }.getOrNull()

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
     * :app sets this at start: the library's save model and engine games for an app interface plugin (docs/plugin-api.md
     * 3 A7a), which live in :library-core above this module. Called on a binder thread, never the main thread.
     */
    @Volatile var saves: SavesExchange? = null

    /**
     * :app sets this at start: a social provider's `social.changed` (docs/plugin-api.md 3 C19), handed to the library's
     * social hub, which lives in :library-core above this module. Called on a binder thread; it must not block.
     */
    @Volatile var socialChanged: (pluginId: String, change: JSONObject) -> Boolean = { _, _ -> false }

    /**
     * :app sets this at start: an app's plugin connected through the door or said its engine core list changed
     * (docs/plugin-api.md 3 A7b), handed to the library's core list in :library-core. Called on a binder thread or the
     * door's own scope; it must not block.
     */
    @Volatile var enginesChanged: (pluginId: String) -> Boolean = { false }

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

/** How long a plugin's confirm waits for the person before it counts as Cancel. */
private const val CONFIRM_TIMEOUT_MS = 120_000L

/** What :library-core answers for `saves` and `library.read` `list_engine_games` (docs/plugin-api.md 3 A7a). */
interface SavesExchange {
    fun where(pluginId: String, args: JSONObject): JSONObject

    fun changed(pluginId: String, args: JSONObject): JSONObject

    fun engineGames(args: JSONObject): JSONObject
}
