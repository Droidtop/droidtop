package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameTitleParser
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.esDeSystemName
import dev.droidtop.library.groupingPath
import dev.droidtop.library.toLibraryEntryKind
import dev.droidtop.pluginhost.JobHandle
import dev.droidtop.pluginhost.JobNumbers
import dev.droidtop.pluginhost.MetadataFields
import dev.droidtop.pluginhost.OpenTarget
import dev.droidtop.pluginhost.Outcome
import dev.droidtop.pluginhost.PluginMetadataProtocol
import dev.droidtop.runtime.prefs.PrefsFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// The PC/engine half of the scrape engine, built on the same model as
// the ROM half in Scrape.kt rather than beside it: user-initiated, one
// selected source at a time, ES-DE's own downloaded_media layout for
// images, the same game_metadata rows, the same "never clobber a user's
// own edits" write, and the same manual picker when the automatic match
// is not certain.
//
// It is separate from scrapeSystemArtwork for one real reason: the
// inputs are different all the way down. A ROM is a FILE with a
// No-Intro-shaped name inside a console system folder, matched by
// hashing its bytes against a database that knows that exact dump. A
// Ren'Py or Wine game is a FOLDER whose name carries a version and a
// platform tag, on a platform none of the ROM scrapers index, with no
// hash anyone has ever heard of. Sharing the ROM path's signature would
// have meant a system id it does not have and a hash that means nothing.

/**
 * The sources droidtop can search for a PC or engine title BY NAME.
 *
 * Deliberately a different list from [ScraperSource]: the ROM scrapers
 * index console dumps by platform id and file hash, and none of them
 * covers "some Ren'Py build in a folder". The selection model is the
 * same as ES-DE's, and as the ROM side's -- exactly ONE source is
 * searched by name for a given scrape, never a silent fallback chain.
 * What the other sources add is asked by the game's identity once it is
 * known, never by its name (see [PcFlavour]).
 */
enum class PcScraperSource(val key: String, val label: String) {
    /** Keyless, so it works on a fresh install with nothing configured. */
    LUTRIS("lutris", "Lutris (no account needed)"),

    /** Needs the user's own free Twitch/IGDB credentials, and in exchange returns descriptions, developer, publisher, genre, series and links. */
    IGDB("igdb", "IGDB (needs your own free API credentials)"),

    /** Needs the user's own free SteamGridDB key. Names and art only: its text comes from IGDB and the Steam store. */
    STEAMGRIDDB("steamgriddb", "SteamGridDB (needs your own free API key)"),
}

object PcScraperSourcePrefs {
    private const val KEY_SOURCE = "droidtop_pc_scraper_source"

    private fun prefs(context: Context) = PrefsFile(context, "com.android.launcher3.prefs")

    fun get(context: Context): PcScraperSource = builtin(prefs(context).getString(KEY_SOURCE, null))

    /** What is chosen, as stored: a built-in source's key or [PLUGIN_PREFIX] and a plugin's id. */
    fun key(context: Context): String = prefs(context).getString(KEY_SOURCE, null) ?: PcScraperSource.LUTRIS.key

    /** The built-in source [raw] names, Lutris for anything else. Pure. */
    fun builtin(raw: String?): PcScraperSource {
        // Lutris is the default because it is the one that works with
        // nothing configured; a default that needs credentials would
        // make a fresh install's first scrape fail for a reason the user
        // did not choose.
        return PcScraperSource.entries.firstOrNull { it.key == raw } ?: PcScraperSource.LUTRIS
    }

    fun set(context: Context, source: PcScraperSource) {
        prefs(context).putString(KEY_SOURCE, source.key)
    }

    /** What the source choice stores for a metadata plugin chosen as the source (Droidtop/tracker#67). */
    const val PLUGIN_PREFIX = "plugin:"

    /** The metadata plugin chosen as the PC source, by its id, or null when a built-in source is chosen. */
    fun plugin(context: Context): String? = pluginOf(prefs(context).getString(KEY_SOURCE, null))

    /** The plugin id the stored choice [raw] names, or null for a built-in source. Pure. */
    fun pluginOf(raw: String?): String? =
        raw?.takeIf { it.startsWith(PLUGIN_PREFIX) }?.removePrefix(PLUGIN_PREFIX)?.takeIf { it.isNotBlank() }

    /** Chooses the metadata plugin [pluginId] as the one source every PC pass asks. */
    fun setPlugin(context: Context, pluginId: String) {
        prefs(context).putString(KEY_SOURCE, PLUGIN_PREFIX + pluginId)
    }
}

/**
 * A store's id for a game, `"steam:440"` or `"gog:1207658691"`: the form
 * droidtop's PC entries are keyed by and [dev.droidtop.library.PcInfo.storeId]
 * carries.
 */
data class PcStoreId(val store: String, val id: String) {
    companion object {
        const val STEAM = "steam"
        const val GOG = "gog"

        fun parse(raw: String?): PcStoreId? {
            if (raw == null) return null
            val store = raw.substringBefore(':', "").takeIf { it.isNotEmpty() } ?: return null
            val id = raw.substringAfter(':').trim().takeIf { it.isNotEmpty() } ?: return null
            return PcStoreId(store, id)
        }
    }
}

/**
 * Every id a game is known by, gathered from wherever the scrape learned
 * it: the entry's own store id, Lutris's `provider_games`, IGDB's
 * `external_games`, the id of the database row a match came from. Each is
 * an identity, so every source that can answer by one is asked by it
 * rather than by the game's name.
 */
data class PcGameIds(
    val steamAppId: Int? = null,
    val gogId: String? = null,
    val igdbId: Long? = null,
    val steamGridDbId: Int? = null,
    val lutrisSlug: String? = null,
) {
    /** These ids, with [other]'s filling any this one lacks. */
    fun orFrom(other: PcGameIds) = PcGameIds(
        steamAppId = steamAppId ?: other.steamAppId,
        gogId = gogId ?: other.gogId,
        igdbId = igdbId ?: other.igdbId,
        steamGridDbId = steamGridDbId ?: other.steamGridDbId,
        lutrisSlug = lutrisSlug ?: other.lutrisSlug,
    )

    /** These ids as `game_ids` rows (docs/SPEC.md 7o "Saves"): the scrape learned them and nothing kept them. */
    fun asSaveIds(): Map<String, String> = buildMap {
        steamAppId?.let { put(dev.droidtop.library.saves.GameIdKind.STEAM, it.toString()) }
        gogId?.let { put(dev.droidtop.library.saves.GameIdKind.GOG, it) }
        igdbId?.let { put(dev.droidtop.library.saves.GameIdKind.IGDB, it.toString()) }
        steamGridDbId?.let { put(dev.droidtop.library.saves.GameIdKind.STEAMGRIDDB, it.toString()) }
        lutrisSlug?.let { put(dev.droidtop.library.saves.GameIdKind.LUTRIS, it) }
    }

    /** The store id IGDB is asked by: Steam first, then GOG. */
    val storeId: PcStoreId?
        get() = steamAppId?.let { PcStoreId(PcStoreId.STEAM, it.toString()) }
            ?: gogId?.let { PcStoreId(PcStoreId.GOG, it) }

    companion object {
        /** The ids an entry carries itself: its own id, or the store id a store-installed engine game keeps. */
        fun of(entry: LibraryEntry): PcGameIds {
            val stores = listOfNotNull(PcStoreId.parse(entry.id), PcStoreId.parse(entry.pcInfo?.storeId))
            return PcGameIds(
                steamAppId = stores.firstOrNull { it.store == PcStoreId.STEAM }?.id?.toIntOrNull(),
                gogId = stores.firstOrNull { it.store == PcStoreId.GOG }?.id,
            )
        }
    }
}

/**
 * One candidate a source returned, in the shape the write path needs.
 * Sources fill what they actually have and leave the rest null -- Lutris's
 * search genuinely has no description or developer field at all, and
 * inventing a plausible one would be exactly the fabrication this project
 * refuses.
 */
data class PcMatch(
    val name: String,
    val sourceLabel: String,
    val year: Int? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: Float? = null,
    /** A second cover to try only when [coverUrl] cannot be downloaded (see [SteamStoreClient]). */
    val alternateCoverUrl: String? = null,
    val series: String? = null,
    val links: List<dev.droidtop.library.GameLink> = emptyList(),
    val heroUrl: String? = null,
    val logoUrl: String? = null,
    val iconUrl: String? = null,
    /** The ids this candidate carries, which is what [PcFlavour] asks the other sources by. */
    val ids: PcGameIds = PcGameIds(),
)

/**
 * Where candidates come from. An interface so the matching logic is
 * testable without a network or a key. A search answers with the same
 * three outcomes every scraper source does ([ScrapeLookup]): candidates,
 * a real miss, or a refusal that says nothing about the game.
 */
interface PcMetadataSource {
    val label: String
    fun search(title: String): ScrapeLookup<List<PcMatch>>
}

/**
 * The search string for a PC game: its parsed title, nothing else.
 *
 * It used to be a second cleaner of folder names beside [GameNaming];
 * [GameTitleParser] is the one parser now (docs/SPEC.md 7n), so what a
 * scrape searches for is the title the library draws. A leftover word the
 * parser cannot place (a scene group, a mod name) is not part of the title
 * and is not searched for.
 */
object PcScrapeTitle {
    fun clean(folderName: String): String = GameTitleParser.parseName(folderName).title.ifBlank { folderName }
}

/**
 * Decides whether a set of candidates contains a match certain enough to
 * apply without asking.
 *
 * The bar is deliberately high, because the failure modes are not
 * symmetric: a game left unscraped is a game the user can scrape by hand
 * in ten seconds, while a wrong match writes a wrong description, a
 * wrong cover, and a wrong release date over a row the user may have
 * edited themselves. Only an exact title match (ignoring case,
 * punctuation, spacing and a leading article) counts as certain, and
 * only when exactly one candidate matches that way. Everything else is
 * handed to the user's own picker -- which is also how real ES-DE
 * behaves when its automatic mode is not confident.
 */
object PcMatching {

    sealed interface Decision {
        /** Safe to write without asking. */
        data class Confident(val match: PcMatch) : Decision

        /** Real candidates exist, but none is certain: the user picks. */
        data class Ambiguous(val matches: List<PcMatch>) : Decision

        /** The source knows nothing by this name. */
        data object None : Decision
    }

    private val LEADING_ARTICLES = setOf("the", "a", "an")

    /**
     * Two titles compare equal when they differ only by case,
     * punctuation, spacing, or a leading article -- "the-witchs-house"
     * and "The Witch's House" are the same game, and a folder name is
     * never punctuated the way a database's title is.
     */
    fun normalize(title: String): String {
        val words = title.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotBlank() }
        val withoutArticle = if (words.size > 1 && words.first() in LEADING_ARTICLES) words.drop(1) else words
        return withoutArticle.joinToString("")
    }

    fun decide(cleanedTitle: String, matches: List<PcMatch>): Decision {
        if (matches.isEmpty()) return Decision.None
        val wanted = normalize(cleanedTitle)
        if (wanted.isEmpty()) return Decision.Ambiguous(matches)
        val exact = matches.filter { normalize(it.name) == wanted }
        return if (exact.size == 1) Decision.Confident(exact.single()) else Decision.Ambiguous(matches)
    }
}

/** Where a PC/engine game's scraped media lives: ES-DE's own `downloaded_media` layout, the same one [dev.droidtop.library.EsDeArtwork] reads. */
object PcMediaLayout {
    fun coverFile(gamesRoot: File, systemFolder: String, baseName: String): File =
        mediaFile(gamesRoot, systemFolder, "covers", baseName)

    /**
     * One media file in the layout. Hero art goes to ES-DE's `fanart` and a
     * logo to its `marquees` (what ES-DE's own scraper files a wheel/logo
     * under), so a theme asking for those types finds them; an icon has no
     * ES-DE type and goes to droidtop's own `icons`, which ES-DE ignores.
     */
    fun mediaFile(gamesRoot: File, systemFolder: String, folder: String, baseName: String): File =
        File(File(File(gamesRoot, dev.droidtop.library.EsDeArtwork.MEDIA_DIR), systemFolder), "$folder/$baseName.png")

    /** [name] with every character FAT/exFAT refuses in a file name replaced by a space, collapsed. */
    fun fileSafe(name: String): String =
        name.map { if (it in "\\/:*?\"<>|" || it < ' ') ' ' else it }.joinToString("")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.').trim().ifBlank { "game" }

    /**
     * The `downloaded_media` system folder for an entry: a console
     * system id when the entry has one (PC store/Wine entries use ES-DE's
     * own `"pc"`), otherwise the engine's own folder name.
     */
    fun systemFolderFor(entry: LibraryEntry): String? =
        entry.systemId
            ?: GameEngine.entries.firstOrNull { it.toLibraryEntryKind() == entry.kind }?.esDeSystemName()
            ?: if (entry.kind == LibraryEntryKind.WINE_PROFILE) "pc" else null
}

/**
 * Whether this entry is one the PC/engine scrape can work on: a detected
 * engine game, or a PC title (a store install or a Wine shortcut).
 *
 * The single definition of that question -- the shell gates its Scrape
 * and Choose-match actions on exactly the same predicate the scrape
 * itself uses, so an action can never be offered for something the
 * scrape would then refuse.
 */
val LibraryEntry.isPcOrEngineGame: Boolean
    get() = kind == LibraryEntryKind.WINE_PROFILE || GameEngine.entries.any { it.toLibraryEntryKind() == kind }

/**
 * The PC/engine scrape itself.
 *
 * Every entry point returns a sentence rather than throwing, for the
 * same reason the ROM path does: these run from a menu on the main
 * screen with the result shown in place, and "TheGamesDB needs its API
 * key" is a useful thing to read where "IOException" is not.
 */
object PcScraper {

    /**
     * The order a pass works through games in, and the checkpoint's key: every game by id, store and folder games
     * together (docs/SPEC.md 7h, "The whole library"). Store games used to come first, and a pass the Steam store
     * stopped never reached a folder game. The "1|" is what the folder games' keys carried then, so a checkpoint
     * saved by that order still resumes in place: a "1|" one after the same game, a "0|" one from the start.
     */
    internal fun orderKey(entry: LibraryEntry): String = "1|" + entry.id

    /**
     * Whether [filter] picks a game for a pass, given its metadata [row] and whether it has a cover ([hasCover]
     * decides). Pure, for the JVM tests.
     *
     * A PC or engine game has its details when it has a description: an engine's detection or a store install fills
     * a developer or a genre on its own, and a game with those and no description is a game nobody has looked up
     * (a Ren'Py folder game skipped as "already has details" is how folder games went unscraped). It has its art
     * when it has a cover, nothing less: the picture a game shows without one (an .exe's icon, a store's icon, a
     * screenshot or miximage in downloaded_media) is not what "Games missing artwork" asks about (console,
     * 2026-10-17: the filter said "nothing matches" while F95 folder games had no cover, because any picture
     * counted).
     */
    internal fun picked(row: GameMetadataEntity?, hasCover: Boolean, filter: ScrapeFilter): Boolean {
        val noMeta = row?.description.isNullOrBlank()
        val noArt = !hasCover
        return when (filter) {
            ScrapeFilter.MISSING_ANY -> noArt || noMeta
            ScrapeFilter.MISSING_ARTWORK -> noArt
            ScrapeFilter.MISSING_METADATA -> noMeta
            ScrapeFilter.FAVORITES -> row?.favorite == true
            ScrapeFilter.ALL -> true
        }
    }

    /**
     * The scrape confidence a game a pass could not decide carries until it is matched (docs/SPEC.md 7h, "Games
     * that need your pick"): candidates came back, none exact. A match of any kind (by id, by name, or the
     * person's pick, "manual") replaces it; a later pass that finds nothing at all clears it.
     */
    const val NEEDS_PICK = "needs_pick"

    /** The PC Games options row that lists [needingPick], named here so the pass's summary can point at it. */
    /** The review queue's name, on the options row that opens it and in a pass's summary (Droidtop/tracker#318 §5). */
    const val REVIEW_MATCHES = "Review matches"

    /**
     * How many games wait in Review Matches: the queued ones still in the library, and the ones a pass marked
     * [NEEDS_PICK] before the queue kept answers, less those marked Local only. Database work: off the main thread.
     */
    suspend fun waitingForReview(context: Context): Int = withContext(Dispatchers.IO) {
        val games = LibraryScrapeJob.libraryGames(context).filter { it.isPcOrEngineGame && !it.missing }.map { it.id }.toSet()
        val queued = dev.droidtop.library.identity.MatchQueue.items(context).map { it.gameId }.filter { it in games }
        val older = needingPick(context).map { it.id }.filter { !dev.droidtop.library.identity.MatchQueue.isLocalOnly(context, it) }
        (queued + older).toSet().size
    }

    /** The library's PC and engine games a pass left at [NEEDS_PICK], by title. Database work: call it off the main thread. */
    suspend fun needingPick(context: Context): List<LibraryEntry> = withContext(Dispatchers.IO) {
        val ids = RomDatabase.get(context).romDao().idsWithScrapeConfidence(NEEDS_PICK).toSet()
        if (ids.isEmpty()) return@withContext emptyList()
        LibraryScrapeJob.libraryGames(context)
            .filter { it.id in ids && it.isPcOrEngineGame && !it.missing }
            .sortedBy { it.title.lowercase() }
    }

    /**
     * Records that [entry] needs a pick ([needs]), or that it no longer does. Never over a game already matched:
     * a game the person picked a match for stays matched when a later pass finds it ambiguous again.
     */
    private suspend fun markNeedsPick(context: Context, entry: LibraryEntry, needs: Boolean) {
        val dao = RomDatabase.get(context).romDao()
        val row = dao.getGameMetadataSingle(entry.id)
        when {
            needs && (row?.scrapeConfidence == null) ->
                dao.upsertGameMetadata((row ?: GameMetadataEntity(id = entry.id)).copy(scrapeConfidence = NEEDS_PICK))
            !needs && row?.scrapeConfidence == NEEDS_PICK -> dao.upsertGameMetadata(row.copy(scrapeConfidence = null))
        }
    }

    /** Games are asked about at least this far apart: the Steam store allows about 200 requests in five minutes. */
    private const val GAME_PACE_MS = 1_600L

    /** How many ids one metadata read asks for (SQLite's variable limit is 999 on older Android). */
    private const val METADATA_READ_CHUNK = 500

    /** The live source for the current selection, or null when it is not usable (see [ScraperReadiness.pcSourceProblem]). */
    fun source(context: Context, key: String = PcScraperSourcePrefs.key(context)): PcMetadataSource? = when (PcScraperSourcePrefs.builtin(key)) {
        PcScraperSource.LUTRIS -> LutrisSource
        PcScraperSource.IGDB -> PcFlavour.igdbCredentials(context)?.let { (id, secret) -> IgdbSource(id, secret) }
        PcScraperSource.STEAMGRIDDB -> SteamGridDbPrefs.apiKey(context).ifBlank { null }?.let { SteamGridDbSource(it) }
    }

    private object LutrisSource : PcMetadataSource {
        override val label = "Lutris"
        override fun search(title: String): ScrapeLookup<List<PcMatch>> =
            LutrisScraperClient.search(title).mapFound { results ->
                results.map { result ->
                    PcMatch(
                        name = result.name,
                        sourceLabel = label,
                        year = result.year,
                        coverUrl = result.coverUrl,
                        // Lutris's search carries no description, developer,
                        // publisher, genre or rating at all, and its `year`
                        // is a year with no month or day. ES-DE's own MD_DATE
                        // is a full "YYYYMMDDT000000" string, so writing one
                        // would mean inventing a January 1st that Lutris
                        // never said: the year is shown in the picker, where
                        // it helps a person choose, and nothing is written.
                        // Its description and genres come from its per-game
                        // record, asked by slug once this match is chosen.
                        ids = PcGameIds(steamAppId = result.steamAppId, gogId = result.gogId, lutrisSlug = result.slug.ifBlank { null }),
                    )
                }
            }
    }

    private class IgdbSource(private val clientId: String, private val clientSecret: String) : PcMetadataSource {
        override val label = "IGDB"
        override fun search(title: String): ScrapeLookup<List<PcMatch>> =
            IgdbScraperClient.search(clientId, clientSecret, title).mapFound { results -> results.map { it.toPcMatch() } }
    }

    private class SteamGridDbSource(private val apiKey: String) : PcMetadataSource {
        override val label = SteamGridDbScraperClient.SOURCE
        override fun search(title: String): ScrapeLookup<List<PcMatch>> =
            SteamGridDbScraperClient.search(apiKey, title).mapFound { games ->
                // Names and years only: the art is asked for once a match
                // is chosen, not ten grids for ten candidates.
                games.map { PcMatch(name = it.name, sourceLabel = label, year = it.year, ids = PcGameIds(steamGridDbId = it.id)) }
            }
    }

    /** What a manual picker needs: candidates to show, or the reason there are none. */
    sealed interface Candidates {
        data class Found(val matches: List<PcMatch>) : Candidates
        data class Unavailable(val message: String) : Candidates
    }

    /**
     * Candidates for one entry, for the manual picker. Never applies
     * anything: the user chooses, then [apply] writes.
     */
    suspend fun candidates(context: Context, entry: LibraryEntry): Candidates = withContext(Dispatchers.IO) {
        ScraperReadiness.pcSourceProblem(context)?.let { return@withContext Candidates.Unavailable(it) }
        val source = source(context) ?: return@withContext Candidates.Unavailable("No PC scraper source is configured.")
        val title = searchTitleFor(entry)
        val lookup = runCatching { source.search(title) }.getOrElse { error ->
            return@withContext Candidates.Unavailable("${source.label} search failed: ${error.message}")
        }
        when (lookup) {
            is ScrapeLookup.Found -> if (lookup.value.isEmpty()) {
                Candidates.Unavailable("${source.label} has nothing under \"$title\".")
            } else {
                Candidates.Found(lookup.value)
            }
            ScrapeLookup.NoMatch -> Candidates.Unavailable("${source.label} has nothing under \"$title\".")
            // A refusal is not an empty result (docs/SPEC.md section 7h).
            is ScrapeLookup.Refused -> Candidates.Unavailable(
                "${lookup.source} refused the search (HTTP ${lookup.httpStatus})" +
                    (lookup.reason?.let { ": $it." } ?: ".") +
                    (ScraperReadiness.credentialFix(lookup)?.let { " $it" } ?: ""),
            )
        }
    }

    /**
     * Writes one match the USER picked. Replaces the cover (the previous
     * one is the picture of the game they just rejected) and overwrites
     * the scraper-owned fields only -- favourites, collections, and
     * anything edited in the metadata editor survive untouched, exactly
     * as [applyManualMatch] does for ROMs.
     *
     * The picked match is an identity like any other, so the rest of the
     * game's flavour and art is asked for by it ([PcFlavour]) before the
     * write, as in the automatic pass.
     */
    suspend fun apply(context: Context, entry: LibraryEntry, match: PcMatch): String = withContext(Dispatchers.IO) {
        val flavour = PcFlavour(context)
        val record = flavour.complete(entry, match)
        write(context, entry, record, confidence = "manual", replaceExisting = true)
        dev.droidtop.library.identity.MatchQueue.clear(context, entry.id)
        // The pick teaches its name (Droidtop/tracker#318 §6.4): the next folder that searches it is matched without asking.
        dev.droidtop.library.identity.MatchAliases.learn(context, searchTitleFor(entry), match)
        "Matched ${entry.title} to ${match.name} (${match.sourceLabel})." + flavour.notes().let { if (it.isEmpty()) "" else " $it" }
    }

    /**
     * The automatic pass over [entries] -- the equivalent of the ROM
     * side's [scrapeSystemArtwork], honouring the same
     * [ScrapeOptionsPrefs] filter and content toggles.
     *
     * Anything the source is not certain about is COUNTED, not applied;
     * the summary says how many are waiting for a manual match so the
     * user knows there is something to do rather than assuming the
     * scrape simply found nothing.
     */
    suspend fun scrape(
        context: Context,
        entries: List<LibraryEntry>,
        // The library-scrape job's checkpoint (docs/SPEC.md 12a "Jobs"): the [orderKey] of the last game
        // the previous run finished; the games are worked through in [orderKey] order.
        resumeAfter: String? = null,
        // Told (with the game's [orderKey]) after each game has been fully handled, never for one a pause or cancel interrupted.
        onEntryDone: (String) -> Unit = {},
        // Told when the source refused every request this pass made, so a combined run does not go on asking.
        onRefusedEverything: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        // A game the person asked to scrape by name (its page or menu) is scraped whatever the library filter says;
        // the filter is for passes over the library (docs/SPEC.md 7h).
        filter: ScrapeFilter? = null,
        // The automatic pass (PcAutoScrape) only fills gaps (Droidtop/tracker#318 §7.2).
        auto: Boolean = false,
        // "Rescrape with…" (Droidtop/tracker#67): the source this pass asks instead of the chosen one, as
        // [PcScraperSourcePrefs.key] stores a choice. The setting is left as it is.
        using: String? = null,
    ): String = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext "PC and engine games: none in the library."
        val key = using ?: PcScraperSourcePrefs.key(context)
        ScraperReadiness.pcSourceProblem(context, key)?.let { return@withContext it }
        // A metadata plugin chosen as the source is the one source the pass asks (Droidtop/tracker#67): no built-in
        // search, as with any other choice.
        val chosen = chosenPlugin(context, key)
        val source = if (chosen != null) PluginChosenSource(chosen.label) else source(context, key) ?: return@withContext "No PC scraper source is configured."
        val wantMetadata = ScrapeOptionsPrefs.scrapeMetadata(context)
        val wantArtwork = ScrapeOptionsPrefs.scrapeArtwork(context)
        if (!wantMetadata && !wantArtwork) {
            return@withContext "Both content types are disabled in scrape options."
        }

        val dao = RomDatabase.get(context).romDao()
        // A library is thousands of entries, and one IN list that long can pass SQLite's limit on
        // variables; the rows are read in slices.
        val ordered = entries.sortedBy(::orderKey)
        val existing = ordered.map { it.id }.chunked(METADATA_READ_CHUNK)
            .flatMap { dao.getGameMetadata(it) }.associateBy { it.id }
        val filter = filter ?: ScrapeOptionsPrefs.filter(context)
        // Only a pass the person started over everything (a game's own Scrape, or "Everything") replaces what a game
        // already has; any other pass fills what is missing (Droidtop/tracker#318 §7.2).
        val refresh = !auto && filter == ScrapeFilter.ALL
        val candidates = if (resumeAfter == null) ordered else ordered.filter { orderKey(it) > resumeAfter }
        val (targets, passedOver) = candidates.partition { entry ->
            val row = existing[entry.id]
            picked(row, hasCover = hasCover(context, entry, row), filter = filter)
        }
        // Every game the pass was given leaves a line (docs/SPEC.md 7h, "Names and reasons"), the ones the
        // filter passed over too, so a folder game missing from the log is a game the library never handed over.
        for (entry in passedOver) {
            dev.droidtop.library.ScanLog.write("scrape: ${entry.title} [pc]: not asked, it already has what the \"${filter.label}\" filter looks for")
            reported(entry, Outcome.SKIPPED, "Already has what \"${filter.label}\" looks for")
        }
        if (targets.isEmpty()) {
            return@withContext if (resumeAfter != null && candidates.isEmpty()) {
                "PC and engine games: nothing was left to scrape."
            } else {
                "PC and engine games: nothing matches the \"${filter.label}\" scrape filter."
            }
        }

        val counts = PcScrapeCounts(targeted = targets.size)
        val flavour = PcFlavour(context)
        // Metadata plugins (docs/plugin-api.md 3 A3), the same way the ROM pass asks them: after every built-in source,
        // filling only what those left empty. One scrape path for ROMs and PC and engine games.
        val plugins = when {
            !wantMetadata -> null
            chosen != null -> dev.droidtop.library.integrations.PluginMetadataSources.Session(context, listOf(chosen))
            else -> dev.droidtop.library.integrations.PluginMetadataSources.open(context)
        }
        // The same rule as the ROM pass, kept per source: several refusals in a row are about that server, not about
        // any game. The selected source refusing ends the pass; the Steam store refusing only stops the store games'
        // lookups by app id for the rest of it, so the folder games after them are still asked.
        var refusedInARow = 0
        var steamInARow = 0
        var steamStopped: ScrapeLookup.Refused? = null
        for ((index, entry) in targets.withIndex()) {
            // A pause or cancel lands here, between games, never inside one.
            kotlin.coroutines.coroutineContext.ensureActive()
            if (refusedInARow >= REFUSAL_ABORT_THRESHOLD) {
                for (left in targets.drop(index)) {
                    dev.droidtop.library.ScanLog.write("scrape: ${left.title} [pc] via ${source.label}: not asked, ${source.label} refused $REFUSAL_ABORT_THRESHOLD in a row")
                    reported(left, Outcome.FAILED, "Not asked: ${source.label} refused $REFUSAL_ABORT_THRESHOLD times in a row")
                }
                break
            }
            onProgress(index, targets.size)
            JobHandle.current()?.numbers(JobNumbers(itemsDone = index, itemsTotal = targets.size))
            // The sources are public services with their own limits (the Steam store allows about
            // 200 requests in five minutes) and one game can ask several of them: games are spaced.
            if (index > 0) kotlinx.coroutines.delay(GAME_PACE_MS)
            counts.attempted++
            try {
                var outcome = scrapeOne(context, entry, source, flavour, steamStopped, refresh, searchByName = chosen == null)
                // A plugin source whose best candidates tie leaves the game for the person to pick, as a built-in one does.
                val fill = plugins?.let { fillFromPlugins(context, entry, it, replace = refresh && chosen != null, always = chosen != null) }
                val pluginTie = fill?.tie ?: false
                // The chosen plugin's answer is the pass's answer for a game its store id did not settle.
                if (chosen != null && outcome is PcOutcome.NoMatch) {
                    outcome = when {
                        fill?.matched == true -> PcOutcome.ByName
                        pluginTie -> PcOutcome.NeedsPicking(outcome.searched, 0)
                        else -> outcome
                    }
                }
                logOutcome(source.label, entry, outcome)
                if (outcome is PcOutcome.Refused) {
                    counts.lastRefusal = outcome.refusal
                    when {
                        !outcome.asked -> Unit
                        // The selected source is never the Steam store, so a store refusal is one by app id.
                        outcome.refusal.source == SteamStoreClient.SOURCE ->
                            if (++steamInARow >= REFUSAL_ABORT_THRESHOLD) steamStopped = outcome.refusal
                        else -> refusedInARow++
                    }
                } else {
                    refusedInARow = 0
                    if (outcome == PcOutcome.ByStoreId) steamInARow = 0
                }
                // Review Matches (Droidtop/tracker#318 §5): a game not taken waits with what the source answered; a match takes it off.
                when (outcome) {
                    PcOutcome.ByStoreId, PcOutcome.ByName -> dev.droidtop.library.identity.MatchQueue.clear(context, entry.id)
                    is PcOutcome.NeedsPicking -> dev.droidtop.library.identity.MatchQueue.put(context, entry, source.label, outcome.searched, outcome.matches)
                    is PcOutcome.NoMatch -> if (!pluginTie) dev.droidtop.library.identity.MatchQueue.put(context, entry, source.label, outcome.searched, emptyList())
                    is PcOutcome.Refused -> Unit
                }
                when (outcome) {
                    PcOutcome.ByStoreId -> counts.byStoreId++
                    PcOutcome.ByName -> counts.byName++
                    is PcOutcome.NeedsPicking -> {
                        markNeedsPick(context, entry, needs = true)
                        counts.needsPicking++
                        counts.misses.add(ScrapeMisses.Kind.NEEDS_PICKING, entry.title, "${outcome.candidates} candidates")
                    }
                    is PcOutcome.NoMatch -> if (pluginTie) {
                        markNeedsPick(context, entry, needs = true)
                        counts.needsPicking++
                        counts.misses.add(ScrapeMisses.Kind.NEEDS_PICKING, entry.title, "a plugin source's candidates tie")
                    } else {
                        markNeedsPick(context, entry, needs = false)
                        counts.noMatch++
                        counts.misses.add(ScrapeMisses.Kind.NO_MATCH, entry.title)
                    }
                    is PcOutcome.Refused -> counts.refused++
                }
                reportOutcome(entry, outcome, pluginTie)
            } catch (t: Exception) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                counts.failed++
                refusedInARow = 0
                counts.misses.add(ScrapeMisses.Kind.FAILED, entry.title, t.message)
                reported(entry, Outcome.FAILED, t.message ?: "Failed")
                dev.droidtop.library.ScanLog.write("scrape: ${entry.title} [pc] via ${source.label}: failed: ${t.message}")
                android.util.Log.e("droidtop.Scraper", "Failed to scrape ${entry.title}", t)
            }
            onEntryDone(orderKey(entry))
        }
        if (counts.attempted > 0 && counts.refused == counts.attempted) onRefusedEverything()
        plugins?.close()
        counts.flavourNotes = flavour.notes()
        formatPcScrapeSummary(source.label, counts).also {
            dev.droidtop.library.ScanLog.write("scrape: PC and engine games summary: ${it.replace('\n', ' ')}")
        }
    }

    /**
     * One line of the scrape job's report (docs/SPEC.md "Jobs and their results"): the game, what happened to it in
     * plain words, and its page to open. The same facts as its scan.log line, said for a person; nothing outside a job.
     */
    private suspend fun reported(entry: LibraryEntry, outcome: Outcome, detail: String) {
        JobHandle.current()?.item(entry.title, outcome, detail, OpenTarget.Game(entry.id))
    }

    /** The report line for a game that was not written; a written game reports itself in [write]. */
    private suspend fun reportOutcome(entry: LibraryEntry, outcome: PcOutcome, pluginTie: Boolean) {
        when (outcome) {
            PcOutcome.ByStoreId, PcOutcome.ByName -> Unit
            is PcOutcome.NeedsPicking -> reported(entry, Outcome.NEEDS_PICK, "${outcome.candidates} possible matches, none exact")
            is PcOutcome.NoMatch -> if (pluginTie) {
                reported(entry, Outcome.NEEDS_PICK, "A source's possible matches tie")
            } else {
                reported(entry, Outcome.SKIPPED, "No match for \"${outcome.searched}\"")
            }
            is PcOutcome.Refused -> reported(
                entry,
                Outcome.FAILED,
                if (outcome.asked) {
                    "${outcome.refusal.source} refused (HTTP ${outcome.refusal.httpStatus})" + (outcome.refusal.reason?.let { ": $it" } ?: "")
                } else {
                    "Not asked: ${outcome.refusal.source} refused $REFUSAL_ABORT_THRESHOLD times in a row"
                },
            )
        }
    }

    /** One scan.log line per game that was not written, naming the game and why; a written game logs itself in [write]. */
    private fun logOutcome(sourceLabel: String, entry: LibraryEntry, outcome: PcOutcome) {
        val what = when (outcome) {
            PcOutcome.ByStoreId, PcOutcome.ByName -> return
            is PcOutcome.NoMatch -> "no match for \"${outcome.searched}\""
            is PcOutcome.NeedsPicking -> "${outcome.candidates} candidates, none exact for \"${outcome.searched}\": needs a pick"
            is PcOutcome.Refused -> if (outcome.asked) {
                "refused, HTTP ${outcome.refusal.httpStatus}" + (outcome.refusal.reason?.let { ": $it" } ?: "")
            } else {
                "not asked, the ${outcome.refusal.source} refused $REFUSAL_ABORT_THRESHOLD in a row (HTTP ${outcome.refusal.httpStatus})"
            }
        }
        dev.droidtop.library.ScanLog.write("scrape: ${entry.title} [pc] via $sourceLabel: $what")
    }

    /**
     * The facts a metadata plugin gets for [entry] (docs/plugin-api.md 3 A3): the title the scrape searched, the folder's
     * name (as `fileName` and `folder.name`, so a source can search by either), the system folder, and the game's source
     * links (docs/SPEC.md 7g), so a source the person or an earlier scrape linked the game to answers for exactly that
     * record.
     */
    private suspend fun pluginFacts(context: Context, entry: LibraryEntry): org.json.JSONObject {
        val facts = org.json.JSONObject().put("title", searchTitleFor(entry))
        entry.groupingPath()?.let { path ->
            val name = File(path).name
            facts.put("fileName", name)
            facts.put("folder", org.json.JSONObject().put("name", name))
        }
        PcMediaLayout.systemFolderFor(entry)?.let { facts.put("systemId", it) }
        val links = dev.droidtop.library.RoomGameLinksStore(context).getAll(listOf(entry.id))[entry.id]?.sources.orEmpty()
        if (links.isNotEmpty()) facts.put("sourceLinks", org.json.JSONObject(links.associate { it.source to it.externalId }))
        return facts
    }

    /**
     * What metadata plugins know about [entry], written into the fields the built-in sources left empty, each recorded
     * under the plugin's name (docs/SPEC.md 7h). A source that matched also links the game to its record
     * (`sourceLinks`, docs/SPEC.md 7h "A source's own match"), so the next scrape and the source's own update checks
     * find it without a search. Returns whether a source's best candidates tied: the game then needs the person's pick.
     */
    private suspend fun fillFromPlugins(
        context: Context,
        entry: LibraryEntry,
        plugins: dev.droidtop.library.integrations.PluginMetadataSources.Session,
        // A refresh with the plugin as the chosen source replaces what is there (Droidtop/tracker#67).
        replace: Boolean = false,
        // The plugin is the chosen source: asked even when the built-in fields are all filled.
        always: Boolean = false,
    ): PluginFill {
        // Nothing left for a source to fill: it is not asked (docs/SPEC.md 7h, "Fewer requests").
        if (!always && !PluginScrapeMemory.wantsPluginFields(RomDatabase.get(context).romDao().getGameMetadataSingle(entry.id))) return PluginFill(tie = false, matched = false)
        val facts = pluginFacts(context, entry)
        val linked = facts.optJSONObject("sourceLinks")?.keys()?.asSequence()?.toSet().orEmpty()
        val now = System.currentTimeMillis()
        // A source that found nothing for this game in the last week is not asked again, unless the game has since
        // been linked to it (the link answers without a search).
        val answers = plugins.answers(facts) { source ->
            source.ownLinkKeys.none { it in linked } && PluginScrapeMemory.recentlyMissed(context, source.record.manifest.id, entry.id, now)
        }
        answers.forEach { PluginScrapeMemory.note(context, it.source.record.manifest.id, entry.id, missed = it.noMatch, now) }
        answers.forEach { linkFromPlugin(context, entry, it.links) }
        writePluginFields(context, entry, answers.mapNotNull { it.fields }.filterNot { it.isEmpty }, replace)
        coverFromPlugins(context, entry, answers)
        val matched = answers.any { it.fields != null || it.links.isNotEmpty() }
        return PluginFill(tie = answers.any { it.ambiguous.isNotEmpty() } && !matched, matched = matched)
    }

    /** What the metadata plugins did for one game: [matched] when one found it, [tie] when only ties came back. */
    private data class PluginFill(val tie: Boolean, val matched: Boolean)

    /** One source "Rescrape with…" offers: the [scrape] `using` value, its name, and why it cannot run now (null when it can). */
    data class SourceChoice(val key: String, val label: String, val problem: String?)

    /** Every source one game can be scraped with (Droidtop/tracker#67): the built-in ones, then the running metadata plugins. */
    fun sourceChoices(context: Context): List<SourceChoice> =
        PcScraperSource.entries.map { SourceChoice(it.key, it.label, ScraperReadiness.pcSourceProblem(context, it.key)) } +
            dev.droidtop.library.integrations.PluginMetadataSources.sources(context).map {
                SourceChoice(PcScraperSourcePrefs.PLUGIN_PREFIX + it.record.manifest.id, "${it.label} (plugin)", null)
            }

    /** The metadata plugin chosen as the PC source, when it is installed and running (Droidtop/tracker#67). */
    internal fun chosenPlugin(context: Context, key: String = PcScraperSourcePrefs.key(context)): dev.droidtop.library.integrations.PluginMetadataSources.Source? {
        val id = PcScraperSourcePrefs.pluginOf(key) ?: return null
        return dev.droidtop.library.integrations.PluginMetadataSources.sources(context).firstOrNull { it.record.manifest.id == id }
    }

    /** Names a chosen plugin in the pass's lines; it is never searched by name here (the plugin answers in [fillFromPlugins]). */
    private class PluginChosenSource(override val label: String) : PcMetadataSource {
        override fun search(title: String): ScrapeLookup<List<PcMatch>> = ScrapeLookup.NoMatch
    }

    /** Links [entry] under each key a source matched it to, unless the game is already linked under that key. */
    private suspend fun linkFromPlugin(context: Context, entry: LibraryEntry, links: Map<String, String>, replace: Boolean = false) {
        if (links.isEmpty()) return
        val store = dev.droidtop.library.RoomGameLinksStore(context)
        val have = store.getAll(listOf(entry.id))[entry.id]?.sources.orEmpty().associate { it.source to it.externalId }
        for ((key, id) in links) {
            if (!replace && have[key] != null) continue
            store.setSourceLink(listOf(entry.id), key, id)
            dev.droidtop.library.ScanLog.write("scrape: ${entry.title} [pc] linked to $key $id")
        }
    }

    /**
     * A cover a metadata plugin named, for a game that has none (docs/SPEC.md 7h, "A source's own match"): most games
     * a forum source knows are on no store, so the source's picture is the only one. droidtop fetches it itself
     * ([PluginImages]: https, from the plugin's own sites, a picture of at most 10 MiB), files it where a scraped cover
     * goes, and records the plugin as the cover's source. A cover already there, or one the person chose, is kept.
     */
    private suspend fun coverFromPlugins(context: Context, entry: LibraryEntry, answers: List<dev.droidtop.library.integrations.PluginMetadataSources.Answer>) {
        if (!ScrapeOptionsPrefs.scrapeArtwork(context)) return
        val offered = answers.filter { it.cover != null }
        if (offered.isEmpty()) return
        val dao = RomDatabase.get(context).romDao()
        val row = dao.getGameMetadataSingle(entry.id)
        if (row?.artworkPath?.let { File(it).isFile } == true || !FieldSources.writable(row?.fieldSources, FieldSources.COVER)) return
        val systemFolder = PcMediaLayout.systemFolderFor(entry) ?: return
        val gamesRoot = mediaRootFor(context, entry) ?: return
        val destination = PcMediaLayout.mediaFile(gamesRoot, systemFolder, "covers", PcMediaLayout.fileSafe(baseNameFor(entry)))
        val won = offered.firstOrNull { answer ->
            runCatching { PluginImages.download(context, answer.cover!!, answer.source.netDomains, destination) }
                .onFailure { dev.droidtop.library.ScanLog.write("scrape: ${entry.title} [pc] cover from ${answer.source.label} not kept: ${it.message}") }
                .isSuccess
        } ?: return
        val base = row ?: GameMetadataEntity(id = entry.id)
        dao.upsertGameMetadata(
            base.copy(artworkPath = destination.absolutePath, fieldSources = FieldSources.merge(base.fieldSources, mapOf(FieldSources.COVER to won.source.label))),
        )
        dev.droidtop.library.ScanLog.write("scrape: ${entry.title} [pc] cover from ${won.source.label}")
    }

    private suspend fun writePluginFields(context: Context, entry: LibraryEntry, found: List<MetadataFields>, replace: Boolean = false) {
        if (found.isEmpty()) return
        val dao = RomDatabase.get(context).romDao()
        val row = dao.getGameMetadataSingle(entry.id) ?: GameMetadataEntity(id = entry.id)
        val (filled, sources) = PcPluginFields.fill(row, found, replace)
        if (sources.isNotEmpty()) dao.upsertGameMetadata(filled.copy(fieldSources = FieldSources.merge(row.fieldSources, sources)))
    }

    /** A metadata plugin's candidate for the picker: which source offered it, and its ids to fetch it by. */
    data class PluginPick(
        val source: dev.droidtop.library.integrations.PluginMetadataSources.Source,
        val candidate: PluginMetadataProtocol.Candidate,
    ) {
        /**
         * The candidate's title, or, when the source gave none, its record said plainly as one with no name
         * ("f95zone thread 214581 (name not given)", Droidtop/tracker#784): never the id as if it were the name.
         */
        val name: String get() = candidate.title ?: "${source.label} ${entryIdText()} (name not given)"
        private fun entryIdText(): String = candidate.ids.keys().asSequence().joinToString(", ") { "$it ${candidate.ids.opt(it)}" }
    }

    /** The candidates metadata plugins offer for [entry], for the picker beside the PC source's. Never applies anything. */
    suspend fun pluginCandidates(context: Context, entry: LibraryEntry): List<PluginPick> = withContext(Dispatchers.IO) {
        val session = dev.droidtop.library.integrations.PluginMetadataSources.open(context) ?: return@withContext emptyList()
        try {
            session.candidates(pluginFacts(context, entry)).map { (source, candidate) -> PluginPick(source, candidate) }
        } finally {
            session.close()
        }
    }

    /**
     * Writes a plugin candidate the person picked: the game is linked to that record (replacing a link under the same
     * key) and its fields fill what is still empty, recorded under the plugin's name. The game leaves "Choose matches".
     */
    suspend fun applyPlugin(context: Context, entry: LibraryEntry, pick: PluginPick): String = withContext(Dispatchers.IO) {
        val session = dev.droidtop.library.integrations.PluginMetadataSources.open(context)
            ?: return@withContext "${pick.source.label} is not running"
        try {
            val answer = session.fetch(pick.source, pick.candidate.ids) ?: return@withContext "${pick.source.label} did not answer"
            linkFromPlugin(context, entry, answer.links, replace = true)
            writePluginFields(context, entry, listOfNotNull(answer.fields?.takeUnless { it.isEmpty }))
            coverFromPlugins(context, entry, listOf(answer))
            markNeedsPick(context, entry, needs = false)
            dev.droidtop.library.identity.MatchQueue.clear(context, entry.id)
            "Matched ${entry.title} to ${pick.name} (${pick.source.label})."
        } finally {
            session.close()
        }
    }

    /** What happened to one game in the automatic pass; each lands in its own bucket of the summary. */
    private sealed interface PcOutcome {
        data object ByStoreId : PcOutcome
        data object ByName : PcOutcome
        /** [matches] are what the source answered, kept for Review Matches (Droidtop/tracker#318 §5). */
        data class NeedsPicking(val searched: String, val candidates: Int, val matches: List<PcMatch> = emptyList()) : PcOutcome
        data class NoMatch(val searched: String) : PcOutcome
        /** [asked] false: the source had already refused [REFUSAL_ABORT_THRESHOLD] in a row and this game was not sent to it. */
        data class Refused(val refusal: ScrapeLookup.Refused, val asked: Boolean = true) : PcOutcome
    }

    /**
     * One game. A game a store knows is identified by that store's id
     * first: a Steam app by Steam's own store record, a GOG game by IGDB's
     * record of that GOG id when IGDB is set up. That answer is certain by
     * construction, the way a hash match is for a ROM, so nothing about it
     * is a guess or a fallback chain. Only when no store record exists (a
     * real miss) does the game go to the selected title source like any
     * other; a Steam store REFUSAL is reported as one and does not quietly
     * turn into a name search.
     *
     * Whatever identified the game, the rest of its flavour and art is then
     * asked for by that identity ([PcFlavour.complete]).
     */
    private suspend fun scrapeOne(
        context: Context,
        entry: LibraryEntry,
        source: PcMetadataSource,
        flavour: PcFlavour,
        steamStopped: ScrapeLookup.Refused? = null,
        refresh: Boolean = false,
        // False when a metadata plugin is the chosen source: only the game's own store id is asked here.
        searchByName: Boolean = true,
    ): PcOutcome {
        val own = PcGameIds.of(entry)
        own.steamAppId?.let { appId ->
            steamStopped?.let { return PcOutcome.Refused(it, asked = false) }
            when (val lookup = SteamStoreClient.appDetails(appId)) {
                is ScrapeLookup.Found -> {
                    recordAnswers(context, entry, "Steam", "id", "steam:$appId", "taken", listOf(lookup.value))
                    write(context, entry, flavour.complete(entry, lookup.value), confidence = "id", replaceExisting = refresh)
                    return PcOutcome.ByStoreId
                }
                is ScrapeLookup.Refused -> return PcOutcome.Refused(lookup)
                ScrapeLookup.NoMatch -> Unit
            }
        }
        own.gogId?.let { gogId ->
            flavour.igdbByStore(PcStoreId(PcStoreId.GOG, gogId))?.let { record ->
                write(context, entry, flavour.complete(entry, record), confidence = "id", replaceExisting = refresh)
                return PcOutcome.ByStoreId
            }
        }
        val title = searchTitleFor(entry)
        if (!searchByName) return PcOutcome.NoMatch(title)
        // A name the person matched by hand before is that match, with no search (Droidtop/tracker#318 §6.4, tier A-).
        dev.droidtop.library.identity.MatchAliases.find(context, title)?.let { taught ->
            write(context, entry, flavour.complete(entry, taught), confidence = "alias", replaceExisting = refresh)
            return PcOutcome.ByName
        }
        val lookup = source.search(title)
        val decision = when (lookup) {
            is ScrapeLookup.Refused -> return PcOutcome.Refused(lookup)
            ScrapeLookup.NoMatch -> PcMatching.Decision.None
            is ScrapeLookup.Found -> PcMatching.decide(title, lookup.value)
        }
        val today = when (decision) {
            is PcMatching.Decision.Confident -> "taken"
            is PcMatching.Decision.Ambiguous -> "asked"
            PcMatching.Decision.None -> "none"
        }
        recordAnswers(context, entry, source.label, "name", title, today, if (lookup is ScrapeLookup.Found) lookup.value else emptyList())
        if (decision is PcMatching.Decision.Confident) {
            write(context, entry, flavour.complete(entry, decision.match), confidence = "name", replaceExisting = refresh)
            return PcOutcome.ByName
        }
        // Not certain from the selected source: the keyless Steam store's own search, held to the same exact, unique
        // bar (docs/SPEC.md 7h, "A game with only a name"), so a folder game that is on Steam gets the store's text.
        flavour.steamByName(title)?.let { record ->
            write(context, entry, flavour.complete(entry, record), confidence = "name", replaceExisting = refresh)
            return PcOutcome.ByName
        }
        return when (decision) {
            is PcMatching.Decision.Ambiguous -> PcOutcome.NeedsPicking(title, decision.matches.size, decision.matches)
            else -> PcOutcome.NoMatch(title)
        }
    }

    /**
     * Every image the scrape filed for [entry], labelled, for the detail
     * page's media viewer: read from the same layout folder and under the
     * same name [write] uses, so what was scraped is what can be viewed.
     * [alsoUnder] is another name to look under too (the game's folder,
     * where media placed by hand or by ES-DE is filed). Disk work: call it
     * off the main thread.
     */
    fun scrapedMedia(context: Context, entry: LibraryEntry, alsoUnder: String? = null): List<Pair<String, String>> {
        val systemFolder = PcMediaLayout.systemFolderFor(entry) ?: return emptyList()
        val root = mediaRootFor(context, entry) ?: return emptyList()
        val names = listOfNotNull(PcMediaLayout.fileSafe(baseNameFor(entry)), alsoUnder).distinct()
        val found = names.flatMap { dev.droidtop.library.EsDeArtwork.allMedia(root, systemFolder, it) }
        val icon = entry.iconUri?.let { listOf("Icon" to it) }.orEmpty()
        return (found + icon).distinctBy { it.second }
    }

    /** The Steam app id behind an entry: its own id, or the store id a store-installed engine game carries. */
    internal fun steamAppIdOf(entry: LibraryEntry): Int? = PcGameIds.of(entry).steamAppId

    /**
     * The one write path, shared by the automatic and manual routes.
     *
     * Media lands in ES-DE's own `downloaded_media` layout so the next scan
     * picks it up the same way a ROM's does, AND its path goes into the
     * metadata row: the PC providers key entries by store id or shortcut
     * path rather than by a file under a games root, so the row is the only
     * thing that can carry artwork back to them.
     *
     * [replaceExisting] is a pick's or a refresh the person asked for: what is
     * already there may be of the game the person just rejected. It replaces
     * only when [confidence] ranks at least as high as the row's match
     * ([FieldSources.replaces]); any other write keeps what is there and
     * fills what is missing (Droidtop/tracker#318 §7.2).
     *
     * Every field written records its source (docs/SPEC.md 7h); a field the
     * person edited is never written over ([FieldSources.keep]).
     */
    private suspend fun write(
        context: Context,
        entry: LibraryEntry,
        record: PcRecord,
        confidence: String,
        replaceExisting: Boolean,
    ) {
        val dao = RomDatabase.get(context).romDao()
        val row = dao.getGameMetadataSingle(entry.id)
        val had = row?.fieldSources
        val sources = mutableMapOf<String, String>()
        // A field or picture already there is replaced only by a refresh at least as strong as the row's match
        // (Droidtop/tracker#318 §7.1, §7.2); otherwise only what is missing is filled, so a name match never overwrites
        // what a store id or the person's pick put there.
        val replaces = FieldSources.replaces(confidence, row?.scrapeConfidence, replaceExisting)
        val systemFolder = PcMediaLayout.systemFolderFor(entry)
        val gamesRoot = mediaRootFor(context, entry)
        // A title is not a file name: "Half-Life: Alyx" cannot be written
        // to an exFAT SD card, which is where a handheld's games root
        // usually is. A folder name already is one.
        val baseName = PcMediaLayout.fileSafe(baseNameFor(entry))

        /** Downloads the first of [candidates] that arrives into [folder]; its path, or null. */
        fun fetch(field: String, enabled: Boolean, folder: String, candidates: List<Sourced<String>>): String? {
            if (!enabled || candidates.isEmpty() || systemFolder == null || gamesRoot == null) return null
            val destination = PcMediaLayout.mediaFile(gamesRoot, systemFolder, folder, baseName)
            if (!replaces && destination.isFile) return destination.absolutePath
            val won = candidates.firstOrNull { candidate ->
                runCatching { downloadImage(candidate.value, destination) }
                    .onFailure { android.util.Log.w("droidtop.Scraper", "${FieldSources.LABELS[field]} for ${entry.title} failed: ${it.message}") }
                    .isSuccess
            } ?: return null
            sources[field] = won.source
            return destination.absolutePath
        }

        val coverPath = fetch(FieldSources.COVER, ScrapeOptionsPrefs.scrapeArtwork(context), "covers", record.covers)
        val heroPath = fetch(FieldSources.HERO, ScrapeOptionsPrefs.scrapeFanArt(context), "fanart", listOfNotNull(record.hero))
        val logoPath = fetch(FieldSources.LOGO, ScrapeOptionsPrefs.scrapeMarquees(context), "marquees", listOfNotNull(record.logo))
        val iconPath = fetch(FieldSources.ICON, ScrapeOptionsPrefs.scrapeArtwork(context), "icons", listOfNotNull(record.icon))

        val wantMetadata = ScrapeOptionsPrefs.scrapeMetadata(context)
        fun <T> text(field: String, scraped: Sourced<T>?, current: T?): T? {
            if (!wantMetadata || scraped == null || !FieldSources.writable(had, field)) return current
            if (current != null && !replaces) return current
            sources[field] = scraped.source
            return scraped.value
        }
        val base = row ?: GameMetadataEntity(id = entry.id)
        val updated = base.copy(
            scrapeConfidence = FieldSources.keptConfidence(confidence, row?.scrapeConfidence, replaces),
            description = text(FieldSources.DESCRIPTION, record.description, row?.description),
            developer = text(FieldSources.DEVELOPER, record.developer, row?.developer),
            publisher = text(FieldSources.PUBLISHER, record.publisher, row?.publisher),
            genre = text(FieldSources.GENRE, record.genre, row?.genre),
            releaseDate = text(FieldSources.RELEASE_DATE, record.releaseDate, row?.releaseDate),
            rating = text(FieldSources.RATING, record.rating, row?.rating),
            series = text(FieldSources.SERIES, record.series, row?.series),
            links = text(FieldSources.LINKS, record.links?.let { Sourced(dev.droidtop.library.GameLink.encode(it.value), it.source) }, row?.links),
            artworkPath = coverPath ?: row?.artworkPath,
            heroPath = heroPath ?: row?.heroPath,
            logoPath = logoPath ?: row?.logoPath,
            iconPath = iconPath ?: row?.iconPath,
        )
        dao.upsertGameMetadata(updated.copy(fieldSources = FieldSources.merge(had, sources)))
        // The ids the scrape learned for this game, kept for the save-rule match (docs/SPEC.md 7o "Saves").
        runCatching { dev.droidtop.library.saves.RoomSaveRecordStore(context).putIds(entry.id, record.ids.asSaveIds()) }
        // Which source answered and what it wrote, for someone asking why a game shows what it shows.
        dev.droidtop.library.ScanLog.write(
            "scrape: ${entry.title} [pc] ($confidence): " +
                "wrote ${sources.entries.joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "nothing" }}",
        )
        reported(
            entry,
            if (sources.isEmpty()) Outcome.SKIPPED else Outcome.DONE,
            wroteLine(sources),
        )
    }

    /**
     * What a scrape wrote for a game, for a person: "Cover and description from Steam store; genre from IGDB", or
     * "Nothing new" when every field was already there. [sources] is field to source, as the per-field record keeps it.
     * Pure.
     */
    internal fun wroteLine(sources: Map<String, String>): String {
        if (sources.isEmpty()) return "Nothing new"
        val words = mapOf(FieldSources.RELEASE_DATE to "release date")
        return sources.entries.groupBy({ it.value }, { words[it.key] ?: it.key })
            .entries.joinToString("; ") { (source, fields) ->
                val list = if (fields.size == 1) fields.single() else fields.dropLast(1).joinToString(", ") + " and " + fields.last()
                "$list from $source"
            }
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * The name a game's media is filed under: the folder name for a game
     * that lives in a folder (which is what an engine game's entry id
     * is, and what its artwork lookup already keys on at scan time), the
     * title otherwise -- a store row's id is a store id, not a path.
     */
    /**
     * What a scrape searches for: the person's own title when they set one
     * (docs/SPEC.md 7n), else the parsed title of the game's folder (so a
     * part folder such as `book3` searches for the game it is a part of),
     * else the title the entry carries. Disk (one `isDirectory`): IO only.
     */
    /**
     * What [source] answered for [entry], for the calibration set when recording is on (Droidtop/tracker#318). A game the
     * person named is left out: its search is their name, not one droidtop read from the disk.
     */
    private fun recordAnswers(context: Context, entry: LibraryEntry, source: String, by: String, searched: String, today: String, matches: List<PcMatch>) {
        if (!entry.gameName.isNullOrBlank()) return
        dev.droidtop.library.identity.MatchRecorder.pc(context, entry, source, by, searched, today, matches)
    }

    internal fun searchTitleFor(entry: LibraryEntry): String {
        entry.gameName?.takeIf { it.isNotBlank() }?.let { return it }
        return if (File(entry.id).isDirectory) {
            GameTitleParser.parse(entry.id).title
        } else {
            PcScrapeTitle.clean(baseNameFor(entry))
        }
    }

    /**
     * Whether [entry] has a real cover (docs/SPEC.md 7h, "What counts as art"): one a scrape filed ([row]'s
     * artwork path, on disk), a `covers` image for it in downloaded_media, or a `cover` or `coverv` image in the
     * game's own folder (what the folder scanner takes as the person's cover). Disk reads: off the main thread.
     */
    internal fun hasCover(context: Context, entry: LibraryEntry, row: GameMetadataEntity?): Boolean {
        if (row?.artworkPath?.let { File(it).isFile } == true) return true
        val system = PcMediaLayout.systemFolderFor(entry)
        val root = mediaRootFor(context, entry)
        if (system != null && root != null &&
            dev.droidtop.library.EsDeArtwork.resolve(root, system, baseNameFor(entry), listOf("cover"))
                ?.takeIf { it.contains("/covers/") } != null
        ) {
            return true
        }
        val folder = File(entry.id).takeIf { it.isDirectory } ?: entry.groupingPath()?.let(::File)?.takeIf { it.isDirectory }
        return folder != null && FOLDER_COVER_NAMES.any { name -> COVER_EXTENSIONS.any { File(folder, "$name.$it").isFile } }
    }

    private val FOLDER_COVER_NAMES = listOf("cover", "coverv")
    private val COVER_EXTENSIONS = listOf("png", "jpg", "jpeg", "webp")

    internal fun baseNameFor(entry: LibraryEntry): String {
        val path = File(entry.id)
        return when {
            path.isDirectory -> path.name
            path.isFile -> path.nameWithoutExtension
            else -> entry.title
        }
    }

    /**
     * Which games root this entry's media belongs under: the one that
     * actually contains the game, or the first configured root for an
     * entry that lives outside them all (a Wine shortcut in app storage,
     * a Steam install). Null with no games roots configured at all, in
     * which case there is nowhere to put media and only the metadata row
     * is written.
     */
    private fun mediaRootFor(context: Context, entry: LibraryEntry): File? {
        val roots = GamesRoots.current(context)
        return GamesRoots.rootOf(roots, File(entry.id)) ?: roots.firstOrNull()
    }
}

/** Maps the value of a [ScrapeLookup.Found]; the other two outcomes pass through unchanged. */
internal inline fun <T, R> ScrapeLookup<T>.mapFound(transform: (T) -> R): ScrapeLookup<R> = when (this) {
    is ScrapeLookup.Found -> ScrapeLookup.Found(transform(value))
    ScrapeLookup.NoMatch -> ScrapeLookup.NoMatch
    is ScrapeLookup.Refused -> this
}

/** The buckets a PC/engine pass reports, each one only what it says (docs/SPEC.md section 7h). */
internal class PcScrapeCounts(val targeted: Int) {
    var attempted = 0
    /** Matched by a store's own record of the app id: certain by construction. */
    var byStoreId = 0
    /** Matched by an exact, unique title match in the selected source. */
    var byName = 0
    /** Candidates exist and none is certain: waiting on the user's pick. */
    var needsPicking = 0
    /** The source answered and has nothing by this name. The only bucket that is a statement about the game. */
    var noMatch = 0
    var failed = 0
    var refused = 0
    var lastRefusal: ScrapeLookup.Refused? = null
    /** The games behind [noMatch], [needsPicking] and [failed], by name. */
    val misses = ScrapeMisses()
    /** What the flavour lookups for matched games ran into ([PcFlavour.notes]); empty when nothing. */
    var flavourNotes: String = ""
}

/**
 * The sentence the user reads after a PC/engine pass. Pure, so the counting
 * is tested rather than only observable on hardware. The same rule as
 * [formatScrapeSummary]: a game is "no result" only when a source said so;
 * refusals, failures, and games never asked about because the pass gave up
 * are each reported as themselves.
 */
internal fun formatPcScrapeSummary(sourceLabel: String, counts: PcScrapeCounts): String {
    val matched = counts.byStoreId + counts.byName
    totalRefusalSummary("PC and engine games", counts.attempted, matched, counts.refused, counts.lastRefusal)
        ?.let { return it }
    return buildString {
        append("$sourceLabel: matched $matched of ${counts.targeted}")
        if (counts.byStoreId > 0) append(" (${counts.byStoreId} by store id)")
        if (counts.needsPicking > 0) append(", ${counts.needsPicking} need a match you pick (PC Games options > ${PcScraper.REVIEW_MATCHES}, or Choose match on the game)")
        if (counts.noMatch > 0) append(", ${counts.noMatch} had no result at all")
        if (counts.failed > 0) append(", ${counts.failed} failed")
        if (counts.refused > 0) append(", ${counts.refused} refused by the server")
        if (counts.attempted < counts.targeted) append(", ${counts.targeted - counts.attempted} not asked for after the pass gave up")
        append('.')
        if (counts.refused > 0) append(describeRefusal(counts.refused, counts.attempted, counts.lastRefusal))
        if (counts.flavourNotes.isNotEmpty()) append(' ').append(counts.flavourNotes)
        append(counts.misses.sentences())
    }
}
