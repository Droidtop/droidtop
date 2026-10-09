package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * One line on the approval screen: [title] is the plain-language wording, [detail] what it lets the plugin do or the
 * plugin's own reason. A line with an [id] is a tick box (docs/plugin-api.md 4.3): the id is the grant key
 * (`net.any`, `provide:library.sources`, `export:<api>`), [highRisk] marks it as such, and [ticked] is how it starts.
 */
data class ConsentLine(
    val title: String,
    val detail: String? = null,
    val id: String? = null,
    val highRisk: Boolean = false,
    val ticked: Boolean = true,
)

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
    /** What the plugin offers other plugins ("Offer <api> to other plugins"), each a tick box. */
    val offers: List<ConsentLine>,
    val uses: List<ConsentUse>,
    val unsupported: List<String>,
    /** A contract 1 plugin holds `host.full_trust`: shown as "Full access (older plugin)". */
    val olderPluginFullAccess: Boolean,
    /** A contract 2 plugin that asks for `host.full_trust` (docs/plugin-api.md 5.3); without it, it runs contained. */
    val asksFullAccess: Boolean = false,
) {
    /** Every tick box on the list, in the order shown. */
    val items: List<ConsentLine> get() = (adds.flatMap { it.second } + can + asks.map { it.line } + offers).filter { it.id != null }

    /** The list cut down to [ids]: what an update added, asked about on the same list as at approval. */
    fun only(ids: Set<String>): ConsentView = copy(
        adds = adds.map { (mode, lines) -> mode to lines.filter { it.id in ids } }.filter { it.second.isNotEmpty() },
        can = can.filter { it.id in ids },
        asks = asks.filter { it.line.id in ids },
        offers = offers.filter { it.id in ids },
        uses = emptyList(),
        unsupported = emptyList(),
    )
}

object PluginConsent {
    private const val GRANT_EXPORT_PREFIX = PluginGrants.EXPORT_PREFIX

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
        val newContract = manifest.contractVersion >= 2
        val adds = linkedMapOf<String, MutableList<ConsentLine>>()
        val seenPoints = mutableSetOf<String>()
        for (entry in v2.provides) {
            val point = ExtensionPoints.find(entry.point) ?: continue
            if (!ExtensionPoints.supports(entry.point, entry.version)) continue
            if (!seenPoints.add(entry.point)) continue
            // One tick box per point, under the first mode it shows in.
            val mode = entry.surfaces().mapNotNull { modeOf(it) }.distinct().minByOrNull { MODE_ORDER.indexOf(it) } ?: MODE_ANY
            val line = ConsentLine(
                title = entry.label ?: point.label,
                detail = point.lets.ifEmpty { null },
                id = PluginPermissions.PROVIDE_PREFIX + entry.point,
                highRisk = point.risk.needsConsent,
                ticked = !newContract || !point.risk.needsConsent,
            )
            adds.getOrPut(mode) { mutableListOf() }.add(line)
        }

        val can = mutableListOf<ConsentLine>()
        val asks = mutableListOf<ConsentAsk>()
        for (declared in v2.permissions) {
            // A `provide:` item is the tick box of its point under "Adds".
            if (declared.id.startsWith(PluginPermissions.PROVIDE_PREFIX)) continue
            val label = PluginPermissions.labelFor(declared.id) ?: continue
            val extra = runCatching { JSONObject(declared.extra) }.getOrDefault(JSONObject())
            val scope = scopeText(declared.id, extra)
            val tier = PluginPermissions.tierFor(declared.id, scopeIsAny = declared.id == "apps.intents.out" && extra.optString("scope") == "any") ?: continue
            val risky = tier != PermissionTier.NORMAL
            // An operation droidtop runs under an Android permission it may not hold yet: Android asks the person too (4.1).
            val androidNote = PluginPermissions.find(declared.id)?.android?.takeIf { it.fromSdk > 0 }?.let { "Android asks you too, the first time" }
            val line = ConsentLine(
                title = label + (scope?.let { " ($it)" } ?: ""),
                detail = listOfNotNull(declared.reason, androidNote).joinToString(" - ").ifEmpty { null },
                id = if (newContract) declared.id else null,
                highRisk = risky,
                ticked = !risky,
            )
            if (!risky) can.add(line) else asks.add(ConsentAsk(tier, line, declared.required))
        }
        val offers = v2.exports.map { ConsentLine(it.offerLabel(), id = GRANT_EXPORT_PREFIX + it.grantKey) }

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
            offers = offers,
            uses = uses,
            unsupported = manifest.unsupportedDeclarations(),
            olderPluginFullAccess = manifest.contractVersion < 2,
            asksFullAccess = newContract && PluginTiers.declaresFullTrust(manifest),
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
