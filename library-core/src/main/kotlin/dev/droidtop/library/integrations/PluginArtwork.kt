package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.PcGameIds
import dev.droidtop.library.scraper.PcMatch
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord
import org.json.JSONArray
import org.json.JSONObject

/**
 * Artwork from plugins (docs/plugin-api.md 3 A4, `library.artwork@1`, Droidtop/tracker#714): a SteamGridDB-style
 * provider is asked during a PC game's scrape, after droidtop's own sources, for the roles they left empty. It answers
 * `list {entry, kinds}` with pictures by role; droidtop downloads each, checks it is an image of a sensible size and
 * files it under the role's folder, exactly as it does a built-in source's (the scrape's own write path). A plugin
 * never writes the media folder and learns only the game's title, system and store ids.
 */
object PluginArtwork {
    const val POINT = "library.artwork"
    private const val BUDGET_MS = 10_000L

    /** The roles a provider may answer, as `kind`: droidtop's artwork roles (docs/SPEC.md 7h, "Artwork roles"). */
    val KINDS = listOf("cover", "wide", "hero", "logo", "icon")

    /**
     * Each provider's pictures for the game, as one candidate per provider in the order the plugins are listed: what
     * the scrape adds after its own sources. Empty when no provider is allowed or none answers. Network: off the main
     * thread; each provider has [BUDGET_MS].
     */
    suspend fun matches(context: Context, entry: LibraryEntry, ids: PcGameIds, name: String): List<PcMatch> {
        val providers = providersOf(context.applicationContext, POINT)
        if (providers.isEmpty()) return emptyList()
        val args = args(entry, ids, name)
        return providers.mapNotNull { (record, provided) ->
            val label = provided.label ?: record.manifest.label
            runCatching { call(context, record, args) }.getOrNull()?.let { reply -> matchOf(label, name, reply) }
        }
    }

    /** What a provider is told: the game's title and system, the store ids the scrape knows, and the kinds wanted. Pure. */
    fun args(entry: LibraryEntry, ids: PcGameIds, name: String): JSONObject {
        val stores = JSONObject()
        ids.steamAppId?.let { stores.put("steam", it.toString()) }
        ids.gogId?.let { stores.put("gog", it) }
        ids.igdbId?.let { stores.put("igdb", it.toString()) }
        ids.steamGridDbId?.let { stores.put("steamgriddb", it.toString()) }
        val game = JSONObject().put("title", name).put("system", entry.systemId).put("ids", stores)
        return JSONObject().put("entry", game).put("kinds", JSONArray(KINDS))
    }

    private suspend fun call(context: Context, record: PluginRecord, args: JSONObject): JSONObject? {
        val policy = PluginCrashPolicy(context.applicationContext)
        return try {
            val reply = policy.handle(record, newCall(POINT, "list", "scrape", JSONObject(args.toString()), BUDGET_MS), timeoutMs = BUDGET_MS, crashOnTimeout = false, userInitiated = false)
            reply.takeIf { it.ok }?.data
        } finally {
            policy.shutdown()
        }
    }

    /**
     * A provider's `list` answer (`{items: [{kind, url, w?, h?, lang?}]}`) as one scrape candidate: the first https
     * picture of each kind, a picture in the device's language before one in another (one with none counts as any).
     * A `wide` picture fills the landscape slot when there is no `hero`. Null when it gives none. Pure.
     */
    fun matchOf(label: String, name: String, reply: JSONObject, language: String = java.util.Locale.getDefault().language): PcMatch? {
        val items = reply.optJSONArray("items") ?: return null
        val byKind = LinkedHashMap<String, MutableList<Pair<String, String?>>>()
        for (i in 0 until minOf(items.length(), 200)) {
            val item = items.optJSONObject(i) ?: continue
            val kind = item.optString("kind", "").lowercase().takeIf { it in KINDS } ?: continue
            val url = item.optString("url", "").trim().takeIf { it.startsWith("https://") && it.length <= 2000 } ?: continue
            byKind.getOrPut(kind) { mutableListOf() } += url to item.optString("lang", "").lowercase().takeIf { it.isNotBlank() }
        }
        fun best(kind: String): String? {
            val offered = byKind[kind] ?: return null
            return (offered.firstOrNull { it.second == language } ?: offered.firstOrNull { it.second == null } ?: offered.first()).first
        }
        val match = PcMatch(
            name = name,
            sourceLabel = label,
            coverUrl = best("cover"),
            heroUrl = best("hero") ?: best("wide"),
            logoUrl = best("logo"),
            iconUrl = best("icon"),
        )
        return match.takeIf { it.coverUrl != null || it.heroUrl != null || it.logoUrl != null || it.iconUrl != null }
    }
}
