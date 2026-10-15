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
 * The tier rules (docs/plugin-api.md 5.3). Contained is the default for a contract 2 plugin. Full trust is only ever
 * the `host.full_trust` grant (critical, so the person ticks it or answers the sheet), plus every contract 1 plugin,
 * which was written before permissions existed. What cannot run contained is said once, here, and both the installer
 * (a plugin that needs full access must declare it) and the call path (it runs only once allowed) ask it.
 */
object PluginTiers {
    const val FULL_TRUST = "host.full_trust"
    const val GPU_RENDER = "gpu.render"
    const val NET_SOCKETS = "net.sockets"

    /** The permissions that each open one hole in an open process ([PluginTier.OPEN]); nothing else does. */
    val HOLES = listOf(GPU_RENDER, NET_SOCKETS)

    /**
     * The manifest key droidtop writes when it hands an open process its plugin: the granted holes ([holes]), which the
     * process turns into its filter and rendering. droidtop's alone: a plugin's own manifest value is always replaced.
     */
    internal const val HOLES_KEY = "_droidtopHoles"

    fun declaresFullTrust(manifest: PluginManifest): Boolean = manifest.v2.permissions.any { it.id == FULL_TRUST }

    /**
     * Why [manifest] cannot run contained, or null when it can. Every kind runs contained (docs/plugin-api.md 5.3: code that
     * loads by path, a native library or a Flutter engine, is answered by the guarded hooks); what is left needs
     * privilege an isolated UID never has: binding another app's service, or giving other plugins system-level access.
     */
    fun containmentBlocker(manifest: PluginManifest): String? = when {
        manifest.contractVersion < 2 -> "it was written before permissions existed"
        manifest.v2.permissions.any { it.id == "apps.bind" } -> "it binds other apps' services"
        manifest.v2.exports.any { it.api.startsWith("priv.") || it.api.startsWith("root.") } -> "it gives other plugins system-level access"
        else -> null
    }

    fun declaresGpu(manifest: PluginManifest): Boolean = manifest.v2.permissions.any { it.id == GPU_RENDER }

    /**
     * The holes [record] gets in its open process: the hole permissions it declared and the person granted ([HOLES]).
     * Empty for a plugin that runs contained or with full access, where none applies.
     */
    fun holes(record: PluginRecord, grants: PluginGrants.Snapshot): Set<String> = when {
        record.manifest.contractVersion < 2 -> emptySet()
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
        record.manifest.contractVersion < 2 -> PluginTier.FULL_TRUST
        declaresFullTrust(record.manifest) && PluginGrants.stateOf(record, grants, FULL_TRUST) == GrantState.GRANTED -> PluginTier.FULL_TRUST
        containmentBlocker(record.manifest) != null -> PluginTier.CONTAINED
        holes(record, grants).isNotEmpty() -> PluginTier.OPEN
        else -> PluginTier.CONTAINED
    }

    /**
     * Why [record] cannot run in the tier its grants put it in, or null: a plugin that can only run with full access and
     * has not been allowed it. It is not called and not disabled; its page says what to allow.
     */
    fun refusal(record: PluginRecord, grants: PluginGrants.Snapshot): String? {
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

    fun badge(record: PluginRecord, grants: PluginGrants.Snapshot): String? = when {
        record.manifest.contractVersion < 2 -> "Full access (older plugin)"
        of(record, grants) == PluginTier.FULL_TRUST -> "Full access"
        else -> null
    }
}
