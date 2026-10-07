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

    /** Shows [text] as a short message attributed to [pluginLabel], on whatever surface is in front; false when it could not. */
    fun toast(pluginLabel: String, text: String): Boolean

    /**
     * The systems the library holds games for, with each one's chosen emulator, for `library.read` `systems`
     * (docs/plugin-api.md 3 A1): `{ready, systems: [...]}`, read from the in-memory index. May block briefly; never walks a folder.
     */
    fun librarySystems(): JSONObject

    /** The chain of plugins the call [pluginId] is currently serving came through (empty when it serves none). */
    fun chainServedBy(pluginId: String): List<String>

    /** Delivers [call] to [provider]'s `handle` and waits: a provider that misses [timeoutMs] is treated as crashed and the reply is TIMEOUT. */
    fun forward(provider: PluginRecord, call: PluginCall, timeoutMs: Long): PluginReply

    /** Starts a provider op that is a job, owned by [caller]; returns the job id or null when it could not start. */
    fun startBrokeredJob(caller: PluginRecord, provider: PluginRecord, call: PluginCall): String?
    fun brokeredJobStatus(caller: PluginRecord, jobId: String): PluginReply
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

/** One host API op a plugin may call (docs/plugin-api.md 3). [permission] is null for an op every plugin may call. */
class HostOp(
    val api: String,
    val op: String,
    val versions: Set<Int> = setOf(1),
    val permission: String? = null,
    /** Returns why the arguments do not fit what the plugin declared for [permission], or null. */
    val scope: ((declared: DeclaredPermission, args: JSONObject) -> String?)? = null,
    /** A one-line target summary for the audit log (a package, never contents). */
    val target: (args: JSONObject) -> String = { "" },
    val exec: (env: BrokerEnvironment, record: PluginRecord, args: JSONObject) -> JSONObject,
)

/** The host's own APIs. Everything a plugin can do beyond its own process goes through one of these or through a provider. */
object HostApis {
    const val MAX_PACKAGES = 50

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun packages(args: JSONObject): List<String> {
        val list = args.optJSONArray("packages")
        val names = if (list != null) List(list.length()) { list.optString(it) } else listOfNotNull(args.optString("package").takeIf { it.isNotBlank() })
        if (names.isEmpty() || names.size > MAX_PACKAGES || names.any { it.isBlank() }) invalid("packages must list 1 to $MAX_PACKAGES package names")
        return names
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

    val ops: List<HostOp> = listOf(
        HostOp("host", "info") { env, record, _ ->
            val points = JSONObject()
            ExtensionPoints.all.forEach { points.put(it.id, JSONArray(it.versions.sorted())) }
            val apis = JSONObject()
            all().groupBy { it.api }.forEach { (api, list) -> apis.put(api, JSONArray(list.flatMap { it.versions }.distinct().sorted())) }
            env.hostFacts()
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
        HostOp("plugins", "job_status") { env, record, args ->
            val id = args.optString("jobId").takeIf { it.isNotBlank() } ?: invalid("jobId is required")
            val reply = env.brokeredJobStatus(record, id)
            if (!reply.ok) throw BrokerException(reply.code ?: PluginErrorCode.FAILED, reply.message.orEmpty())
            reply.data
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
        // docs/plugin-api.md 3 A1: the user's systems and each one's chosen emulator, so a panel can list every system, not only those it heard about.
        HostOp("library.read", "systems", permission = "library.read") { env, _, _ -> env.librarySystems() },
    )

    /** The longest toast text droidtop shows; longer text is cut, never refused. */
    const val MAX_TOAST = 200

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
    fun hasProvider(api: String, version: Int): Boolean =
        PluginApiResolver.providerFor(env.resolution(), "", RequiredApi(api, "$version.0", optional = true), env.providerChoice(api)) != null

    fun call(api: String, version: Int, op: String, args: JSONObject): PluginReply {
        val required = RequiredApi(api, "$version.0", optional = true)
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
            caller = JSONObject().put("kind", "host"),
            args = args,
        )
        val reply = env.forward(record, call, timeout)
        val permission = PluginPermissions.find(exportedOp.permission)
        if (permission == null || permission.tier != PermissionTier.NORMAL) {
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
        val reply = caller.call("priv.packages", 1, "force_stop", JSONObject().put("package", target))
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

    private fun handle(text: String): PluginReply {
        val request = parse(text)
        // 1. The plugin is runnable.
        val record = env.record(pluginId) ?: throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "this plugin is not installed")
        if (!record.runnable() || env.resolution().isWaiting(pluginId)) {
            throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "this plugin is not running")
        }
        // 5. The quota (checked early so a flood costs nothing else).
        if (!bucket.tryTake()) throw BrokerException(PluginErrorCode.RATE_LIMITED, "too many calls")
        val hostOp = HostApis.find(request.api, request.op)
        return when {
            hostOp != null -> callHost(record, request, hostOp)
            HostApis.isHostApi(request.api) -> throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} has no op ${request.op}")
            else -> callProvider(record, request)
        }
    }

    private fun callHost(record: PluginRecord, request: Request, hostOp: HostOp): PluginReply {
        // 2. The API and version are supported.
        if (request.version !in hostOp.versions) throw BrokerException(PluginErrorCode.UNSUPPORTED, "${request.api} version ${request.version} is not supported")
        var tier: PermissionTier? = null
        var permission: String? = null
        var result = "ok"
        try {
            hostOp.permission?.let { id ->
                permission = id
                // 3. The permission is declared and granted.
                val declared = record.manifest.v2.permissions.firstOrNull { it.id == id }
                    ?: throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${record.manifest.id} did not declare $id")
                tier = PluginGrants.tierOf(declared)
                // 4. The parameters fit what was declared.
                hostOp.scope?.invoke(declared, request.args)?.let { throw BrokerException(PluginErrorCode.PERMISSION_DENIED, it) }
                requireGrant(record, id, tier!!, declared.reason, PluginPermissions.labelFor(id) ?: id)
            }
            return PluginReply.ok(hostOp.exec(env, record, request.args))
        } catch (e: BrokerException) {
            result = e.code.name
            throw e
        } finally {
            if (permission != null && tier != null && tier != PermissionTier.NORMAL) {
                env.audit(pluginId, AuditEntry(env.nowMs(), permission!!, request.api, request.op, hostOp.target(request.args), emptyList(), result))
            }
        }
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
            if (tier != PermissionTier.NORMAL) {
                val now = env.nowMs()
                env.audit(callerId, AuditEntry(now, exportedOp.permission, request.api, request.op, "", listOf(provider.plugin.manifest.id), result))
                env.audit(provider.plugin.manifest.id, AuditEntry(now, exportedOp.permission, request.api, "served ${request.op} for $callerId", "", chain, result))
            }
        }
    }

    companion object {
        const val BURST = 50
        const val SUSTAINED_PER_SEC = 10.0
        const val MAX_CHAIN = 3
        const val PROVIDER_MARGIN_MS = 1_000L
        const val MIN_PROVIDER_MS = 500L
    }
}
