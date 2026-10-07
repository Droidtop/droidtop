package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The one translation from a contract 1 manifest to the contract 2
 * shape (docs/plugin-api.md 6), so the host has one model internally.
 * What a v1 plugin could already do is what it is derived to hold:
 * nothing new is asked of it, and the approval screen shows it as
 * "Full access (older plugin)" through `host.full_trust`.
 */
object LegacyManifest {
    /** The capability each extension point replaces, both ways: a v1 capability provides this point, and a v2 point with an entry here is also served as that capability. */
    val CAPABILITY_POINTS: Map<PluginCapability, String> = mapOf(
        PluginCapability.ACQUIRE_CONTENT to "library.sources",
        PluginCapability.METADATA_SOURCE to "library.metadata",
        PluginCapability.LIBRARY_ACTION to "ui.context_action",
        PluginCapability.STATUS_TILE to "ui.status_tile",
        PluginCapability.SETTINGS_ROWS to "ui.settings",
        PluginCapability.APP_STATUS to "apps.bridge",
    )

    /** The contract 1 capability an extension point replaced, or null for a point that never had one. */
    fun capabilityForPoint(point: String): PluginCapability? = CAPABILITY_POINTS.entries.firstOrNull { it.value == point }?.key

    /**
     * The capability a contract 2 job under [point] carries to `startJob`. A point with no contract 1 capability (a
     * panel, a game's rows) carries `settings_rows` as a label only: the plugin reads the point and op from the
     * envelope in `args["call"]`, and the host checks the grant of that point ([PluginGrants.jobRefusal]), not of
     * the label's.
     */
    fun jobCapabilityFor(point: String): PluginCapability = capabilityForPoint(point) ?: PluginCapability.SETTINGS_ROWS

    /** The capabilities a v2 manifest's `provides` implies, so a v2 plugin is served by the same runners until they speak the v2 envelope. */
    fun capabilitiesFor(provides: List<ProvidedPoint>): Set<PluginCapability> {
        val byPoint = CAPABILITY_POINTS.entries.associate { (cap, point) -> point to cap }
        return provides.mapNotNull { byPoint[it.point] }.toSet()
    }

    /** The event id docs/plugin-api.md 6 renames; the old id stays an alias while contract 1 is served. */
    private val EVENT_RENAMES = mapOf("default_player_changed" to EventSubscription("library.default_player_changed", 2))

    fun toV2(m: PluginManifest): V2Declarations {
        val provides = m.capabilities.sortedBy { it.ordinal }.map { cap ->
            val point = CAPABILITY_POINTS.getValue(cap)
            val extra = when (cap) {
                PluginCapability.LIBRARY_ACTION -> JSONObject().put("targets", JSONArray(listOf("game"))).toString()
                PluginCapability.SETTINGS_ROWS -> JSONObject().put("target", "plugin").toString()
                else -> "{}"
            }
            ProvidedPoint(point = point, version = 1, extra = extra)
        }

        val permissions = buildList<DeclaredPermission> {
            fun grant(id: String, extra: String = "{}") {
                if (none { it.id == id }) add(DeclaredPermission(id = id, extra = extra))
            }
            for (cap in m.capabilities.sortedBy { it.ordinal }) {
                grant("provide:" + CAPABILITY_POINTS.getValue(cap))
                when (cap) {
                    PluginCapability.ACQUIRE_CONTENT -> { grant("library.folders.write", """{"scope":"destination"}"""); grant("net.any") }
                    PluginCapability.METADATA_SOURCE -> grant("net.any")
                    PluginCapability.LIBRARY_ACTION -> grant("library.read")
                    // apps.bridge also implies apps.check and apps.launch: every v1 plugin holds both below.
                    PluginCapability.APP_STATUS, PluginCapability.STATUS_TILE, PluginCapability.SETTINGS_ROWS -> Unit
                }
            }
            // A v1 plugin is not contained: it can call everything PluginContext offers and open its own sockets.
            grant("host.full_trust")
            grant("apps.check", """{"packages":["*"]}""")
            grant("apps.launch")
            grant("apps.intents.out", """{"scope":"any"}""")
            if (m.requestsRoot) grant("priv.shell.root")
            if (m.boundServiceTargets.isNotEmpty()) {
                grant("apps.bind", JSONObject().put("packages", JSONArray(m.boundServiceTargets.toList().sorted())).toString())
            }
            if (m.subscribedEvents.any { PluginEvent.fromId(it) == PluginEvent.DEFAULT_PLAYER_CHANGED }) grant("library.read")
        }

        val subscribes = m.subscribedEvents.sorted().map { EVENT_RENAMES[it] ?: EventSubscription(it, 1) }

        val requires = if (m.requestsRoot) {
            listOf(RequiredApi(api = "priv.shell", version = "1.0", optional = true, minLevel = "root"))
        } else {
            emptyList()
        }

        return V2Declarations(provides = provides, permissions = permissions, subscribes = subscribes, requires = requires)
    }
}
