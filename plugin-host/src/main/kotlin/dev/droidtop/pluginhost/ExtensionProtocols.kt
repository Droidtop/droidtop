package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * The wire shapes of the extension points droidtop calls into (docs/plugin-api.md
 * 3): what a call carries and how a reply is read. Kept here, pure, so the
 * call sites in the shells and the scrape pass only decide when to call, and
 * the reading of what comes back is one tested mechanism.
 */

/** What a context action is offered on: a game, an app, ... (C4). */
data class ContextTarget(
    val kind: String,
    val id: String,
    val title: String,
    val systemId: String? = null,
    val packageName: String? = null,
)

object ContextActionFilter {
    private fun strings(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { array.optString(it) }
    }

    /**
     * The static filter of a `ui.context_action` entry (docs/plugin-api.md 3 C4):
     * `targets` (default `game`), `systems` and `packages`. Decided from the manifest
     * alone, so a menu never loads a plugin to learn whether to show an action.
     */
    fun matches(entry: ProvidedPoint, target: ContextTarget): Boolean {
        val extra = runCatching { JSONObject(entry.extra) }.getOrDefault(JSONObject())
        val targets = strings(extra, "targets").ifEmpty { listOf("game") }
        if (target.kind !in targets) return false
        val systems = strings(extra, "systems")
        if (systems.isNotEmpty() && target.systemId !in systems) return false
        val packages = strings(extra, "packages")
        if (packages.isNotEmpty() && target.packageName !in packages) return false
        return true
    }

    /** True when the entry asks to run as a contract 1 job (`"job": true` in its own fields) instead of a quick call. */
    fun runsAsJob(entry: ProvidedPoint): Boolean =
        runCatching { JSONObject(entry.extra).optBoolean("job", false) }.getOrDefault(false)

    /**
     * The `target` argument. A game's identity (id, title, system) is handed over only
     * when [includeIdentity], which the caller sets from the plugin's `library.read` grant
     * (docs/plugin-api.md 3 C4); otherwise the plugin learns only the kind.
     */
    fun targetJson(target: ContextTarget, includeIdentity: Boolean): JSONObject {
        val json = JSONObject().put("kind", target.kind)
        if (includeIdentity) {
            json.put("id", target.id).put("title", target.title)
            target.systemId?.let { json.put("systemId", it) }
            target.packageName?.let { json.put("package", it) }
        }
        return json
    }
}

/** What a metadata source (A3) found for one game, every field optional. [source] names where it came from. */
data class MetadataFields(
    val source: String,
    val description: String?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    val releaseDate: String?,
    val players: String?,
    val rating: Float?,
) {
    val isEmpty: Boolean
        get() = listOf(description, developer, publisher, genre, releaseDate, players).all { it == null } && rating == null
}

object PluginMetadataProtocol {
    /** A candidate below this is not a match: a wrong guess is worse than none. */
    const val MIN_CONFIDENCE = 0.5

    /**
     * `match` answers `{candidates: [{ids: {...}, confidence: 0..1}]}`. The ids of the most confident candidate
     * at or above [MIN_CONFIDENCE], or null when there is none (a candidate with no confidence counts as 0.5).
     */
    fun bestCandidateIds(data: JSONObject): JSONObject? {
        val candidates = data.optJSONArray("candidates") ?: return null
        var best: JSONObject? = null
        var bestConfidence = -1.0
        for (i in 0 until candidates.length()) {
            val candidate = candidates.optJSONObject(i) ?: continue
            val ids = candidate.optJSONObject("ids") ?: continue
            val confidence = candidate.optDouble("confidence", MIN_CONFIDENCE)
            if (confidence >= MIN_CONFIDENCE && confidence > bestConfidence) {
                best = ids
                bestConfidence = confidence
            }
        }
        return best
    }

    private fun text(data: JSONObject, key: String): String? =
        if (data.isNull(key)) null else data.optString(key).trim().takeIf { it.isNotEmpty() }

    /**
     * ES-DE's own date form, `YYYYMMDDT000000`. A plugin may send that, `YYYY-MM-DD`, `YYYY-MM` or a bare
     * year; anything else is dropped rather than stored as text nobody can sort.
     */
    fun normalizeReleaseDate(raw: String?): String? {
        val value = raw?.trim() ?: return null
        Regex("""^\d{8}T\d{6}$""").matchEntire(value)?.let { return value }
        Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(value)?.let { return "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}T000000" }
        Regex("""^(\d{4})-(\d{2})$""").matchEntire(value)?.let { return "${it.groupValues[1]}${it.groupValues[2]}01T000000" }
        Regex("""^\d{4}$""").matchEntire(value)?.let { return "${value}0101T000000" }
        return null
    }

    /** `fetch` answers the fields directly; `rating` is 0..1 like the scrapers' own and is clamped. */
    fun fields(source: String, data: JSONObject): MetadataFields? {
        val fields = MetadataFields(
            source = source,
            description = text(data, "description"),
            developer = text(data, "developer"),
            publisher = text(data, "publisher"),
            genre = text(data, "genre"),
            releaseDate = normalizeReleaseDate(text(data, "releaseDate")),
            players = text(data, "players"),
            rating = if (data.has("rating") && !data.isNull("rating")) data.optDouble("rating", Double.NaN).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)?.toFloat() else null,
        )
        return fields.takeUnless { it.isEmpty }
    }
}

/** What a tile shows (C2, C3): [on] is present only for a toggle. */
data class TileState(val label: String, val value: String?, val on: Boolean?, val severity: String?)

object PluginTileProtocol {
    /**
     * A tile's `surfaces` filter: an entry with none appears on every surface that shows tiles, one that
     * lists some appears only on those (`gaming.quick_menu`).
     */
    fun onSurface(entry: ProvidedPoint, surface: String): Boolean {
        val surfaces = entry.surfaces()
        return surfaces.isEmpty() || surface in surfaces
    }

    /** `state` answers `{label, value, on?, severity?}`. A missing label falls back to the tile's own. */
    fun state(data: JSONObject, fallbackLabel: String): TileState = TileState(
        label = data.optString("label").takeIf { it.isNotBlank() && !data.isNull("label") } ?: fallbackLabel,
        value = if (data.isNull("value")) null else data.optString("value").takeIf { it.isNotBlank() },
        on = if (data.has("on") && !data.isNull("on")) parseBoolean(data.opt("on")) else null,
        severity = if (data.isNull("severity")) null else data.optString("severity").takeIf { it.isNotBlank() },
    )

    private fun parseBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.lowercase().let { if (it == "true" || it == "on") true else if (it == "false" || it == "off") false else null }
        else -> null
    }

    /** A tile with an `on` state is a toggle; one without is an action. */
    fun pressOp(state: TileState?): String = if (state?.on != null) "toggle" else "action"
}
