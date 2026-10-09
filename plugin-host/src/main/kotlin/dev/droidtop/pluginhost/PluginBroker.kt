package dev.droidtop.pluginhost

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** What the user answered on the first-use sheet (docs/plugin-api.md 4.3). */
enum class GrantAnswer { ALLOW, NOT_NOW, NEVER }

/** What the first-use sheet shows: "<plugin> wants to <permission label>", the plugin's own reason and, for a provider's permission, the provider. */
data class GrantPromptRequest(
    val pluginId: String,
    val pluginLabel: String,
    val permission: String,
    val permissionLabel: String,
    val reason: String?,
    val tier: PermissionTier,
)

/** Thrown inside the broker to end a call with one of the closed error codes. */
class BrokerException(val code: PluginErrorCode, message: String) : Exception(message)

/**
 * Everything the broker needs from droidtop's side, so [BrokerCore] holds
 * the rules and the tests can fake the rest. The production implementation
 * is [AppBrokerEnvironment]. Calls here may block: the broker runs on a
 * binder thread, never on the main thread.
 */
interface BrokerEnvironment {
    fun nowMs(): Long
    fun record(pluginId: String): PluginRecord?
    fun resolution(): ApiResolution
    fun providerChoice(api: String): String?
    fun grants(pluginId: String): PluginGrants.Snapshot
    fun setGrant(pluginId: String, permission: String, state: GrantState)
    fun noteWanted(pluginId: String, permission: String)
    fun audit(pluginId: String, entry: AuditEntry)

    /** True while a user-initiated host to plugin call is in flight for [pluginId]: only then may a call in `ask` state show the sheet. */
    fun userInitiated(pluginId: String): Boolean

    /** Milliseconds left of the host to plugin call [pluginId] is serving, or null when it is serving none. */
    fun remainingMs(pluginId: String): Long?
    fun prompt(request: GrantPromptRequest): GrantAnswer
    fun isOfficial(origin: String): Boolean
    fun trustBadge(origin: String): String
    fun installId(pluginId: String): String

    /** droidtopVersion, mode and abis, for `host.info`. */
    fun hostFacts(): JSONObject
    fun appInstalled(packageName: String): Boolean
    fun launchApp(packageName: String): Boolean
    fun launchAppWithExtras(packageName: String, extras: Map<String, String>, action: String?): Boolean

    /**
     * Hands [uri] (already checked by [AppLinks]) to another app through Android's own chooser, titled [title]. Null when the
     * chooser was shown; otherwise the plain reason it was not ("no app opens magnet links").
     */
    fun openLink(uri: String, title: String?): String?

    /** Shows [text] as a short message attributed to [pluginLabel], on whatever surface is in front; false when it could not. */
    fun toast(pluginLabel: String, text: String): Boolean

    /**
     * The systems the library holds games for, with each one's chosen emulator, for `library.read` `systems`
     * (docs/plugin-api.md 3 A1): `{ready, systems: [...]}`, read from the in-memory index. May block briefly; never walks a folder.
     */
    fun librarySystems(): JSONObject

    /** The folders a `library.files` `changed` report may name, as absolute paths: the person's game folders. */
    fun gamesRoots(): List<String> = emptyList()

    /**
     * A plugin reports files it added, removed or changed in the person's game folders (`library.files` `changed`,
     * docs/plugin-api.md 3 A3): droidtop indexes exactly those, off this thread, and walks nothing. True when the report was taken.
     */
    fun libraryFilesChanged(pluginId: String, change: dev.droidtop.library.settings.PathChange): Boolean = false

    /**
     * A `social.provider` plugin says its friends or a conversation changed (`social.changed`, docs/plugin-api.md 3 C19):
     * droidtop asks it again off this thread. True when the change was taken. The broker checks the point first.
     */
    fun socialChanged(pluginId: String, change: JSONObject): Boolean = false

    /** The per-plugin secret store behind `vault` (docs/plugin-api.md 3 G1); null where there is none (the call is UNSUPPORTED). */
    fun vault(): PluginVault? = null

    /** Whether droidtop holds [need] now (or this Android version has no such permission). */
    fun holdsAndroid(need: AndroidNeed): Boolean = true

    /** Shows Android's own prompt for [need] and waits for the answer; true when droidtop holds it afterwards. Only called during a call the person started. */
    fun requestAndroid(need: AndroidNeed): Boolean = false

    /** C6: posts a notification in [pluginId]'s own channel, named [pluginLabel]; false when it could not. */
    fun notify(pluginId: String, pluginLabel: String, title: String, text: String): Boolean =
        throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop posts no notifications for plugins")

    /**
     * B (docs/plugin-api.md 3, `retroarch.command`): sends one command line to RetroArch on this device
     * ([RetroArchCommands]); with [replyMs] above zero, RetroArch's answer, or null when none came in time.
     */
    fun retroArchCommand(line: String, replyMs: Long): String? =
        throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop sends no commands to RetroArch")

    /** D1 (docs/plugin-api.md 3): `{online, type, metered, vpn}`. */
    fun netState(): JSONObject = throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop reports no network state")

    /** The addresses [host] resolves to; asked only once the plugin may reach [host]. */
    fun addressesOf(host: String): List<java.net.InetAddress> = emptyList()

    /** D2: runs [call], passing every URL it is about to connect to (the first and each redirect) to [allow], which throws to stop it. */
    fun http(call: HttpCall, allow: (String) -> Unit): HttpAnswer =
        throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop makes no requests for plugins")

    /** D2: downloads [url] into [file] of the plugin's [store] as a job owned by [record]; the job id, or null when it could not start. */
    fun startDownload(record: PluginRecord, url: String, file: java.io.File, store: PluginDataStore, allow: (String) -> Unit): String? = null

    /** H1: the plugin's own data folder. */
    fun dataStore(pluginId: String): PluginDataStore? = null

    /** Plugin contexts kept in step with the person's paired computers (docs/plugin-api.md 3 F8); null in a host without them. */
    fun contexts(): PluginContexts? = null

    /** D4: the tokens of the documents each plugin was handed. */
    fun fileTokens(): PluginFileTokens? = null

    /** D4: shows Android's document picker over droidtop and waits; null when the person backed out. Only during a call the person started. */
    fun pickDocument(pluginLabel: String, mode: String, mime: String, name: String?): PickedDocument? = null

    /** docs/plugin-api.md 3 G3: droidtop's own web view for a plugin's session; blocks until the person is done. Null where there is none. */
    fun webSession(request: WebSessionRequest): WebSessionResult? = null

    /** D4: opens a picked document (`r`, `w`, `rw`); null when it is gone or its grant was withdrawn. */
    fun openDocument(uri: String, mode: String): android.os.ParcelFileDescriptor? = null

    /** D5: whether droidtop itself has all-files access, which every shared-files op runs under. */
    fun sharedFilesAllowed(): Boolean = false

    /** D3, D5: each mounted storage volume's root folder by id, the primary one first. */
    fun storageRoots(): Map<String, java.io.File> = emptyMap()

    /** D3: `[{id, label, primary, removable, state, freeBytes, totalBytes}]`. */
    fun storageVolumes(): JSONArray = JSONArray()

    /** The chain of plugins the call [pluginId] is currently serving came through (empty when it serves none). */
    fun chainServedBy(pluginId: String): List<String>

    /**
     * Delivers [call] to [provider]'s `handle` and waits: a provider that misses [timeoutMs] is treated as crashed and the
     * reply is TIMEOUT. [personStarted] is true when droidtop's own code calls for something the person just did (Quit to
     * Library); then the provider's first-use sheets may show, its full-access one included.
     */
    fun forward(provider: PluginRecord, call: PluginCall, timeoutMs: Long, personStarted: Boolean = false): PluginReply

    /** Starts a provider op that is a job, owned by [caller]; returns the job id or null when it could not start. */
    fun startBrokeredJob(caller: PluginRecord, provider: PluginRecord, call: PluginCall): String?
    fun brokeredJobStatus(caller: PluginRecord, jobId: String): PluginReply

    /** Asks [caller]'s own job [jobId] to stop (a provider's job or a host download); false when it has no such running job. */
    fun cancelBrokeredJob(caller: PluginRecord, jobId: String): Boolean = false

    /** Records the privilege level [provider] holds right now for its export of [api] ([ProviderLevels]); false when not kept. */
    fun reportProviderLevel(provider: PluginRecord, api: String, level: String): Boolean = false
}

/** At most [perHour] uses per key in any hour (docs/plugin-api.md 8: notifications). */
class HourlyQuota(private val perHour: Int) {
    private val uses = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryTake(key: String, nowMs: Long): Boolean {
        val times = uses.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && nowMs - times.first() >= 60L * 60 * 1000) times.removeFirst()
        if (times.size >= perHour) return false
        times.addLast(nowMs)
        return true
    }
}

/** A refill-over-time limiter: 50 calls at once, 10 per second sustained (docs/plugin-api.md 8). */
class TokenBucket(private val capacity: Int, private val refillPerSec: Double, private val clock: () -> Long) {
    private var tokens = capacity.toDouble()
    private var last = clock()

    @Synchronized
    fun tryTake(): Boolean {
        val now = clock()
        tokens = minOf(capacity.toDouble(), tokens + (now - last) / 1000.0 * refillPerSec)
        last = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

/**
 * An Android permission droidtop itself must hold to run an op for a plugin (docs/plugin-api.md 4.1, "Android
 * permissions"), from Android version [fromSdk] on: below it the permission does not exist and nothing is asked.
 */
data class AndroidNeed(val permission: String, val fromSdk: Int = 0)

/** One host API op a plugin may call (docs/plugin-api.md 3). [permission] is null for an op every plugin may call. */
class HostOp(
    val api: String,
    val op: String,
    val versions: Set<Int> = setOf(1),
    val permission: String? = null,
    /**
     * For an op whose permission depends on its arguments: the id to check, given what the plugin declared. `net.http`
     * needs `net.local` for a device on the local network, `net.domains` for a declared domain and `net.any` otherwise.
     * Wins over [permission].
     */
    val permissionFor: ((declared: List<DeclaredPermission>, args: JSONObject, env: BrokerEnvironment) -> String)? = null,
    /** Returns why the arguments do not fit what the plugin declared for [permission], or null. */
    val scope: ((declared: DeclaredPermission, args: JSONObject) -> String?)? = null,
    /** A one-line target summary for the audit log (a package, a domain, a file name; never contents). */
    val target: (args: JSONObject) -> String = { "" },
    /** Only during a call the person started: the op shows something of the system's (a picker, a prompt). */
    val userOnly: Boolean = false,
    /** Written to the activity log whatever the permission's tier: everything that leaves the device or touches shared files. */
    val alwaysAudit: Boolean = false,
    /** The Android permission droidtop needs for this op; asked of Android on first use during a call the person started. */
    val android: AndroidNeed? = null,
    /** For an op that hands the plugin a file ([IPluginHostBroker.open]) instead of JSON. */
    val open: ((env: BrokerEnvironment, record: PluginRecord, args: JSONObject) -> android.os.ParcelFileDescriptor)? = null,
    val exec: ((env: BrokerEnvironment, record: PluginRecord, args: JSONObject) -> JSONObject)? = null,
)

/** The host's own APIs. Everything a plugin can do beyond its own process goes through one of these or through a provider. */
object HostApis {
    const val MAX_PACKAGES = 50
    const val MAX_LINK_TITLE = 60

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun packages(args: JSONObject): List<String> {
        val list = args.optJSONArray("packages")
        val names = if (list != null) List(list.length()) { list.optString(it) } else listOfNotNull(args.optString("package").takeIf { it.isNotBlank() })
        if (names.isEmpty() || names.size > MAX_PACKAGES || names.any { it.isBlank() }) invalid("packages must list 1 to $MAX_PACKAGES package names")
        return names
    }

    /** The most paths one `library.files` `changed` call may name, and the longest one. */
    const val MAX_CHANGED_PATHS = 200
    const val MAX_PATH_LENGTH = 4096

    private fun filesChange(args: JSONObject): dev.droidtop.library.settings.PathChange {
        fun list(name: String): List<String> {
            val array = args.optJSONArray(name) ?: if (args.has(name)) invalid("$name must be a list of paths") else return emptyList()
            return List(array.length()) { index ->
                (array.opt(index) as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_PATH_LENGTH }
                    ?: invalid("$name must hold only paths of at most $MAX_PATH_LENGTH characters")
            }
        }
        val change = dev.droidtop.library.settings.PathChange(list("added"), list("removed"), list("changed"))
        val count = change.added.size + change.removed.size + change.changed.size
        if (count == 0) invalid("name at least one path in added, removed or changed")
        if (count > MAX_CHANGED_PATHS) invalid("name at most $MAX_CHANGED_PATHS paths in one call")
        return change
    }

    private fun declaredPackages(declared: DeclaredPermission): Set<String> {
        val extra = runCatching { JSONObject(declared.extra) }.getOrDefault(JSONObject())
        val list = extra.optJSONArray("packages") ?: return emptySet()
        return buildSet { for (i in 0 until list.length()) add(list.optString(i)) }
    }

    private fun scopeAny(declared: DeclaredPermission): Boolean =
        runCatching { JSONObject(declared.extra).optString("scope") }.getOrDefault("") == "any"

    private fun packageScope(declared: DeclaredPermission, args: JSONObject): String? {
        if ("*" in declaredPackages(declared) || scopeAny(declared)) return null
        val allowed = declaredPackages(declared)
        val outside = packages(args).firstOrNull { it !in allowed }
        return outside?.let { "$it is not in the package list this plugin declared" }
    }

    private val core: List<HostOp> = listOf(
        HostOp("host", "info") { env, record, _ ->
            val points = JSONObject()
            ExtensionPoints.all.forEach { points.put(it.id, JSONArray(it.versions.sorted())) }
            val apis = JSONObject()
            all().groupBy { it.api }.forEach { (api, list) -> apis.put(api, JSONArray(list.flatMap { it.versions }.distinct().sorted())) }
            val facts = env.hostFacts()
            // One spelling of a mode everywhere a plugin meets one (docs/plugin-api.md 1.9): `standard`, never `android`.
            PluginModes.canonical(facts.optString("mode"))?.let { facts.put("mode", it) }
            facts
                .put("contract", PLUGIN_CONTRACT_VERSION)
                .put("points", points)
                .put("apis", apis)
                .put("installId", env.installId(record.manifest.id))
        },
        HostOp("plugins", "available") { env, record, args ->
            val api = args.optString("api").takeIf { it.isNotBlank() } ?: invalid("api is required")
            val minLevel = args.optString("minLevel").takeIf { it.isNotBlank() }
                ?: record.manifest.v2.requires.firstOrNull { it.api == api }?.minLevel
            val provider = PluginApiResolver.providerOf(env.resolution(), api, minLevel, env.providerChoice(api))
                ?.takeIf { it.plugin.manifest.id != record.manifest.id }
            val out = JSONObject().put("available", provider != null)
            if (provider != null) {
                // Only the provider's label, never its id.
                out.put("version", provider.export.version)
                    .put("attributes", runCatching { JSONObject(provider.export.attributes) }.getOrDefault(JSONObject()))
                    .put("label", provider.plugin.manifest.label)
            }
            out
        },
        // A provider says which privilege level it holds right now (docs/plugin-api.md 2.7): its root-level export is offered
        // only while it reports "root". Only for an API it exports at a level it declared; it grants nothing by itself.
        HostOp("plugins", "report_level") { env, record, args ->
            val api = args.optString("api").takeIf { it.isNotBlank() } ?: invalid("api is required")
            val level = args.optString("level").takeIf { it in setOf("none", "adb", "root") } ?: invalid("level is none, adb or root")
            val declared = record.manifest.v2.exports.filter { it.api == api }.map { it.level }
            if (declared.isEmpty()) invalid("this plugin does not export $api")
            if (level != "none" && level !in declared) invalid("$api is not exported at level $level")
            JSONObject().put("recorded", env.reportProviderLevel(record, api, level))
        },
        HostOp("plugins", "job_status") { env, record, args ->
            val id = args.optString("jobId").takeIf { it.isNotBlank() } ?: invalid("jobId is required")
            val reply = env.brokeredJobStatus(record, id)
            if (!reply.ok) throw BrokerException(reply.code ?: PluginErrorCode.FAILED, reply.message.orEmpty())
            reply.data
        },
        // A job the plugin started through the broker (a provider's job op, a `net.download`), stopped at its next step.
        HostOp("plugins", "job_cancel") { env, record, args ->
            val id = args.optString("jobId").takeIf { it.isNotBlank() } ?: invalid("jobId is required")
            JSONObject().put("cancelled", env.cancelBrokeredJob(record, id))
        },
        HostOp(
            "apps", "check",
            permission = "apps.check",
            scope = { declared, args -> packageScope(declared, args) },
            target = { runCatching { packages(it).joinToString(",") }.getOrDefault("") },
        ) { env, _, args ->
            val installed = JSONObject()
            packages(args).forEach { installed.put(it, env.appInstalled(it)) }
            JSONObject().put("installed", installed)
        },
        HostOp(
            "apps", "launch",
            permission = "apps.launch",
            target = { it.optString("package") },
        ) { env, _, args ->
            val pkg = args.optString("package").takeIf { it.isNotBlank() } ?: invalid("package is required")
            JSONObject().put("launched", env.launchApp(pkg))
        },
        // docs/plugin-api.md 3 F2: a link handed to another app (a magnet to a torrent app, a page to the browser). droidtop
        // builds the intent and shows Android's chooser; the plugin never sees an Intent. Only during a call the person started.
        HostOp(
            "apps", "view",
            permission = "apps.view",
            userOnly = true,
            alwaysAudit = true,
            target = { AppLinks.target(it.optString("uri")) },
        ) { env, _, args ->
            val uri = args.optString("uri").trim()
            AppLinks.refusal(uri)?.let { invalid(it) }
            val title = args.optString("title").trim().take(MAX_LINK_TITLE).takeIf { it.isNotEmpty() }
            val reason = env.openLink(uri, title)
            JSONObject().put("opened", reason == null).also { if (reason != null) it.put("reason", reason) }
        },
        HostOp(
            "apps", "intent",
            permission = "apps.intents.out",
            scope = { declared, args ->
                if (scopeAny(declared) || "*" in declaredPackages(declared)) {
                    null
                } else {
                    val pkg = args.optString("package")
                    if (pkg in declaredPackages(declared)) null else "$pkg is not in the package list this plugin declared"
                }
            },
            target = { it.optString("package") },
        ) { env, _, args ->
            val pkg = args.optString("package").takeIf { it.isNotBlank() } ?: invalid("package is required")
            val extrasJson = args.optJSONObject("extras") ?: JSONObject()
            val extras = buildMap { extrasJson.keys().forEach { put(it, extrasJson.optString(it)) } }
            val action = args.optString("action").takeIf { it.isNotBlank() }
            JSONObject().put("launched", env.launchAppWithExtras(pkg, extras, action))
        },
        // docs/plugin-api.md 3 C6a: the Decky toaster's job, drawn by droidtop and always named after the plugin.
        HostOp(
            "ui.toast", "show",
            permission = "overlay.toast",
        ) { env, record, args ->
            val text = args.optString("text").trim().takeIf { it.isNotEmpty() } ?: invalid("text is required")
            JSONObject().put("shown", env.toast(record.manifest.label, text.take(MAX_TOAST)))
        },
        // docs/plugin-api.md 3 C6: a notification in the plugin's own channel, posted by droidtop under its own Android
        // permission (asked of Android on first use from a call the person started), at most 5 an hour.
        HostOp(
            "notify", "post",
            permission = "notify.post",
            android = PluginPermissions.find("notify.post")?.android,
            target = { it.optString("title").take(60) },
        ) { env, record, args ->
            val title = args.optString("title").trim().take(MAX_NOTIFY_TITLE).takeIf { it.isNotEmpty() } ?: invalid("title is required")
            val text = args.optString("text").trim().take(MAX_NOTIFY_TEXT)
            if (!notifyQuota.tryTake(record.manifest.id, env.nowMs())) throw BrokerException(PluginErrorCode.RATE_LIMITED, "at most $NOTIFY_PER_HOUR notifications an hour")
            JSONObject().put("posted", env.notify(record.manifest.id, record.manifest.label, title, text))
        },
        // docs/plugin-api.md 3 C19: a social provider with a live connection says something changed, so droidtop never
        // polls it. Only a plugin that provides social.provider, with that point still on, may say so.
        HostOp(
            "social", "changed",
            target = { it.optString("friendId") },
        ) { env, record, args ->
            val point = "social.provider"
            PluginGrants.pointRefusal(record, env.grants(record.manifest.id), point)?.let {
                throw BrokerException(PluginErrorCode.PERMISSION_DENIED, it)
            }
            if (args.has("friendId") && args.optString("friendId").isBlank()) invalid("friendId must not be blank")
            if (args.has("message") && args.optJSONObject("message") == null) invalid("message must be an object")
            JSONObject().put("accepted", env.socialChanged(record.manifest.id, args))
        },
        // docs/plugin-api.md 3 B: a command to the RetroArch running the game (save, load, slot, shader, fast-forward), sent by
        // droidtop to the loopback address only, then RetroArch's status, so the plugin can say whether it answered.
        HostOp(
            "retroarch", "command",
            permission = "retroarch.commands",
            target = { it.optString("command") },
        ) { env, _, args ->
            val command = args.optString("command").trim()
            if (command !in RetroArchCommands.ALLOWED) invalid("command must be one of ${RetroArchCommands.ALLOWED.sorted().joinToString()}")
            env.retroArchCommand(command, 0)
            retroArchStatus(env).put("sent", true)
        },
        // Whether RetroArch answers, and what it runs, without sending anything else: a panel decides what to show.
        HostOp("retroarch", "status", permission = "retroarch.commands") { env, _, _ -> retroArchStatus(env) },
        // docs/plugin-api.md 3 C15: a recorder says it started or stopped, and the companion's status line shows "Recording"
        // with a timer. Only a plugin whose panel declares the `recording` ability, with that point still on, may say so.
        HostOp("companion", "recording") { env, record, args ->
            PluginGrants.pointRefusal(record, env.grants(record.manifest.id), "ui.panel")?.let {
                throw BrokerException(PluginErrorCode.PERMISSION_DENIED, it)
            }
            if (!CompanionAbilities.declares(record.manifest, CompanionAbilities.RECORDING)) {
                throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${record.manifest.label}'s panel does not declare the recording ability")
            }
            val on = args.opt("on") as? Boolean ?: invalid("on must be true or false")
            val since = if (args.has("sinceMs")) args.optLong("sinceMs", -1L).takeIf { it > 0 } ?: invalid("sinceMs must be a time in milliseconds") else null
            JSONObject().put("changed", PluginRecording.report(record.manifest.id, record.manifest.label, on, since, env.nowMs()))
        },
        // docs/plugin-api.md 3 A3: a plugin that put a file in a game folder (or took one out) says so, and droidtop looks at exactly
        // that, never at the whole library. The grant is the one that lets it write there at all; the paths must be inside the
        // person's game folders (a game folder itself is a rescan, not a report).
        HostOp(
            "library.files", "changed",
            permission = "library.folders.write",
            target = { "${it.optJSONArray("added")?.length() ?: 0} added, ${it.optJSONArray("removed")?.length() ?: 0} removed, ${it.optJSONArray("changed")?.length() ?: 0} changed" },
        ) { env, record, args ->
            val change = filesChange(args)
            val outside = dev.droidtop.library.settings.LibraryPaths.outside(change.added + change.removed + change.changed, env.gamesRoots())
            if (outside.isNotEmpty()) {
                throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${outside.first()} is not inside your game folders")
            }
            JSONObject().put("accepted", env.libraryFilesChanged(record.manifest.id, change))
        },
        // docs/plugin-api.md 3 A1: the user's systems and each one's chosen emulator, so a panel can list every system, not only those it heard about.
        HostOp("library.read", "systems", permission = "library.read") { env, _, _ -> env.librarySystems() },
        // docs/plugin-api.md 3 G1: the caller's own secrets. The broker names the caller, so a plugin can only ever reach its own;
        // the audit and every log carry the key's name at most, never a value.
        HostOp("vault", "put", permission = "vault.own", target = { it.optString("key") }) { env, record, args ->
            val key = args.optString("key")
            val value = if (args.isNull("value")) invalid("value is required") else args.optString("value")
            vaultOf(env).let { vault ->
                try {
                    vault.put(record.manifest.id, key, value)
                } catch (e: PluginVault.Refused) {
                    invalid(e.message.orEmpty())
                }
            }
            JSONObject().put("stored", true)
        },
        HostOp("vault", "get", permission = "vault.own", target = { it.optString("key") }) { env, record, args ->
            val key = args.optString("key").takeIf { PluginVault.validKey(it) } ?: invalid("key is required")
            JSONObject().put("value", vaultOf(env).get(record.manifest.id, key) ?: JSONObject.NULL)
        },
        HostOp("vault", "delete", permission = "vault.own", target = { it.optString("key") }) { env, record, args ->
            val key = args.optString("key").takeIf { PluginVault.validKey(it) } ?: invalid("key is required")
            JSONObject().put("deleted", vaultOf(env).delete(record.manifest.id, key))
        },
        HostOp("vault", "keys", permission = "vault.own") { env, record, _ ->
            JSONObject().put("keys", JSONArray(vaultOf(env).keys(record.manifest.id)))
        },
    )

    /** RetroArch's answer to GET_STATUS as `{answered, state?, system?, content?}`. */
    private fun retroArchStatus(env: BrokerEnvironment): JSONObject {
        val status = RetroArchCommands.parseStatus(env.retroArchCommand("GET_STATUS", RetroArchCommands.STATUS_WAIT_MS))
        return JSONObject().put("answered", status != null).apply {
            if (status != null) {
                put("state", status.state)
                status.system?.let { put("system", it) }
                status.content?.let { put("content", it) }
            }
        }
    }

    /** Every host op: the core ones above, and the groups that live in their own files (docs/plugin-api.md 3 D, H). */
    val ops: List<HostOp> by lazy { core + HostNetApis.ops + HostDataApis.ops + HostFileApis.ops + HostContextApis.ops + HostWebApis.ops }

    private fun vaultOf(env: BrokerEnvironment): PluginVault =
        env.vault() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop keeps no plugin secrets")

    /** The longest toast text droidtop shows; longer text is cut, never refused. */
    const val MAX_TOAST = 200

    const val MAX_NOTIFY_TITLE = 80
    const val MAX_NOTIFY_TEXT = 400
    const val NOTIFY_PER_HOUR = 5

    /** Notifications per plugin in the last hour (docs/plugin-api.md 8). */
    private val notifyQuota = HourlyQuota(NOTIFY_PER_HOUR)

    fun all(): List<HostOp> = ops

    fun find(api: String, op: String): HostOp? = ops.firstOrNull { it.api == api && it.op == op }

    /** True for an API id the host owns: a provider plugin can never take one over. */
    fun isHostApi(api: String): Boolean = ops.any { it.api == api }
}

/**
 * Why [provider] may not serve [api] right now, or null. A `priv.*` or `root.*` API from a source droidtop has not checked
 * needs the provider's own `plugins.export_privileged` grant (docs/plugin-api.md 2.7, 2.8); an official provider does not.
 */
internal fun privilegedExportBlock(env: BrokerEnvironment, provider: PluginRecord, api: String): String? {
    val privileged = api.startsWith("priv.") || api.startsWith("root.")
    if (!privileged || env.isOfficial(provider.manifest.origin)) return null
    val granted = PluginGrants.stateOf(provider, env.grants(provider.manifest.id), "plugins.export_privileged") == GrantState.GRANTED
    return if (granted) null else "${provider.manifest.label} has not been allowed to give other plugins system access"
}

/**
 * droidtop's own code calling a provider plugin (docs/plugin-api.md 2.4), for a feature that is the user's own action
 * inside droidtop: Quit to Library force-stopping an emulator, for one. There is no caller plugin, so no caller grant is
 * checked; everything about the provider still is: it must be running, its export switched on, and a privileged API
 * from an unchecked source needs its `plugins.export_privileged` grant. The call carries `caller: {kind: "host"}` and
 * the provider's side is audited. Only quick ops: a job op is refused here.
 */
class HostApiCaller(private val env: BrokerEnvironment) {
    /** True when a running plugin provides [api] at [version], so a caller can tell "no helper installed" from "the helper failed". */
    fun hasProvider(api: String, version: Int, minLevel: String? = null): Boolean =
        PluginApiResolver.providerFor(env.resolution(), "", RequiredApi(api, "$version.0", optional = true, minLevel = minLevel), env.providerChoice(api)) != null

    /** True when the running provider of [api] exports [op], so a caller offers only what the provider can do. */
    fun hasOp(api: String, version: Int, op: String): Boolean =
        PluginApiResolver.providerFor(env.resolution(), "", RequiredApi(api, "$version.0", optional = true), env.providerChoice(api))
            ?.export?.ops?.any { it.op == op } == true

    /**
     * [personStarted]: the call is for something the person just did in droidtop (Quit to Library, the task manager's
     * End). Only then may a provider that has not been allowed full access ask for it on the first-use sheet; droidtop's
     * own background use of a provider never shows one.
     */
    fun call(api: String, version: Int, op: String, args: JSONObject, personStarted: Boolean = false, minLevel: String? = null): PluginReply {
        // [minLevel]: droidtop's own feature needs this level (the rooted desktop stack asks for "root"); without it the
        // lowest export serves (least privilege, docs/plugin-api.md 2.7).
        val required = RequiredApi(api, "$version.0", optional = true, minLevel = minLevel)
        val provider = PluginApiResolver.providerFor(env.resolution(), "", required, env.providerChoice(api))
            ?: return PluginReply.error(PluginErrorCode.PROVIDER_UNAVAILABLE, "no plugin provides $api")
        val exportedOp = provider.export.ops.firstOrNull { it.op == op }
            ?: return PluginReply.error(PluginErrorCode.UNSUPPORTED, "$api has no op $op")
        if (exportedOp.job) return PluginReply.error(PluginErrorCode.UNSUPPORTED, "$api $op is a job and cannot be called from here")
        val record = env.record(provider.plugin.manifest.id)?.takeIf { it.runnable() }
            ?: return PluginReply.error(PluginErrorCode.PROVIDER_UNAVAILABLE, "${provider.plugin.manifest.label} is not running")
        privilegedExportBlock(env, record, api)?.let { return PluginReply.error(PluginErrorCode.PROVIDER_UNAVAILABLE, it) }
        val timeout = PluginRunner.CALL_TIMEOUT_MS - BrokerCore.PROVIDER_MARGIN_MS
        val call = PluginCall(
            callId = "c-" + UUID.randomUUID().toString().take(8),
            deadlineMs = timeout,
            point = "api:$api",
            version = version,
            op = op,
            caller = JSONObject().put("kind", "host").put("level", provider.export.level),
            args = args,
        )
        val reply = env.forward(record, call, timeout, personStarted)
        val permission = PluginPermissions.find(exportedOp.permission)
        if ((permission == null || permission.tier != PermissionTier.NORMAL) && !BrokerCore.isSessionPlumbing(api, op)) {
            env.audit(
                record.manifest.id,
                AuditEntry(env.nowMs(), exportedOp.permission, api, "served $op for droidtop", args.optString("package"), emptyList(), if (reply.ok) "ok" else (reply.code?.name ?: "FAILED")),
            )
        }
        return reply
    }
}

/** Quit to Library's second way (docs/plugin-api.md 2.7): ask a `priv.packages` provider to force-stop a package. */
object ForceStop {
    sealed interface Result {
        /** The provider ended the package. */
        data object Stopped : Result

        /** No running plugin provides `priv.packages`: nothing was tried. */
        data object NoProvider : Result

        /** A provider tried and could not, or refused: [message] is what it said. */
        data class Failed(val message: String) : Result
    }

    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+\$")

    fun request(caller: HostApiCaller, target: String): Result {
        if (!packageName.matches(target)) return Result.Failed("$target is not a package name")
        if (!caller.hasProvider("priv.packages", 1)) return Result.NoProvider
        // Ending a game is always the person's own action (Quit to Library, the task manager's End).
        val reply = caller.call("priv.packages", 1, "force_stop", JSONObject().put("package", target), personStarted = true)
        return if (reply.ok && reply.data.optBoolean("stopped", true)) Result.Stopped else Result.Failed(reply.message.orEmpty().ifBlank { "it did not say why" })
    }
}

/**
 * The broker for ONE plugin (docs/plugin-api.md 1.4). [pluginId] is fixed at
 * construction: the binder object handed to that plugin's process is the
 * only thing that names the caller, and nothing in a request can. The
 * checks run in the document's order: runnable, API and version, permission
 * declared and granted (with the first-use sheet), parameters within what
 * was declared, quota, then the host executes or forwards to a provider,
 * and an audit entry is written.
 */
class BrokerCore(val pluginId: String, private val env: BrokerEnvironment) {
    private val bucket = TokenBucket(BURST, SUSTAINED_PER_SEC) { env.nowMs() }

    /** Never throws: every failure is an error reply with one of the closed codes. */
    fun call(requestJson: String): String = try {
        handle(requestJson).encode()
    } catch (e: BrokerException) {
        PluginReply.error(e.code, e.message.orEmpty()).encode()
    } catch (t: Throwable) {
        PluginReply.error(PluginErrorCode.FAILED, "the host could not run this call").encode()
    }

    /**
     * [IPluginHostBroker.open]: the same checks in the same order, for a host op that hands the plugin a file. The reply
     * is `{opened: true}` with the descriptor, or the refusal with none. Never throws.
     */
    fun open(requestJson: String): Pair<String, android.os.ParcelFileDescriptor?> = try {
        val request = parse(requestJson)
        val record = runnableRecord()
        if (!bucket.tryTake()) throw BrokerException(PluginErrorCode.RATE_LIMITED, "too many calls")
        val hostOp = HostApis.find(request.api, request.op)
            ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} ${request.op} hands over no file")
        val opener = hostOp.open ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} ${request.op} hands over no file; call it instead")
        val fd = guarded(record, request, hostOp) { opener(env, record, request.args) }
        PluginReply.ok(JSONObject().put("opened", true)).encode() to fd
    } catch (e: BrokerException) {
        PluginReply.error(e.code, e.message.orEmpty()).encode() to null
    } catch (t: Throwable) {
        PluginReply.error(PluginErrorCode.FAILED, "the host could not open this: ${t.message ?: t::class.java.simpleName}").encode() to null
    }

    private class Request(val api: String, val version: Int, val op: String, val args: JSONObject)

    private fun parse(text: String): Request {
        if (text.toByteArray(Charsets.UTF_8).size > PluginRunner.MAX_RESULT_BYTES) {
            throw BrokerException(PluginErrorCode.INVALID_ARGS, "the request exceeds the size cap")
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BrokerException(PluginErrorCode.INVALID_ARGS, "the request is not JSON")
        }
        val api = json.optString("api").takeIf { it.isNotBlank() } ?: throw BrokerException(PluginErrorCode.INVALID_ARGS, "api is required")
        val op = json.optString("op").takeIf { it.isNotBlank() } ?: throw BrokerException(PluginErrorCode.INVALID_ARGS, "op is required")
        return Request(api, json.optInt("version", 1), op, json.optJSONObject("args") ?: JSONObject())
    }

    /** 1. The plugin is runnable: approved, enabled, and not Waiting for a provider. */
    private fun runnableRecord(): PluginRecord {
        val record = env.record(pluginId) ?: throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "this plugin is not installed")
        if (!record.runnable() || env.resolution().isWaiting(pluginId)) {
            throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "this plugin is not running")
        }
        return record
    }

    private fun handle(text: String): PluginReply {
        val request = parse(text)
        val record = runnableRecord()
        // 5. The quota (checked early so a flood costs nothing else).
        if (!bucket.tryTake()) throw BrokerException(PluginErrorCode.RATE_LIMITED, "too many calls")
        val hostOp = HostApis.find(request.api, request.op)
        return when {
            hostOp != null -> {
                val exec = hostOp.exec ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} ${request.op} hands over a file; open it instead")
                PluginReply.ok(guarded(record, request, hostOp) { exec(env, record, request.args) })
            }
            HostApis.isHostApi(request.api) -> throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} has no op ${request.op}")
            else -> callProvider(record, request)
        }
    }

    /**
     * Checks 2 to 4 for a host op, then [run] (6) and the audit entry (7): the version is served; an op that shows
     * something of the system's runs only during a call the person started; the permission the op needs (fixed, or
     * chosen from the arguments) is declared, its parameters fit what was declared, and it is granted (with the
     * first-use sheet); and droidtop itself holds the Android permission the op needs, asking Android when it may.
     */
    private fun <T> guarded(record: PluginRecord, request: Request, hostOp: HostOp, run: () -> T): T {
        // 2. The API and version are supported.
        if (request.version !in hostOp.versions) throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} version ${request.version} is not supported")
        var tier: PermissionTier? = null
        var permission: String? = null
        var result = "ok"
        try {
            if (hostOp.userOnly && !env.userInitiated(pluginId)) {
                throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${request.api} ${request.op} works only while you are using ${record.manifest.label}")
            }
            (hostOp.permissionFor?.invoke(record.manifest.v2.permissions, request.args, env) ?: hostOp.permission)?.let { id ->
                permission = id
                // 3. The permission is declared and granted.
                val declared = record.manifest.v2.permissions.firstOrNull { it.id == id }
                    ?: throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${record.manifest.id} did not declare $id")
                tier = PluginGrants.tierOf(declared)
                // 4. The parameters fit what was declared.
                hostOp.scope?.invoke(declared, request.args)?.let { throw BrokerException(PluginErrorCode.PERMISSION_DENIED, it) }
                requireGrant(record, id, tier!!, declared.reason, PluginPermissions.labelFor(id) ?: id)
            }
            hostOp.android?.let { requireAndroid(record, it) }
            return run()
        } catch (e: BrokerException) {
            result = e.code.name
            throw e
        } catch (t: Throwable) {
            result = PluginErrorCode.FAILED.name
            throw t
        } finally {
            val audited = if (permission != null) tier != null && (tier != PermissionTier.NORMAL || hostOp.alwaysAudit) else hostOp.alwaysAudit
            if (audited) {
                env.audit(pluginId, AuditEntry(env.nowMs(), permission ?: request.api, request.api, request.op, runCatching { hostOp.target(request.args) }.getOrDefault(""), emptyList(), result))
            }
        }
    }

    /**
     * droidtop runs the op under its own Android permission (docs/plugin-api.md 4.1): when it does not hold it yet,
     * Android's own prompt is shown, but only during a call the person started. From the background the call is
     * refused and droidtop asks next time.
     */
    private fun requireAndroid(record: PluginRecord, need: AndroidNeed) {
        if (env.holdsAndroid(need)) return
        if (env.userInitiated(pluginId) && env.requestAndroid(need)) return
        val name = need.permission.removePrefix(AndroidPermissions.PREFIX)
        throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "droidtop needs Android's $name permission for this; it asks the next time you use ${record.manifest.label}")
    }

    /**
     * The grant check with the first-use sheet: `granted` passes, `denied`
     * fails, and `ask` shows the sheet only during a user-initiated call.
     * A call from an event, a schedule or a service fails and leaves a note
     * that the plugin wants this ("Wants <label>" on its row).
     */
    private fun requireGrant(record: PluginRecord, permission: String, tier: PermissionTier, reason: String?, label: String) {
        when (PluginGrants.stateOf(record, env.grants(pluginId), permission)) {
            GrantState.GRANTED -> return
            GrantState.DENIED -> throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "$label is turned off for this plugin")
            GrantState.ASK, null -> {
                if (!env.userInitiated(pluginId)) {
                    env.noteWanted(pluginId, permission)
                    throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "this plugin needs you to allow: $label")
                }
                val answer = env.prompt(GrantPromptRequest(pluginId, record.manifest.label, permission, label, reason, tier))
                when (answer) {
                    GrantAnswer.ALLOW -> env.setGrant(pluginId, permission, GrantState.GRANTED)
                    GrantAnswer.NOT_NOW -> throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "not now")
                    GrantAnswer.NEVER -> {
                        env.setGrant(pluginId, permission, GrantState.DENIED)
                        throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "$label is turned off for this plugin")
                    }
                }
            }
        }
    }

    private fun majorOf(version: String): Int? = version.substringBefore('.').toIntOrNull()

    private fun riskTier(risk: String): PermissionTier = when (risk.lowercase()) {
        "critical" -> PermissionTier.CRITICAL
        "high" -> PermissionTier.DANGEROUS
        else -> PermissionTier.NORMAL
    }

    /** docs/plugin-api.md 2.6: a provider's own permission is never rated below the most dangerous grant the provider itself holds. */
    private fun riskFloor(provider: PluginRecord): PermissionTier {
        val snap = env.grants(provider.manifest.id)
        return provider.manifest.v2.permissions
            .filter { PluginGrants.stateOf(provider, snap, it.id) == GrantState.GRANTED }
            .map { PluginGrants.tierOf(it) }
            .maxByOrNull { it.ordinal } ?: PermissionTier.NORMAL
    }

    private fun callProvider(record: PluginRecord, request: Request): PluginReply {
        val callerId = record.manifest.id
        // 1. The caller declared the requirement.
        val required = record.manifest.v2.requires.firstOrNull { it.api == request.api }
            ?: throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "$callerId did not declare requires ${request.api}")
        // 2. A runnable provider satisfies it, at a version the caller speaks.
        val provider = PluginApiResolver.providerFor(env.resolution(), callerId, required, env.providerChoice(request.api))
            ?: throw BrokerException(PluginErrorCode.PROVIDER_UNAVAILABLE, "no plugin provides ${request.api}")
        val export = provider.export
        if (majorOf(export.version) != request.version) {
            throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} is served at version ${export.version}, not ${request.version}")
        }
        val exportedOp = export.ops.firstOrNull { it.op == request.op }
            ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} has no op ${request.op}")
        // 3. The caller holds a grant of its own for the op's permission.
        val declared = record.manifest.v2.permissions.firstOrNull { it.id == exportedOp.permission }
            ?: throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "$callerId did not declare ${exportedOp.permission}")
        val providerName = provider.plugin.manifest.label
        val provided = export.permissions.firstOrNull { it.id == exportedOp.permission }
        val tier = if (PluginPermissions.find(exportedOp.permission) != null) {
            PluginGrants.tierOf(declared)
        } else {
            listOf(riskTier(provided?.risk ?: "high"), riskFloor(provider.plugin)).maxByOrNull { it.ordinal }!!
        }
        val label = PluginPermissions.labelFor(exportedOp.permission) ?: "$providerName: ${provided?.label ?: exportedOp.permission}"
        var result = "ok"
        val chain = env.chainServedBy(callerId) + callerId
        try {
            requireGrant(record, exportedOp.permission, tier, declared.reason, label)
            // 2 again, from the provider's side: its own export is still allowed.
            val providerRecord = env.record(provider.plugin.manifest.id)
            if (providerRecord == null || !providerRecord.runnable()) {
                throw BrokerException(PluginErrorCode.PROVIDER_UNAVAILABLE, "$providerName is not running")
            }
            privilegedExportBlock(env, providerRecord, request.api)?.let { throw BrokerException(PluginErrorCode.PROVIDER_UNAVAILABLE, it) }
            // The chain is attributed and limited: at most three plugins, no repeats.
            if (providerRecord.manifest.id in chain) throw BrokerException(PluginErrorCode.INVALID_ARGS, "a plugin cannot call itself through a provider")
            if (chain.size + 1 > MAX_CHAIN) throw BrokerException(PluginErrorCode.INVALID_ARGS, "calls between plugins are limited to $MAX_CHAIN deep")
            val callerBlock = JSONObject()
                .put("kind", "plugin")
                .put("id", callerId)
                .put("origin", record.manifest.origin)
                .put("trust", env.trustBadge(record.manifest.origin))
                .put("grants", JSONArray(listOf(exportedOp.permission)))
                .put("via", JSONArray(chain))
                // Which of the provider's exports served the call (docs/plugin-api.md 2.7): a provider offering one API at two
                // levels runs it at this one, the level the caller's grant was checked for.
                .put("level", export.level)
            val remaining = env.remainingMs(callerId) ?: PluginRunner.CALL_TIMEOUT_MS
            val timeout = minOf(PluginRunner.CALL_TIMEOUT_MS - PROVIDER_MARGIN_MS, remaining - PROVIDER_MARGIN_MS).coerceAtLeast(MIN_PROVIDER_MS)
            val call = PluginCall(
                callId = "c-" + UUID.randomUUID().toString().take(8),
                deadlineMs = timeout,
                point = "api:${request.api}",
                version = request.version,
                op = request.op,
                caller = callerBlock,
                args = request.args,
            )
            if (exportedOp.job) {
                val jobId = env.startBrokeredJob(record, providerRecord, call)
                    ?: throw BrokerException(PluginErrorCode.PROVIDER_UNAVAILABLE, "$providerName could not start the job")
                return PluginReply.ok(JSONObject().put("jobId", jobId))
            }
            val reply = env.forward(providerRecord, call, timeout)
            if (!reply.ok) result = reply.code?.name ?: PluginErrorCode.FAILED.name
            return reply
        } catch (e: BrokerException) {
            result = e.code.name
            throw e
        } finally {
            if (tier != PermissionTier.NORMAL && !isSessionPlumbing(request.api, request.op)) {
                val now = env.nowMs()
                env.audit(callerId, AuditEntry(now, exportedOp.permission, request.api, request.op, "", listOf(provider.plugin.manifest.id), result))
                env.audit(provider.plugin.manifest.id, AuditEntry(now, exportedOp.permission, request.api, "served ${request.op} for $callerId", "", chain, result))
            }
        }
    }

    companion object {
        /**
         * The I/O of a `priv.shell` stream session (docs/plugin-api.md 2.7): the session's start (`exec_stream`, with its
         * argv) and its end (`stream_kill`) are logged like any privileged call, the reads and writes in between are not,
         * or a running session would push everything else out of the activity log. Fixed by droidtop for the standard
         * interface; a provider cannot mark its own ops unlogged.
         */
        fun isSessionPlumbing(api: String, op: String): Boolean = api == "priv.shell" && (op == "stream_read" || op == "stream_write")

        const val BURST = 50
        const val SUSTAINED_PER_SEC = 10.0
        const val MAX_CHAIN = 3
        const val PROVIDER_MARGIN_MS = 1_000L
        const val MIN_PROVIDER_MS = 500L
    }
}
