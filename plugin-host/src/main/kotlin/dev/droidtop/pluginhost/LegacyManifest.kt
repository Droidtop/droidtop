package dev.droidtop.pluginhost

/**
 * The capabilities extension points map to (docs/plugin-api.md 6). Contract 1
 * plugins are no longer served (Droidtop/tracker#766), but the capability call
 * path they used still carries a contract 2 plugin's calls for these points
 * ([DroidtopPlugin.invoke], [DroidtopPlugin.startJob]), so a manifest's
 * `provides` implies the capabilities here.
 */
object LegacyManifest {
    /** The capability each extension point is served as, for a point with an entry here. */
    val CAPABILITY_POINTS: Map<PluginCapability, String> = mapOf(
        PluginCapability.ACQUIRE_CONTENT to "library.sources",
        PluginCapability.METADATA_SOURCE to "library.metadata",
        PluginCapability.LIBRARY_ACTION to "ui.context_action",
        PluginCapability.STATUS_TILE to "ui.status_tile",
        PluginCapability.SETTINGS_ROWS to "ui.settings",
        PluginCapability.APP_STATUS to "apps.bridge",
    )

    /** The capability an extension point is served as, or null for a point that has none. */
    fun capabilityForPoint(point: String): PluginCapability? = CAPABILITY_POINTS.entries.firstOrNull { it.value == point }?.key

    /**
     * The capability a contract 2 job under [point] carries to `startJob`. A point with no capability (a
     * panel, a game's rows) carries `settings_rows` as a label only: the plugin reads the point and op from the
     * envelope in `args["call"]`, and the host checks the grant of that point ([PluginGrants.jobRefusal]), not of
     * the label's.
     */
    fun jobCapabilityFor(point: String): PluginCapability = capabilityForPoint(point) ?: PluginCapability.SETTINGS_ROWS

    /** The capabilities a manifest's `provides` implies, so its plugin is served by the capability runners for those points. */
    fun capabilitiesFor(provides: List<ProvidedPoint>): Set<PluginCapability> {
        val byPoint = CAPABILITY_POINTS.entries.associate { (cap, point) -> point to cap }
        return provides.mapNotNull { byPoint[it.point] }.toSet()
    }
}
