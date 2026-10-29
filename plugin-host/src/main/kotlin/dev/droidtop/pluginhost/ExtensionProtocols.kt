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
        if (target.kind !in targets(entry)) return false
        val systems = strings(extra, "systems")
        if (systems.isNotEmpty() && target.systemId !in systems) return false
        val packages = strings(extra, "packages")
        if (packages.isNotEmpty() && target.packageName !in packages) return false
        return true
    }

    /** The kinds of thing the entry acts on (`targets`, default `game`). */
    fun targets(entry: ProvidedPoint): List<String> =
        strings(runCatching { JSONObject(entry.extra) }.getOrDefault(JSONObject()), "targets").ifEmpty { listOf("game") }

    /** True when the entry asks to run as a job (`"job": true` in its own fields, started through `startJob`) instead of a quick call. */
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

/**
 * The systems a `library.sources` entry serves (docs/plugin-api.md 1.6, Droidtop/tracker#543): the entry's static
 * `systems`, a list of droidtop system ids or `"*"` for every system. Decided from the manifest alone, so a Get games
 * page never loads a plugin to learn whether to list it. An entry without a usable `systems` is [UNDECLARED]: it keeps
 * the behaviour every source had before the field existed (listed under every system, asked by every search).
 */
class SourceSystems private constructor(private val ids: Set<String>?, private val declared: Boolean) {
    val isDeclared: Boolean get() = declared

    /** True when the entry names every system (`"*"`) or says nothing. */
    val isAll: Boolean get() = !declared || ids == null

    /** The system ids the entry names, empty for "every system" and for an undeclared entry. */
    val named: Set<String> get() = ids.orEmpty()

    /**
     * Whether the entry serves [systemId]. A blank or null id is a search with no system of its own (the launcher's
     * search): every source is asked. [PC] is the PC library, which spans several system ids ([PC_FAMILY]): an entry
     * serves it when it names any of them. An entry naming `pc` serves every PC system.
     */
    fun serves(systemId: String?): Boolean {
        if (isAll) return true
        val id = systemId?.trim()?.lowercase().orEmpty()
        if (id.isEmpty()) return true
        val named = ids.orEmpty()
        if (id in named) return true
        if (id == PC) return named.any { it in PC_FAMILY }
        return PC in named && id in PC_FAMILY
    }

    /** True when the entry serves the PC library and no console system: its page can open straight on the PC search. */
    fun servesOnlyPc(): Boolean = !isAll && ids.orEmpty().all { it in PC_FAMILY }

    companion object {
        /** The PC library's pseudo system id (the PC Games list). */
        const val PC = "pc"

        /** The system ids that are the PC library's games: a source naming one of them serves PC searches. */
        val PC_FAMILY: Set<String> = setOf("pc", "windows", "windows3x", "windows9x", "dos", "linux", "mac", "macos")

        val UNDECLARED = SourceSystems(null, declared = false)
        val ALL = SourceSystems(null, declared = true)

        /** Reads a `systems` value: `"*"`, or a list of ids (a `"*"` in it means all). Anything else, or an empty list, is undeclared. */
        fun parse(value: Any?): SourceSystems {
            val list: List<String> = when (value) {
                is String -> listOf(value)
                is org.json.JSONArray -> List(value.length()) { value.optString(it) }
                else -> return UNDECLARED
            }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (list.isEmpty()) return UNDECLARED
            if ("*" in list) return ALL
            return SourceSystems(list.toSet(), declared = true)
        }

        fun of(vararg ids: String) = SourceSystems(ids.map { it.trim().lowercase() }.toSet(), declared = true)

        /** The `systems` of one `library.sources` entry. */
        fun of(entry: ProvidedPoint): SourceSystems =
            runCatching { parse(JSONObject(entry.extra).opt("systems")) }.getOrDefault(UNDECLARED)

        /** The systems a plugin's manifest says its sources serve: the union of its `library.sources` entries, undeclared when there is none or any entry says nothing. */
        fun of(manifest: PluginManifest): SourceSystems {
            val entries = manifest.v2.provides.filter { it.point == "library.sources" }
            if (entries.isEmpty()) return UNDECLARED
            val each = entries.map { of(it) }
            if (each.any { !it.isDeclared }) return UNDECLARED
            if (each.any { it.isAll }) return ALL
            return SourceSystems(each.flatMap { it.named }.toSet(), declared = true)
        }
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
    fun bestCandidateIds(data: JSONObject): JSONObject? = (decide(data) as? MatchDecision.Match)?.candidate?.ids

    /** One `match` candidate: the ids to `fetch` it by, how sure the plugin is, and the name it goes by. */
    data class Candidate(val ids: JSONObject, val confidence: Double, val title: String?)

    /** What a `match` answer decides (docs/SPEC.md 7h, "A source's own match"). */
    sealed interface MatchDecision {
        /** One candidate is the most confident, at or above [MIN_CONFIDENCE]: it is the game. */
        data class Match(val candidate: Candidate) : MatchDecision

        /** Two or more share the top confidence: none is taken, and the person picks among them ("Choose matches"). */
        data class Ambiguous(val candidates: List<Candidate>) : MatchDecision

        data object None : MatchDecision
    }

    /** Every candidate of a `match` answer that has ids, most confident first (a candidate with no confidence counts as 0.5). */
    fun candidates(data: JSONObject): List<Candidate> {
        val array = data.optJSONArray("candidates") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val candidate = array.optJSONObject(i) ?: return@mapNotNull null
            val ids = candidate.optJSONObject("ids")?.takeIf { it.length() > 0 } ?: return@mapNotNull null
            Candidate(ids, candidate.optDouble("confidence", MIN_CONFIDENCE).coerceIn(0.0, 1.0), text(candidate, "title"))
        }.sortedByDescending { it.confidence }
    }

    /**
     * The scrape's rule for a `match` answer, the same as for its built-in sources: one candidate clearly first is
     * taken; a tie at the top is never guessed between, so the person picks; nothing at or above [MIN_CONFIDENCE] is
     * no match. Candidates with the same ids count once.
     */
    fun decide(data: JSONObject): MatchDecision {
        val usable = candidates(data).filter { it.confidence >= MIN_CONFIDENCE }.distinctBy { it.ids.toString() }
        if (usable.isEmpty()) return MatchDecision.None
        val top = usable.filter { it.confidence == usable.first().confidence }
        return if (top.size == 1) MatchDecision.Match(top.single()) else MatchDecision.Ambiguous(top)
    }

    /** The `title` a `fetch` answer gives, the name the source's record goes by (A3), or null. */
    fun title(data: JSONObject): String? = text(data, "title")?.takeIf { it.length <= 200 && it.none(Char::isISOControl) }

    /**
     * Whether [title] is only one of [ids]' values, alone or after one word ("214581", "Thread 214581"): a stand-in for a
     * name the source did not give, which docs/plugin-api.md 1.11 forbids (Droidtop/tracker#784). droidtop then asks the
     * source's `fetch` for the name instead of showing the id as one.
     */
    fun isIdOnly(title: String?, ids: JSONObject): Boolean {
        val shown = title?.trim()?.lowercase() ?: return false
        val values = ids.keys().asSequence().mapNotNull { ids.opt(it)?.toString()?.trim()?.lowercase()?.takeIf { v -> v.isNotEmpty() } }.toList()
        return values.any { id -> shown == id || Regex("^\\S+\\s+#?${Regex.escape(id)}$").matches(shown) }
    }

    /** The `cover` a `fetch` answer names: an https address, which the host fetches itself, or null. */
    fun cover(data: JSONObject): String? = text(data, "cover")?.takeIf { NetScope.isHttps(it) && NetScope.hostOf(it) != null }

    /**
     * The game links a `fetch` answer names (`sourceLinks: {key: id}`), kept to [ownKeys], the source keys the plugin
     * itself declared (its `library.updates` entries): a plugin links games to its own site only. Ids are one line of
     * at most 200 characters.
     */
    fun sourceLinks(data: JSONObject, ownKeys: Set<String>): Map<String, String> {
        val links = data.optJSONObject("sourceLinks") ?: return emptyMap()
        return links.keys().asSequence().filter { it in ownKeys }.mapNotNull { key ->
            text(links, key)?.takeIf { it.length <= 200 && it.none(Char::isISOControl) }?.let { key to it }
        }.toMap()
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

    /**
     * A tile's game filter (docs/plugin-api.md 3 C2, Droidtop/tracker#544 item 6): `"whileGame": true` shows it only
     * while a game is running, and `"gamePackages": [...]` (the `ui.panel` entry's field) only while one of those apps
     * runs the game. A tile with neither shows whatever runs. Pure: [gameRunning] and [runningPackage] are the host's.
     */
    fun forGame(entry: ProvidedPoint, gameRunning: Boolean, runningPackage: String?): Boolean {
        val packages = CompanionAbilities.gamePackages(entry)
        val whileGame = runCatching { JSONObject(entry.extra).optBoolean("whileGame", false) }.getOrDefault(false)
        if (packages.isEmpty() && !whileGame) return true
        if (!gameRunning) return false
        return packages.isEmpty() || runningPackage in packages
    }

    /**
     * `state` answers `{label, value, on?, severity?}`. A missing label falls back to the tile's own. The label and the
     * value are cut to the view limits' `tileLabel` and `tileValue` (32 and 16): a tile is a host row of fixed height,
     * so a long answer ellipsizes instead of growing it (Droidtop/tracker#644, "Plugins inherit it").
     */
    fun state(data: JSONObject, fallbackLabel: String): TileState = TileState(
        label = cut(data.optString("label").takeIf { it.isNotBlank() && !data.isNull("label") } ?: fallbackLabel, PluginViewLimits.length("tileLabel")),
        value = if (data.isNull("value")) null else data.optString("value").takeIf { it.isNotBlank() }?.let { cut(it, PluginViewLimits.length("tileValue")) },
        on = if (data.has("on") && !data.isNull("on")) parseBoolean(data.opt("on")) else null,
        severity = if (data.isNull("severity")) null else data.optString("severity").takeIf { it.isNotBlank() },
    )

    /** [text] at most [max] characters, an ellipsis standing for what was cut. Pure. */
    private fun cut(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1).trimEnd() + "\u2026"

    private fun parseBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.lowercase().let { if (it == "true" || it == "on") true else if (it == "false" || it == "off") false else null }
        else -> null
    }

    /** A tile with an `on` state is a toggle; one without is an action. */
    fun pressOp(state: TileState?): String = if (state?.on != null) "toggle" else "action"
}

/**
 * Rows on a game's page (`ui.game_section@1`, docs/plugin-api.md 3 C18). Which games a section is for is the same
 * static filter a context action has (`targets`, `systems`, `packages`: [ContextActionFilter.matches]), decided from
 * the manifest alone; [tab] is where on the PC game page its rows go.
 */
object GameSectionProtocol {
    const val POINT = "ui.game_section"

    /** The PC game page's tabs a section may name; anything else, or nothing, is Extras. */
    val TABS = listOf("overview", "versions", "extras", "details")

    fun tab(entry: ProvidedPoint): String =
        runCatching { JSONObject(entry.extra).optString("tab") }.getOrDefault("").takeIf { it in TABS } ?: "extras"
}

/** One shelf a plugin asked Home to show: its own id, its title, and library entry ids, in the plugin's order. */
data class PluginShelf(val id: String, val title: String, val entryIds: List<String>)

/** One library entry as a shelf call may describe it to a plugin (docs/plugin-api.md 3 C11). */
data class ShelfCandidate(
    val id: String,
    val title: String,
    val kind: String,
    val systemId: String?,
    val favorite: Boolean,
    val lastPlayedEpochMs: Long?,
)

/**
 * Shelves on Home (`gaming.rows@1`, docs/plugin-api.md 3 C11). A shelf is made of the person's own library entries,
 * named by id: droidtop draws the real entries and drops every id it does not have, so a plugin cannot put a game
 * on Home that is not in the library (A10: items that are library entries must be real entries).
 */
object PluginShelfProtocol {
    const val POINT = "gaming.rows"
    const val MAX_SHELVES = 2
    const val MAX_ITEMS = 24
    const val MAX_TITLE = 60

    /** How many entries a call describes to the plugin at most: the most recently played and added come first. */
    const val MAX_CANDIDATES = 500

    /**
     * `rows` answers `{shelves: [{id, title, entries: ["<entry id>", ...]}]}`. Keeps at most [MAX_SHELVES] shelves of
     * [MAX_ITEMS] entries, only ids in [known], each id once per shelf; a shelf left empty, or with no title, is dropped.
     */
    fun shelves(data: JSONObject, known: Set<String>): List<PluginShelf> {
        val array = data.optJSONArray("shelves") ?: return emptyList()
        val out = ArrayList<PluginShelf>()
        val seenIds = HashSet<String>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_SHELVES) break
            val shelf = array.optJSONObject(i) ?: continue
            if (shelf.isNull("title")) continue
            val title = shelf.optString("title").trim().take(MAX_TITLE).takeIf { it.isNotEmpty() } ?: continue
            val id = shelf.optString("id").trim().takeIf { it.isNotEmpty() } ?: "shelf$i"
            if (!seenIds.add(id)) continue
            val entries = shelf.optJSONArray("entries") ?: continue
            val ids = LinkedHashSet<String>()
            for (j in 0 until entries.length()) {
                if (ids.size >= MAX_ITEMS) break
                val entryId = entries.optString(j)
                if (entryId in known) ids.add(entryId)
            }
            if (ids.isNotEmpty()) out.add(PluginShelf(id, title, ids.toList()))
        }
        return out
    }

    /**
     * The `context.library` a call carries: nothing unless the plugin may read the library ([libraryRead]), and play
     * times only with [history] (`library.history`). At most [MAX_CANDIDATES] entries.
     */
    fun libraryContext(candidates: List<ShelfCandidate>, libraryRead: Boolean, history: Boolean): org.json.JSONArray? {
        if (!libraryRead) return null
        val array = org.json.JSONArray()
        candidates.take(MAX_CANDIDATES).forEach { c ->
            val json = JSONObject().put("id", c.id).put("title", c.title).put("kind", c.kind).put("favorite", c.favorite)
            c.systemId?.let { json.put("systemId", it) }
            if (history) c.lastPlayedEpochMs?.let { json.put("lastPlayed", it) }
            array.put(json)
        }
        return array
    }
}
