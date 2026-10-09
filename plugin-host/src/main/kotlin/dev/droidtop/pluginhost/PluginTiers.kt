package dev.droidtop.pluginhost

/** docs/plugin-api.md 5.3: where a plugin's code runs, and so what it can reach without asking droidtop. */
enum class PluginTier {
    /** An isolated process of its own: no permissions, no network, no files. Everything goes through the broker. */
    CONTAINED,

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

    fun declaresFullTrust(manifest: PluginManifest): Boolean = manifest.v2.permissions.any { it.id == FULL_TRUST }

    /**
     * Why [manifest] cannot run contained, or null when it can. Each reason is a fact of the isolated process
     * (docs/plugin-api.md 5.3, "Spike results"): it opens no path, binds no other app and holds no privilege.
     */
    fun containmentBlocker(manifest: PluginManifest): String? = when {
        manifest.contractVersion < 2 -> "it was written before permissions existed"
        manifest.kind == PluginKind.FLUTTER_EMBED -> "a Flutter engine loads its code and assets by path"
        manifest.kind == PluginKind.NATIVE_BUNDLE && manifest.payload.any { it.path.startsWith(ContainedFiles.NATIVE_PREFIX) && it.path.endsWith(".so") } ->
            "its native libraries are loaded by path"
        manifest.v2.permissions.any { it.id == "apps.bind" } -> "it binds other apps' services"
        manifest.v2.exports.any { it.api.startsWith("priv.") || it.api.startsWith("root.") } -> "it gives other plugins system-level access"
        else -> null
    }

    /** The tier [record] runs in with the grants in [grants]. */
    fun of(record: PluginRecord, grants: PluginGrants.Snapshot): PluginTier = when {
        record.manifest.contractVersion < 2 -> PluginTier.FULL_TRUST
        declaresFullTrust(record.manifest) && PluginGrants.stateOf(record, grants, FULL_TRUST) == GrantState.GRANTED -> PluginTier.FULL_TRUST
        else -> PluginTier.CONTAINED
    }

    /**
     * Why [record] cannot run in the tier its grants put it in, or null: a plugin that can only run with full access and
     * has not been allowed it. It is not called and not disabled; its page says what to allow.
     */
    fun refusal(record: PluginRecord, grants: PluginGrants.Snapshot): String? {
        if (of(record, grants) == PluginTier.FULL_TRUST) return null
        val blocker = containmentBlocker(record.manifest) ?: return null
        if (!declaresFullTrust(record.manifest)) {
            return "${record.manifest.label} needs full access ($blocker), and this version of it does not ask for it; it needs an update that does"
        }
        return "${record.manifest.label} needs full access ($blocker). Allow \"Run with droidtop's full access\" on its Permissions screen"
    }

    /** The badge every surface shows for [manifest]'s access (docs/plugin-api.md 4.6, 5.3). */
    fun badge(record: PluginRecord, grants: PluginGrants.Snapshot): String = when {
        record.manifest.contractVersion < 2 -> "Full access (older plugin)"
        of(record, grants) == PluginTier.FULL_TRUST -> "Full access"
        else -> "Contained"
    }
}
