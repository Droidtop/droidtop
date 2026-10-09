package dev.droidtop.pluginhost

/** docs/plugin-api.md 5.3: where a plugin's code runs, and so what it can reach without asking droidtop. */
enum class PluginTier {
    /** An isolated process of its own: no permissions, no network, no files. Everything goes through the broker. */
    CONTAINED,

    /**
     * A process of its own under droidtop's UID, kept to the broker-only context and the sandbox class loader like a
     * contained plugin, but NOT an isolated process, so the graphics chip is reachable (an isolated process may not open
     * it: sepolicy isolated_app_all.te). The one hole `gpu.render` opens. A system-call filter takes the network away
     * ([PluginSyscallFilter]); what the process still shares with droidtop's UID (its own native code could open droidtop's
     * files or ask Android's services for things as droidtop) cannot be walled off by an app on Android, so the
     * permission carries a plain warning ([PluginTiers.caution], docs/plugin-api.md 5.3).
     */
    GPU_RENDER,

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
     * The tier [record] runs in with the grants in [grants]. Full access is the `host.full_trust` grant and nothing
     * else; the graphics chip is the `gpu.render` grant and nothing else (a plugin the broker keeps to its own data,
     * now in a process that may draw with hardware). A plugin that cannot run contained at all ([containmentBlocker])
     * is left CONTAINED here, where [refusal] blocks it until it is allowed full access; `gpu.render` never substitutes
     * for the privilege such a plugin needs.
     */
    fun of(record: PluginRecord, grants: PluginGrants.Snapshot): PluginTier = when {
        record.manifest.contractVersion < 2 -> PluginTier.FULL_TRUST
        declaresFullTrust(record.manifest) && PluginGrants.stateOf(record, grants, FULL_TRUST) == GrantState.GRANTED -> PluginTier.FULL_TRUST
        containmentBlocker(record.manifest) != null -> PluginTier.CONTAINED
        declaresGpu(record.manifest) && PluginGrants.stateOf(record, grants, GPU_RENDER) == GrantState.GRANTED -> PluginTier.GPU_RENDER
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
        return "${record.manifest.label} needs full access ($blocker). Allow \"Run with droidtop's full access\" on its Permissions screen"
    }

    /**
     * The only access badge the person ever sees (docs/plugin-api.md 4.6): "Full access", the single warning, or null for
     * the safe default. There is deliberately no "contained" or "graphics" badge: the containment is the plugin system's
     * job, not the person's to understand, so a plugin that is not full access simply carries no access badge, and what it
     * may do is the one list on its Permissions screen.
     */
    /**
     * The warning of a granted permission whose grant lets the plugin's own code reach past droidtop's checks
     * ([PluginPermission.caution]; today `gpu.render`), for the plugin's page while it is granted, or null. A full-access
     * plugin carries the "Full access" warning instead, which already says more.
     */
    fun caution(record: PluginRecord, grants: PluginGrants.Snapshot): String? {
        if (of(record, grants) == PluginTier.FULL_TRUST) return null
        return record.manifest.v2.permissions.firstNotNullOfOrNull { declared ->
            PluginPermissions.find(declared.id)?.caution?.takeIf { PluginGrants.stateOf(record, grants, declared.id) == GrantState.GRANTED }
        }
    }

    fun badge(record: PluginRecord, grants: PluginGrants.Snapshot): String? = when {
        record.manifest.contractVersion < 2 -> "Full access (older plugin)"
        of(record, grants) == PluginTier.FULL_TRUST -> "Full access"
        else -> null
    }
}
