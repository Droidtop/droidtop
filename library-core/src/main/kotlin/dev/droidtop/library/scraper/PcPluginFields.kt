package dev.droidtop.library.scraper

import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.pluginhost.MetadataFields

/**
 * Metadata plugins' answers for one PC or engine game (docs/plugin-api.md 3 A3), merged the way the ROM pass merges
 * them: a plugin fills a field no other source filled and never replaces one, a field the person edited is never
 * written ([FieldSources.writable]), and the first plugin that has a value wins it. Pure, for the tests.
 */
internal object PcPluginFields {
    /**
     * [row] with the gaps [found] fills, and which source each filled field came from (empty when nothing changed). With
     * [replace] (a refresh with a plugin as the chosen source, Droidtop/tracker#67) a field already set is replaced too;
     * a field the person edited never is.
     */
    fun fill(row: GameMetadataEntity, found: List<MetadataFields>, replace: Boolean = false): Pair<GameMetadataEntity, Map<String, String>> {
        val sources = mutableMapOf<String, String>()
        fun <T> gap(field: String, current: T?, pick: (MetadataFields) -> T?): T? {
            if (!FieldSources.writable(row.fieldSources, field)) return current
            if (current != null && !replace) return current
            val hit = found.firstNotNullOfOrNull { fields -> pick(fields)?.let { fields.source to it } } ?: return current
            sources[field] = hit.first
            return hit.second
        }
        val filled = row.copy(
            description = gap(FieldSources.DESCRIPTION, row.description) { it.description },
            developer = gap(FieldSources.DEVELOPER, row.developer) { it.developer },
            publisher = gap(FieldSources.PUBLISHER, row.publisher) { it.publisher },
            genre = gap(FieldSources.GENRE, row.genre) { it.genre },
            releaseDate = gap(FieldSources.RELEASE_DATE, row.releaseDate) { it.releaseDate },
            players = gap(FieldSources.PLAYERS, row.players) { it.players },
            rating = gap(FieldSources.RATING, row.rating) { it.rating },
        )
        return filled to sources
    }
}
