package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.GameLink
import dev.droidtop.library.LibraryEntry

/** A value and the source it came from. */
data class Sourced<out T>(val value: T, val source: String)

/**
 * Everything the scrape will write for one identified game, each field
 * with the source that won it (docs/SPEC.md 7h). [covers] is every cover
 * on offer, best first: the write keeps the first that downloads.
 */
internal data class PcRecord(
    val description: Sourced<String>? = null,
    val developer: Sourced<String>? = null,
    val publisher: Sourced<String>? = null,
    val genre: Sourced<String>? = null,
    val releaseDate: Sourced<String>? = null,
    val rating: Sourced<Float>? = null,
    val series: Sourced<String>? = null,
    val links: Sourced<List<GameLink>>? = null,
    val covers: List<Sourced<String>> = emptyList(),
    val hero: Sourced<String>? = null,
    val logo: Sourced<String>? = null,
    val icon: Sourced<String>? = null,
) {
    companion object {
        /**
         * The record [text] and [art] make together, first non-empty value
         * per field. Pure, so which source wins which field is tested.
         *
         * [text] is in text priority (IGDB, the Steam store, Lutris's
         * per-game record, then the match itself); [art] is in art priority
         * (SteamGridDB, the Steam store, IGDB, then the match itself). A
         * match's [PcMatch.sourceLabel] is the source recorded.
         */
        fun of(text: List<PcMatch>, art: List<PcMatch>): PcRecord {
            fun <T> first(from: List<PcMatch>, get: (PcMatch) -> T?): Sourced<T>? =
                from.firstNotNullOfOrNull { match -> get(match)?.let { Sourced(it, match.sourceLabel) } }
            return PcRecord(
                description = first(text) { it.description },
                developer = first(text) { it.developer },
                publisher = first(text) { it.publisher },
                genre = first(text) { it.genre },
                releaseDate = first(text) { it.releaseDate },
                rating = first(text) { it.rating },
                series = first(text) { it.series },
                links = first(text) { it.links.ifEmpty { null } },
                covers = art.flatMap { match ->
                    listOfNotNull(match.coverUrl, match.alternateCoverUrl).map { Sourced(it, match.sourceLabel) }
                }.distinctBy { it.value },
                hero = first(art) { it.heroUrl },
                logo = first(art) { it.logoUrl },
                icon = first(art) { it.iconUrl },
            )
        }
    }
}

/** An IGDB record as a candidate: every field it has, and the store ids it names. */
internal fun IgdbGameMetadata.toPcMatch() = PcMatch(
    name = name,
    sourceLabel = "IGDB",
    year = releaseDate?.take(4)?.toIntOrNull(),
    coverUrl = coverUrl,
    description = description,
    developer = developer,
    publisher = publisher,
    genre = genre,
    releaseDate = releaseDate,
    rating = rating,
    series = series,
    links = links,
    ids = PcGameIds(steamAppId = steamAppId, gogId = gogId, igdbId = id),
)

/**
 * The rest of a game once it is identified (docs/SPEC.md 7h, "All of a
 * game's flavour is scraped").
 *
 * One source searches by name ([PcScraperSource]); once a game is
 * identified -- by a store id, or by the match the name search or the
 * person chose -- every other configured source that can answer BY THAT
 * IDENTITY is asked by it: IGDB by the game's Steam or GOG id, the Steam
 * store by its app id, Lutris's per-game record by its slug, SteamGridDB
 * by its own id or the game's Steam or GOG id. None of them is asked by
 * name, so none of them can put another game's description on this one.
 * Sources with no key set are simply not asked; the one selected for the
 * name search is the one whose missing key refuses the pass.
 *
 * One instance per pass: a source that refuses a key (401, 403) is not
 * asked again in that pass, and nor is one that refused five times in a
 * row, and [notes] says so in the summary.
 */
internal class PcFlavour(context: Context) {

    private val igdb = igdbCredentials(context)
    private val steamGridDbKey = SteamGridDbPrefs.apiKey(context).ifBlank { null }

    private val lastRefusal = linkedMapOf<String, ScrapeLookup.Refused>()
    private val refusedInARow = mutableMapOf<String, Int>()
    private val silenced = mutableSetOf<String>()
    private var failed = 0

    /** IGDB's record of the game a store knows by [storeId], or null (not set up, not there, refused). */
    fun igdbByStore(storeId: PcStoreId): PcMatch? {
        val (clientId, clientSecret) = igdb ?: return null
        return ask(IGDB) { IgdbScraperClient.byStoreId(clientId, clientSecret, storeId) }?.toPcMatch()
    }

    /** [identity] with everything the other sources hold for that same game. */
    fun complete(entry: LibraryEntry, identity: PcMatch): PcRecord {
        var ids = PcGameIds.of(entry).orFrom(identity.ids)
        val igdbRecord = if (identity.sourceLabel == IGDB) identity else ids.storeId?.let { igdbByStore(it) }
        igdbRecord?.let { ids = ids.orFrom(it.ids) }
        val steamRecord = if (identity.sourceLabel == SteamStoreClient.SOURCE) {
            identity
        } else {
            ids.steamAppId?.let { appId -> ask(SteamStoreClient.SOURCE) { SteamStoreClient.appDetails(appId) } }
        }
        val lutrisRecord = ids.lutrisSlug?.let { slug ->
            ask(LUTRIS) { LutrisScraperClient.details(slug) }?.let {
                PcMatch(name = identity.name, sourceLabel = LUTRIS, description = it.description, genre = it.genre)
            }
        }
        val steamGridDbRecord = steamGridDbKey?.let { key ->
            val ref = ids.steamGridDbId?.let { SteamGridDbRef.game(it) }
                ?: ids.steamAppId?.let { SteamGridDbRef.steam(it) }
                ?: ids.gogId?.let { SteamGridDbRef.gog(it) }
            ref?.let { ask(SteamGridDbScraperClient.SOURCE) { SteamGridDbScraperClient.art(key, it) } }?.let { art ->
                PcMatch(
                    name = identity.name,
                    sourceLabel = SteamGridDbScraperClient.SOURCE,
                    coverUrl = art.gridUrl,
                    heroUrl = art.heroUrl,
                    logoUrl = art.logoUrl,
                    iconUrl = art.iconUrl,
                )
            }
        }
        return PcRecord.of(
            text = listOfNotNull(igdbRecord, steamRecord, lutrisRecord, identity).distinct(),
            art = listOfNotNull(steamGridDbRecord, steamRecord, igdbRecord, identity).distinct(),
        )
    }

    /**
     * One lookup by identity. A refusal is remembered for [notes] and never
     * becomes a value; a transport failure is counted and logged; neither
     * stops the game's other sources from being asked.
     */
    private fun <T> ask(source: String, call: () -> ScrapeLookup<T>): T? {
        if (source in silenced) return null
        val lookup = try {
            call()
        } catch (t: Exception) {
            failed++
            android.util.Log.w("droidtop.Scraper", "$source lookup failed: ${t.message}")
            return null
        }
        return when (lookup) {
            is ScrapeLookup.Found -> {
                refusedInARow[source] = 0
                lookup.value
            }
            ScrapeLookup.NoMatch -> {
                refusedInARow[source] = 0
                null
            }
            is ScrapeLookup.Refused -> {
                lastRefusal[source] = lookup
                val inARow = (refusedInARow[source] ?: 0) + 1
                refusedInARow[source] = inARow
                // A rejected key will not be accepted on the next game.
                if (lookup.httpStatus == 401 || lookup.httpStatus == 403 || inARow >= REFUSAL_ABORT_THRESHOLD) silenced += source
                null
            }
        }
    }

    /** What the summary says about these lookups: each source that refused, with its reason and fix; failures by count. */
    fun notes(): String = pcFlavourNotes(lastRefusal.values.toList(), silenced, failed)

    companion object {
        const val IGDB = "IGDB"
        const val LUTRIS = "Lutris"

        /** The IGDB (Twitch) credentials, or null when either is not set. */
        fun igdbCredentials(context: Context): Pair<String, String>? {
            val clientId = ScraperPrefs.clientId(context)
            val clientSecret = ScraperPrefs.clientSecret(context)
            return if (clientId.isBlank() || clientSecret.isBlank()) null else clientId to clientSecret
        }
    }
}

/** Pure, for the JVM tests: the flavour lookups' part of a summary. */
internal fun pcFlavourNotes(refusals: List<ScrapeLookup.Refused>, silenced: Set<String>, failed: Int): String = buildList {
    refusals.forEach { refusal ->
        val reason = refusal.reason?.let { ": $it" }.orEmpty()
        val stopped = if (silenced.any { refusal.source.startsWith(it) }) " and was not asked again" else ""
        val fix = ScraperReadiness.credentialFix(refusal)?.let { " $it" }.orEmpty()
        add("${refusal.source} refused a lookup (HTTP ${refusal.httpStatus}$reason)$stopped.$fix")
    }
    if (failed > 0) add("$failed ${if (failed == 1) "lookup" else "lookups"} for details or art could not connect.")
}.joinToString(" ")
