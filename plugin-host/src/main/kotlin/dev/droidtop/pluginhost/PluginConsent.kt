package dev.droidtop.pluginhost

import org.json.JSONObject

/** One line on the approval screen: [title] is the plain-language wording, [detail] the plugin's own reason or a scope. */
data class ConsentLine(val title: String, val detail: String? = null)

/** How a permission or extension point is shown under "Asks for" (docs/plugin-api.md 4.3). */
data class ConsentAsk(val tier: PermissionTier, val line: ConsentLine, val needed: Boolean)

/** What one `requires` entry resolves to right now: the provider that would serve it, if any (docs/plugin-api.md 2.3). */
data class ConsentUse(val line: ConsentLine, val optional: Boolean, val providerLabel: String?, val providerBadge: String?)

/**
 * The approval screen's content for one plugin, built from the registries
 * and the manifest alone (docs/plugin-api.md 4.3): "Adds" grouped by mode,
 * "Can" (normal permissions), "Asks for" (dangerous and critical ones plus
 * high-risk points, each with its reason), "Uses from other plugins", and
 * what this build does not support. Read-only: no grant is stored yet.
 */
data class ConsentView(
    val adds: List<Pair<String, List<ConsentLine>>>,
    val can: List<ConsentLine>,
    val asks: List<ConsentAsk>,
    val uses: List<ConsentUse>,
    val unsupported: List<String>,
    /** A contract 1 plugin holds `host.full_trust`: shown as "Full access (older plugin)". */
    val olderPluginFullAccess: Boolean,
)

object PluginConsent {
    private const val MODE_GAMING = "Gaming"
    private const val MODE_ANDROID = "Android"
    private const val MODE_DESKTOP = "Desktop"
    private const val MODE_ANY = "Wherever it fits"
    private val MODE_ORDER = listOf(MODE_GAMING, MODE_ANDROID, MODE_DESKTOP, MODE_ANY)

    /** The mode a surface id belongs to (`gaming.quick_menu` is Gaming), or null for an id with no known mode prefix. */
    fun modeOf(surface: String): String? = when (surface.substringBefore('.')) {
        "gaming" -> MODE_GAMING
        "launcher", "android", "settings" -> MODE_ANDROID
        "desktop" -> MODE_DESKTOP
        else -> null
    }

    /**
     * @param installed every installed plugin, to resolve `requires` against each one's `exports`.
     * @param badgeFor the trust badge for an origin ("Official", "Added by you", ...), so the badge wording stays with the caller that owns trust state.
     */
    fun of(manifest: PluginManifest, installed: List<PluginRecord>, badgeFor: (String) -> String): ConsentView {
        val v2 = manifest.v2
        val adds = linkedMapOf<String, MutableList<ConsentLine>>()
        for (entry in v2.provides) {
            val point = ExtensionPoints.find(entry.point) ?: continue
            if (!ExtensionPoints.supports(entry.point, entry.version)) continue
            val modes = entry.surfaces().mapNotNull { modeOf(it) }.distinct().ifEmpty { listOf(MODE_ANY) }
            val line = ConsentLine(entry.label ?: point.label, if (entry.label != null) point.label else null)
            for (mode in modes) adds.getOrPut(mode) { mutableListOf() }.add(line)
        }

        val can = mutableListOf<ConsentLine>()
        val asks = mutableListOf<ConsentAsk>()
        val seenProvide = mutableSetOf<String>()
        for (declared in v2.permissions) {
            val label = PluginPermissions.labelFor(declared.id) ?: continue
            val extra = runCatching { JSONObject(declared.extra) }.getOrDefault(JSONObject())
            val scope = scopeText(declared.id, extra)
            val tier = PluginPermissions.tierFor(declared.id, scopeIsAny = declared.id == "apps.intents.out" && extra.optString("scope") == "any") ?: continue
            declared.id.let { PluginPermissions.providedPoint(it) }?.let { seenProvide += it }
            val line = ConsentLine(label + (scope?.let { " ($it)" } ?: ""), declared.reason)
            if (tier == PermissionTier.NORMAL) {
                // A normal `provide:` item is already listed under "Adds".
                if (!declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) can.add(line)
            } else {
                asks.add(ConsentAsk(tier, line, declared.required))
            }
        }
        // A v2 plugin that provides a high-risk point without naming the consent item is still asked about it.
        for (entry in v2.provides) {
            val point = ExtensionPoints.find(entry.point) ?: continue
            if (point.risk.needsConsent && entry.point !in seenProvide) {
                seenProvide += entry.point
                val tier = if (point.risk == PointRisk.CRITICAL) PermissionTier.CRITICAL else PermissionTier.DANGEROUS
                asks.add(ConsentAsk(tier, ConsentLine("Add to droidtop: ${point.label}"), needed = false))
            }
        }

        val uses = v2.requires.map { req ->
            val provider = installed.firstOrNull { rec ->
                rec.manifest.id != manifest.id && rec.manifest.v2.exports.any { it.api == req.api }
            }
            val detail = buildList {
                if (req.minLevel != null) add("level ${req.minLevel}")
                req.reason?.let { add(it) }
            }.joinToString(" - ").ifEmpty { null }
            ConsentUse(
                line = ConsentLine(req.api, detail),
                optional = req.optional,
                providerLabel = provider?.manifest?.label,
                providerBadge = provider?.let { badgeFor(it.manifest.origin) },
            )
        }

        return ConsentView(
            adds = MODE_ORDER.mapNotNull { mode -> adds[mode]?.let { mode to it.toList() } },
            can = can,
            asks = asks.sortedByDescending { it.tier.ordinal },
            uses = uses,
            unsupported = manifest.unsupportedDeclarations(),
            olderPluginFullAccess = manifest.contractVersion < 2,
        )
    }

    /** The parameter a scoped permission was declared with, as text: the domain list, the package list, the folder scope. */
    private fun scopeText(id: String, extra: JSONObject): String? = when (id) {
        "net.domains" -> extra.optJSONArray("domains")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }
        "apps.bind", "apps.check", "apps.intents.out" -> extra.optJSONArray("packages")?.let { a ->
            (0 until a.length()).joinToString(", ") { i -> a.optString(i).let { if (it == "*") "any app" else it } }
        } ?: if (extra.optString("scope") == "any") "any app" else null
        "library.folders.write" -> if (extra.optString("scope") == "destination") "only the folder you choose" else null
        else -> null
    }?.takeIf { it.isNotBlank() }
}
