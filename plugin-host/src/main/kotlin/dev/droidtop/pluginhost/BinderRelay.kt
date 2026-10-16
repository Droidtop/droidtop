package dev.droidtop.pluginhost

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * Decides one transaction a plugin sends through a [BinderRelay]: null to forward it, else the reason it is refused.
 * [data] is positioned at the start; a policy may read it (the relay rewinds it before forwarding).
 */
fun interface RelayPolicy {
    fun refusal(code: Int, data: Parcel): String?
}

/**
 * The sandbox's binder relay, droidtop's half (docs/plugin-api.md 5.6, "Every plugin is isolated, full access
 * included"): a plugin in an isolated process holds this binder in place of a system service's, and every
 * transaction it sends arrives here, in droidtop's process, where [policy] decides it. Forwarded, it is made by
 * droidtop, so the service sees droidtop's identity and permissions, for exactly the calls the policy lists; refused,
 * the plugin gets a SecurityException naming why. Allowlist-only: there is no pass-through, and a code outside the
 * call range (a dump, a shell command) is refused whatever the policy says. Every decision goes to [audit].
 */
class BinderRelay(
    private val target: IBinder,
    private val descriptor: String,
    private val policy: RelayPolicy,
    private val audit: (code: Int, refusal: String?) -> Unit,
) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(descriptor)
            return true
        }
        val refusal = if (code < FIRST_CALL_TRANSACTION || code > LAST_CALL_TRANSACTION) {
            "not a call the relay forwards"
        } else {
            runCatching { policy.refusal(code, data) }.getOrElse { "the call could not be read" }
        }
        data.setDataPosition(0)
        audit(code, refusal)
        if (refusal != null) {
            if (reply != null && flags and FLAG_ONEWAY == 0) reply.writeException(SecurityException(refusal))
            return true
        }
        return target.transact(code, data, reply, flags)
    }
}

/**
 * What the Shizuku relay forwards (docs/plugin-api.md 5.6): Shizuku authorizes by the caller's package, and an
 * isolated UID has none, so a full-access plugin that declares `os.shizuku` reaches Shizuku through droidtop. The
 * codes are `IShizukuService`'s (Shizuku-API, `moe.shizuku.server.IShizukuService`: a method's `= id` is its
 * transaction code less [IBinder.FIRST_CALL_TRANSACTION]). Forwarded: the state reads, asking and checking
 * droidtop's permission, attaching the client (Shizuku records the caller, droidtop, for the processes it starts),
 * and starting a process for a plugin that exports a `priv.*` command point. Never: the raw `transactRemote` (any
 * system service as the shell), user services, system properties, flags for other UIDs, or stopping Shizuku.
 */
object ShizukuRelayPolicy {
    const val DESCRIPTOR = "moe.shizuku.server.IShizukuService"

    /** The name [IPluginRuntime.attachRelay] carries for this relay. */
    const val RELAY_NAME = "shizuku"

    private const val GET_VERSION = 2
    private const val GET_UID = 3
    private const val CHECK_PERMISSION = 4
    private const val NEW_PROCESS = 7
    private const val GET_SELINUX_CONTEXT = 8
    private const val REQUEST_PERMISSION = 14
    private const val CHECK_SELF_PERMISSION = 15
    private const val SHOULD_SHOW_RATIONALE = 16
    private const val ATTACH_APPLICATION = 17

    private val ALWAYS = setOf(GET_VERSION, GET_UID, CHECK_PERMISSION, GET_SELINUX_CONTEXT, REQUEST_PERMISSION, CHECK_SELF_PERMISSION, SHOULD_SHOW_RATIONALE, ATTACH_APPLICATION)

    /** The exported points whose job is a command or a package call, so a process may be started for them. */
    private val COMMAND_POINTS = setOf("priv.shell", "priv.packages", "priv.settings")

    fun code(id: Int): Int = IBinder.FIRST_CALL_TRANSACTION + id

    /** Null when [code] is one the relay forwards for a plugin exporting [exports], else why not. Pure. */
    fun codeRefusal(code: Int, exports: Set<String>): String? {
        val id = code - IBinder.FIRST_CALL_TRANSACTION
        return when {
            id in ALWAYS -> null
            id == NEW_PROCESS && exports.any { it in COMMAND_POINTS } -> null
            id == NEW_PROCESS -> "starting a process needs a declared command point (priv.shell, priv.packages)"
            else -> "Shizuku call $id is not one a declared full-access point prescribes"
        }
    }

    /**
     * True when a command names droidtop's own package: a plugin's command never reaches droidtop's components. A
     * guard on the plain spelling only; what stops a disguised one is that the command points are droidtop's
     * official plugins' and every call to them is the person's, checked and logged by the broker.
     */
    fun namesPackage(argv: List<String>, packageName: String): Boolean = argv.any { it.contains(packageName) }

    /** The policy for a plugin exporting [exports], in droidtop's package [packageName]. */
    fun policy(exports: Set<String>, packageName: String): RelayPolicy = RelayPolicy { code, data ->
        codeRefusal(code, exports) ?: if (code == code(NEW_PROCESS)) {
            data.enforceInterface(DESCRIPTOR)
            val argv = data.createStringArray()?.toList().orEmpty()
            if (namesPackage(argv, packageName)) "a command may not name droidtop's own package" else null
        } else {
            null
        }
    }
}

/**
 * droidtop's side of the Shizuku relay (docs/plugin-api.md 5.6): for a plugin allowed full access and `os.shizuku`,
 * in an isolated process, a [BinderRelay] over the Shizuku binder droidtop holds, handed to the plugin's process.
 * Every forwarded or refused call goes to the plugin's activity log, as a broker call does.
 */
object ShizukuRelays {
    /** In a plugin's process: droidtop handed it a Shizuku relay ([IPluginRuntime.attachRelay]). */
    @Volatile
    var received = false

    /** True when [record] may have the relay with [grants]: it declared `os.shizuku` and the person allowed it. */
    fun allowed(record: PluginRecord, grants: PluginGrants.Snapshot): Boolean =
        record.manifest.v2.permissions.any { it.id == PluginTiers.OS_SHIZUKU } &&
            PluginGrants.stateOf(record, grants, PluginTiers.OS_SHIZUKU) == GrantState.GRANTED

    /**
     * Hands [runtime] the relay for [record]. False when there is nothing to hand yet (Shizuku is not running): the
     * plugin sees Shizuku as absent, and the next binder Shizuku sends is handed on ([NativePluginRunner]).
     */
    fun attach(context: android.content.Context, runtime: IPluginRuntime, record: PluginRecord): Boolean {
        val id = record.manifest.id
        ShizukuTransport.ensureStarted()
        val target = runCatching { rikka.shizuku.Shizuku.getBinder() }.getOrNull()?.takeIf { it.isBinderAlive } ?: return false
        val exports = record.manifest.v2.exports.map { it.api }.toSet()
        val audit = PluginAudit.forContext(context)
        val relay = BinderRelay(target, ShizukuRelayPolicy.DESCRIPTOR, ShizukuRelayPolicy.policy(exports, context.packageName)) { code, refusal ->
            val call = code - IBinder.FIRST_CALL_TRANSACTION
            if (refusal != null) android.util.Log.w("droidtop.plugin", "$id: Shizuku call $call refused: $refusal")
            runCatching { audit.append(id, AuditEntry(System.currentTimeMillis(), PluginTiers.OS_SHIZUKU, "shizuku", "call $call", "", emptyList(), refusal?.let { "refused: $it" } ?: "ok")) }
        }
        runtime.attachRelay(id, ShizukuRelayPolicy.RELAY_NAME, relay)
        return true
    }
}
