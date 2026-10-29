package dev.droidtop.pluginhost

/** docs/plugin-api.md 5.3: where a plugin's code runs, and so what it can reach without asking droidtop. */
enum class PluginTier {
    /** An isolated process of its own: no permissions, no network, no files but a folder of its own. Everything goes through the broker. */
    CONTAINED,

    /**
     * An open process (docs/plugin-api.md 5.3, "Open processes: one hole per permission"): a process of its own under
     * droidtop's UID, kept to the broker-only context and the sandbox class loader like a contained plugin, but NOT an
     * isolated process, so it can be given what an isolated process never has. Each granted hole permission
     * ([PluginTiers.holes]) opens only its own hole: `gpu.render` the graphics chip (an isolated process may not open
     * it: sepolicy isolated_app_all.te), `net.sockets` internet sockets. The system-call filter ([PluginSyscallFilter])
     * takes away everything else, the network included unless granted, and sends every path to a file broker
     * ([SandboxBroker]). What the process still shares with droidtop's UID (its own native code could ask Android's
     * services for things as droidtop) cannot be walled off by an app on Android, so each hole permission carries a
     * plain warning ([PluginTiers.caution]). One process serves one plugin and ends with it, so no hole outlives it.
     */
    OPEN,

    /** A process of its own under droidtop's UID: anything droidtop can do, beyond the broker's sight. */
    FULL_TRUST,
}

/**
 * The tier rules (docs/plugin-api.md 5.3). Contained is the default. Full trust is only ever the `host.full_trust`
 * grant (critical, so the person ticks it or answers the sheet). What cannot run contained is said once, here, and both
 * the installer (a plugin that needs full access must declare it) and the call path (it runs only once allowed) ask it.
 */
object PluginTiers {
    const val FULL_TRUST = "host.full_trust"
    /**
     * Retired (2026-10-16, docs/plugin-api.md 5.6): the owner, "Plugins shouldn't be drawing stuff themselves anyway". It is
     * no hole and no permission; a plugin that declares it runs contained and draws CPU frames. Kept as a name so the code
     * that still reads it (a process that was given it before) compiles to "never".
     */
    const val GPU_RENDER = "gpu.render"
    const val NET_SOCKETS = "net.sockets"

    /** The permissions that each open one hole in an open process ([PluginTier.OPEN]); nothing else does. */
    val HOLES = listOf(NET_SOCKETS)

    /**
     * The manifest key droidtop writes when it hands an open process its plugin: the granted holes ([holes]), which the
     * process turns into its filter and rendering. droidtop's alone: a plugin's own manifest value is always replaced.
     */
    internal const val HOLES_KEY = "_droidtopHoles"

    /** Shizuku through droidtop's relay (docs/plugin-api.md 5.6): a full-access permission, granted to nothing else. */
    const val OS_SHIZUKU = "os.shizuku"

    /**
     * The system property that runs full access isolated before it is the default (docs/plugin-api.md 5.6, "Every
     * plugin is isolated, full access included"): `adb shell setprop debug.droidtop.isolate_full 1`, for the per-plugin
     * runs on CI and the rig. The default switches only once every official and gamegrab plugin has passed.
     */
    const val ISOLATE_FULL_PROPERTY = "debug.droidtop.isolate_full"

    /** Whether full access runs isolated on this device now: [ISOLATE_FULL_PROPERTY] set. Off by default. */
    fun fullAccessIsolated(): Boolean = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, ISOLATE_FULL_PROPERTY) == "1"
    }.getOrDefault(false)

    /**
     * True when a plugin allowed full access runs in an isolated process with [switchOn]: on Android 11 (API 30) or
     * later, where its sockets can be the broker's.
     */
    fun fullAccessRunsIsolated(switchOn: Boolean, sdk: Int = android.os.Build.VERSION.SDK_INT): Boolean =
        switchOn && sdk >= 30

    fun declaresFullTrust(manifest: PluginManifest): Boolean = manifest.v2.permissions.any { it.id == FULL_TRUST }

    /**
     * Why [manifest] cannot run contained, or null when it can. Every kind runs contained (docs/plugin-api.md 5.3: code that
     * loads by path, a native library or a Flutter engine, is answered by the guarded hooks); what is left needs
     * privilege an isolated UID never has: binding another app's service, or giving other plugins system-level access.
     */
    fun containmentBlocker(manifest: PluginManifest): String? = when {
        manifest.v2.permissions.any { it.id == "apps.bind" } -> "it binds other apps' services"
        manifest.v2.exports.any { it.api.startsWith("priv.") || it.api.startsWith("root.") } -> "it gives other plugins system-level access"
        else -> null
    }

    fun declaresGpu(manifest: PluginManifest): Boolean = manifest.v2.permissions.any { it.id == GPU_RENDER }

    /**
     * True when an open plugin with [holes] runs in an isolated process under a UID of its own, its sockets made, connected
     * and accepted by droidtop's broker (docs/plugin-api.md 5.6, "A UID of its own, as a sandbox feature"): the network
     * hole, on Android 11 (API 30) or later, where an isolated process may use a socket it was handed. Below API 30 it
     * waits for the broker's byte relay and stays in a process of droidtop's UID until then.
     */
    fun runsIsolated(holes: Set<String>, sdk: Int = android.os.Build.VERSION.SDK_INT): Boolean =
        holes == setOf(NET_SOCKETS) && sdk >= 30

    /**
     * The holes [record] gets in its open process: the hole permissions it declared and the person granted ([HOLES]).
     * Empty for a plugin that runs contained or with full access, where none applies.
     */
    fun holes(record: PluginRecord, grants: PluginGrants.Snapshot): Set<String> = when {
        declaresFullTrust(record.manifest) && PluginGrants.stateOf(record, grants, FULL_TRUST) == GrantState.GRANTED -> emptySet()
        containmentBlocker(record.manifest) != null -> emptySet()
        else -> HOLES.filterTo(LinkedHashSet()) { hole ->
            record.manifest.v2.permissions.any { it.id == hole } && PluginGrants.stateOf(record, grants, hole) == GrantState.GRANTED
        }
    }

    /**
     * The tier [record] runs in with the grants in [grants]. Full access is the `host.full_trust` grant and nothing
     * else; an open process is any granted hole permission and nothing else ([holes]: a plugin the broker keeps to its
     * own data, in a process that may draw with hardware or use the network, each only when granted). A plugin that
     * cannot run contained at all ([containmentBlocker]) is left CONTAINED here, where [refusal] blocks it until it is
     * allowed full access; a hole permission never substitutes for the privilege such a plugin needs.
     */
    fun of(record: PluginRecord, grants: PluginGrants.Snapshot): PluginTier = when {
        declaresFullTrust(record.manifest) && PluginGrants.stateOf(record, grants, FULL_TRUST) == GrantState.GRANTED -> PluginTier.FULL_TRUST
        containmentBlocker(record.manifest) != null -> PluginTier.CONTAINED
        holes(record, grants).isNotEmpty() -> PluginTier.OPEN
        else -> PluginTier.CONTAINED
    }

    /**
     * Why [record] cannot run in the tier its grants put it in, or null: a contract 1 plugin, which this build no longer
     * runs ([PLUGIN_MANIFEST_MIN_CONTRACT_VERSION]), or a plugin that can only run with full access and has not been
     * allowed it. It is not called and not disabled; its page says it needs an update, or what to allow.
     */
    fun refusal(record: PluginRecord, grants: PluginGrants.Snapshot): String? {
        if (record.manifest.contractVersion < PLUGIN_MANIFEST_MIN_CONTRACT_VERSION) {
            return "${record.manifest.label} was written for an older droidtop and needs an update"
        }
        if (of(record, grants) != PluginTier.CONTAINED) return null
        val blocker = containmentBlocker(record.manifest) ?: return null
        if (!declaresFullTrust(record.manifest)) {
            return "${record.manifest.label} needs full access ($blocker), and this version of it does not ask for it; it needs an update that does"
        }
        return "${record.manifest.label} needs full access ($blocker). Allow \"Full access\" on its Permissions screen"
    }

    /**
     * The only access badge the person ever sees (docs/plugin-api.md 4.6): "Full access", the single warning, or null for
     * the safe default. There is deliberately no "contained" or "graphics" badge: the containment is the plugin system's
     * job, not the person's to understand, so a plugin that is not full access simply carries no access badge, and what it
     * may do is the one list on its Permissions screen.
     */
    /**
     * The warning of a granted permission whose grant lets the plugin's own code reach past droidtop's checks
     * ([PluginPermission.caution]; the hole permissions `gpu.render` and `net.sockets`), for the plugin's page while it is
     * granted, or null. A full-access
     * plugin carries the "Full access" warning instead, which already says more.
     */
    fun caution(record: PluginRecord, grants: PluginGrants.Snapshot): String? {
        if (of(record, grants) == PluginTier.FULL_TRUST) return null
        // Each granted hole says its own warning: a plugin with the graphics chip and the network gets both.
        return record.manifest.v2.permissions.mapNotNull { declared ->
            PluginPermissions.find(declared.id)?.caution?.takeIf { PluginGrants.stateOf(record, grants, declared.id) == GrantState.GRANTED }
        }.distinct().joinToString(" ").ifEmpty { null }
    }

    fun badge(record: PluginRecord, grants: PluginGrants.Snapshot): String? =
        if (of(record, grants) == PluginTier.FULL_TRUST) "Full access" else null
}
