package dev.droidtop.library.scraper

import org.json.JSONObject

/**
 * Which source each of a game's fields came from (docs/SPEC.md 7h): the
 * record a scrape writes beside the values, per game, for ROMs and PC
 * games alike, stored as a small JSON object in
 * [dev.droidtop.library.consoles.GameMetadataEntity.fieldSources].
 *
 * Two jobs. It lets a person see where a description or a cover came
 * from (the PC surface's focused-game panel draws one line naming the
 * source of each field), and it is what makes "a scrape never
 * overwrites what you edited" true: a field whose source is [EDITED] is
 * skipped by every scrape write ([keep]).
 */
object FieldSources {

    /** The source recorded for a field changed in the metadata editor. */
    const val EDITED = "you"

    // The field names, one vocabulary for every writer and reader.
    const val DESCRIPTION = "description"
    const val DEVELOPER = "developer"
    const val PUBLISHER = "publisher"
    const val GENRE = "genre"
    const val RELEASE_DATE = "releaseDate"
    const val RATING = "rating"
    const val PLAYERS = "players"
    const val SERIES = "series"
    const val LINKS = "links"
    const val COVER = "cover"
    const val HERO = "hero"
    const val LOGO = "logo"
    const val ICON = "icon"

    /** The fields the metadata editor can change, which are the ones [EDITED] can protect. */
    val EDITABLE = listOf(DESCRIPTION, DEVELOPER, PUBLISHER, GENRE, RELEASE_DATE, RATING, PLAYERS)

    /** Human names, for the line that says where each field came from. */
    val LABELS = mapOf(
        DESCRIPTION to "Description",
        DEVELOPER to "Developer",
        PUBLISHER to "Publisher",
        GENRE to "Genre",
        RELEASE_DATE to "Release date",
        RATING to "Rating",
        PLAYERS to "Players",
        SERIES to "Series",
        LINKS to "Links",
        COVER to "Cover",
        HERO to "Hero art",
        LOGO to "Logo",
        ICON to "Icon",
    )

    fun decode(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().mapNotNull { key -> obj.optString(key, "").ifBlank { null }?.let { key to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun encode(sources: Map<String, String>): String? {
        if (sources.isEmpty()) return null
        val obj = JSONObject()
        sources.toSortedMap().forEach { (field, source) -> obj.put(field, source) }
        return obj.toString()
    }

    /**
     * [existing] with every field in [written] recorded as coming from its
     * source there. A field the person edited stays theirs, whatever the
     * scrape brought.
     */
    fun merge(existing: String?, written: Map<String, String>): String? {
        val current = decode(existing).toMutableMap()
        written.forEach { (field, source) -> if (current[field] != EDITED) current[field] = source }
        return encode(current)
    }

    /** Whether a scrape may write [field]: false once the person has edited it. */
    fun writable(existing: String?, field: String): Boolean = decode(existing)[field] != EDITED

    /**
     * The value a scrape write leaves in [field]: [scraped] when there is
     * one and the field is not the person's own, otherwise [current].
     */
    fun <T> keep(existing: String?, field: String, scraped: T?, current: T?): T? =
        if (scraped != null && writable(existing, field)) scraped else current

    /**
     * The editable fields [source] wrote and no longer stands behind, because a fresh lookup of the
     * same game at [source] answered "no match" (not a refusal): its earlier answer was another
     * game. A TheGamesDB name search used to take the API's first result, so "Pokemon - Crystal
     * Version" kept a fan game's description and date (Droidtop/tracker#251); the search was fixed,
     * but nothing took the old values back, and the console on build 1386 still showed them.
     * Fields the person edited are never in here (their source is [EDITED]).
     */
    fun retracted(existing: String?, source: String): Set<String> =
        decode(existing).filter { (field, from) -> from == source && field in EDITABLE }.keys

    /** [existing] without [fields]: they have no source any more. */
    fun withdraw(existing: String?, fields: Set<String>): String? =
        if (fields.isEmpty()) existing else encode(decode(existing) - fields)

    /**
     * The sources after the editor saved [after] over [before]: every
     * editable field whose value the person changed is now [EDITED]. A
     * field they did not touch keeps whatever source it had.
     */
    fun afterEdit(
        before: dev.droidtop.library.consoles.GameMetadataEntity?,
        after: dev.droidtop.library.consoles.GameMetadataEntity,
    ): String? {
        val sources = decode(after.fieldSources).toMutableMap()
        fun changed(field: String, old: Any?, new: Any?) {
            if (old != new) sources[field] = EDITED
        }
        changed(DESCRIPTION, before?.description, after.description)
        changed(DEVELOPER, before?.developer, after.developer)
        changed(PUBLISHER, before?.publisher, after.publisher)
        changed(GENRE, before?.genre, after.genre)
        changed(RELEASE_DATE, before?.releaseDate, after.releaseDate)
        changed(RATING, before?.rating, after.rating)
        changed(PLAYERS, before?.players, after.players)
        return encode(sources)
    }
}
