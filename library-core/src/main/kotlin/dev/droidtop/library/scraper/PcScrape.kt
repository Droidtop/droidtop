package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.esDeSystemName
import dev.droidtop.library.toLibraryEntryKind
import java.io.File
import kotlinx.coroutines.Dispatchers
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
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_SOURCE = "droidtop_pc_scraper_source"

    fun get(context: Context): PcScraperSource {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_SOURCE, null)
        // Lutris is the default because it is the one that works with
        // nothing configured; a default that needs credentials would
        // make a fresh install's first scrape fail for a reason the user
        // did not choose.
        return PcScraperSource.entries.firstOrNull { it.key == raw } ?: PcScraperSource.LUTRIS
    }

    fun set(context: Context, source: PcScraperSource) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, source.key).apply()
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
 * Turns a game FOLDER name into something a title search can use.
 *
 * Real folder names in a real library are not titles: "Eternum-0.9.5-pc",
 * "BeingADIK-0.8.3-scrappy", "Game_v1.2_win64", "Some Game [1.0]". Every
 * rule here strips a tag that is provably not part of a title -- a
 * version number, a platform/build tag, a bracketed suffix, a separator
 * used as a space. Nothing here guesses at the title itself: whatever
 * survives the strip is passed through unchanged.
 */
object PcScrapeTitle {

    // Platform/build tags real distributions append. Matched as whole
    // separator-delimited tokens, so a title containing "win" or a game
    // actually called "Mac" is untouched.
    private val PLATFORM_TAGS = setOf(
        "pc", "win", "win32", "win64", "windows", "linux", "lin", "lin64", "mac", "osx",
        "x86", "x64", "32bit", "64bit", "android",
    )

    /**
     * A version token: either something with an internal separator
     * ("0.9.5", "1_2") or a v/r-prefixed number ("v1.2", "r12").
     *
     * A bare number deliberately does NOT match. Sequels are numbered --
     * "Half-Life 2", "Persona 5" -- and a rule that ate a trailing digit
     * would quietly search for the wrong game every time.
     */
    private val VERSION_TOKEN = Regex("""^([vr]\d+([.\-_]\d+)*|\d+([.\-_]\d+)+)[a-z]?$""", RegexOption.IGNORE_CASE)

    // Bracketed or parenthesised tags anywhere in the name: "[1.0]", "(Final)".
    private val BRACKETED = Regex("""[\[({][^\[\]{}()]*[\])}]""")

    fun clean(folderName: String): String {
        val tokens = BRACKETED.replace(folderName, " ").split('-', '_', ' ').filter { it.isNotBlank() }
        // The FIRST token is never dropped: "V2 Berlin" is a title, and
        // so is "2064" -- a version-shaped word can only be a version tag
        // when something came before it.
        val withoutVersions = tokens.filterIndexed { index, token -> index == 0 || !VERSION_TOKEN.matches(token) }
        val kept = withoutVersions.toMutableList()
        // Platform tags only at the end, where releases actually put them.
        while (kept.size > 1 && kept.last().lowercase() in PLATFORM_TAGS) kept.removeAt(kept.size - 1)
        return kept.joinToString(" ").trim().ifBlank { folderName }
    }
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
        File(File(File(gamesRoot, "downloaded_media"), systemFolder), "$folder/$baseName.png")

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

    /** The live source for the current selection, or null when it is not usable (see [ScraperReadiness.pcSourceProblem]). */
    fun source(context: Context): PcMetadataSource? = when (PcScraperSourcePrefs.get(context)) {
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
        val title = PcScrapeTitle.clean(baseNameFor(entry))
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
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext "No PC or engine games to scrape."
        ScraperReadiness.pcSourceProblem(context)?.let { return@withContext it }
        val source = source(context) ?: return@withContext "No PC scraper source is configured."
        val wantMetadata = ScrapeOptionsPrefs.scrapeMetadata(context)
        val wantArtwork = ScrapeOptionsPrefs.scrapeArtwork(context)
        if (!wantMetadata && !wantArtwork) {
            return@withContext "Both content types are disabled in scrape options."
        }

        val dao = RomDatabase.get(context).romDao()
        val existing = dao.getGameMetadata(entries.map { it.id }).associateBy { it.id }
        val filter = ScrapeOptionsPrefs.filter(context)
        val targets = entries.filter { entry ->
            val row = existing[entry.id]
            val noMeta = row?.description == null && row?.genre == null && row?.developer == null
            val scrapedCover = row?.artworkPath?.let { File(it).isFile } == true
            // What a PC provider brings on its own is a store's icon or
            // the icon inside an .exe, not a cover: only a scraped cover
            // counts as art for one (see withScrapedMetadata's
            // scrapedArtworkFirst). An engine game's art is what its
            // folder or downloaded_media holds, which does count.
            val noArt = !scrapedCover && (entry.kind == LibraryEntryKind.WINE_PROFILE || entry.artworkUri == null)
            when (filter) {
                ScrapeFilter.MISSING_ANY -> noArt || noMeta
                ScrapeFilter.MISSING_ARTWORK -> noArt
                ScrapeFilter.MISSING_METADATA -> noMeta
                ScrapeFilter.FAVORITES -> row?.favorite == true
                ScrapeFilter.ALL -> true
            }
        }
        if (targets.isEmpty()) return@withContext "Nothing matches the \"${filter.label}\" scrape filter."

        val counts = PcScrapeCounts(targeted = targets.size)
        val flavour = PcFlavour(context)
        var consecutiveRefusals = 0
        for ((index, entry) in targets.withIndex()) {
            // The same rule as the ROM pass: several refusals in a row are
            // about the server, not about any game, and asking again only
            // spends the user's time to be told the same thing.
            if (consecutiveRefusals >= REFUSAL_ABORT_THRESHOLD) break
            onProgress(index, targets.size)
            counts.attempted++
            try {
                val outcome = scrapeOne(context, entry, source, flavour)
                if (outcome is PcOutcome.Refused) {
                    consecutiveRefusals++
                    counts.lastRefusal = outcome.refusal
                } else {
                    consecutiveRefusals = 0
                }
                when (outcome) {
                    PcOutcome.ByStoreId -> counts.byStoreId++
                    PcOutcome.ByName -> counts.byName++
                    PcOutcome.NeedsPicking -> counts.needsPicking++
                    PcOutcome.NoMatch -> counts.noMatch++
                    is PcOutcome.Refused -> counts.refused++
                }
            } catch (t: Exception) {
                counts.failed++
                consecutiveRefusals = 0
                android.util.Log.e("droidtop.Scraper", "Failed to scrape ${entry.title}", t)
            }
        }
        counts.flavourNotes = flavour.notes()
        formatPcScrapeSummary(source.label, counts)
    }

    /** What happened to one game in the automatic pass; each lands in its own bucket of the summary. */
    private sealed interface PcOutcome {
        data object ByStoreId : PcOutcome
        data object ByName : PcOutcome
        data object NeedsPicking : PcOutcome
        data object NoMatch : PcOutcome
        data class Refused(val refusal: ScrapeLookup.Refused) : PcOutcome
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
    private suspend fun scrapeOne(context: Context, entry: LibraryEntry, source: PcMetadataSource, flavour: PcFlavour): PcOutcome {
        val own = PcGameIds.of(entry)
        own.steamAppId?.let { appId ->
            when (val lookup = SteamStoreClient.appDetails(appId)) {
                is ScrapeLookup.Found -> {
                    write(context, entry, flavour.complete(entry, lookup.value), confidence = "id", replaceExisting = false)
                    return PcOutcome.ByStoreId
                }
                is ScrapeLookup.Refused -> return PcOutcome.Refused(lookup)
                ScrapeLookup.NoMatch -> Unit
            }
        }
        own.gogId?.let { gogId ->
            flavour.igdbByStore(PcStoreId(PcStoreId.GOG, gogId))?.let { record ->
                write(context, entry, flavour.complete(entry, record), confidence = "id", replaceExisting = false)
                return PcOutcome.ByStoreId
            }
        }
        val title = PcScrapeTitle.clean(baseNameFor(entry))
        return when (val lookup = source.search(title)) {
            is ScrapeLookup.Refused -> PcOutcome.Refused(lookup)
            ScrapeLookup.NoMatch -> PcOutcome.NoMatch
            is ScrapeLookup.Found -> when (val decision = PcMatching.decide(title, lookup.value)) {
                is PcMatching.Decision.Confident -> {
                    write(context, entry, flavour.complete(entry, decision.match), confidence = "name", replaceExisting = false)
                    PcOutcome.ByName
                }
                is PcMatching.Decision.Ambiguous -> PcOutcome.NeedsPicking
                PcMatching.Decision.None -> PcOutcome.NoMatch
            }
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
     * [replaceExisting] is the manual route's: the files already there are
     * pictures of the game the person just rejected. The automatic pass
     * keeps a file that is already there.
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
            if (!replaceExisting && destination.isFile) return destination.absolutePath
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
            sources[field] = scraped.source
            return scraped.value
        }
        val base = row ?: GameMetadataEntity(id = entry.id)
        val updated = base.copy(
            scrapeConfidence = confidence,
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
    }

    /**
     * The name a game's media is filed under: the folder name for a game
     * that lives in a folder (which is what an engine game's entry id
     * is, and what its artwork lookup already keys on at scan time), the
     * title otherwise -- a store row's id is a store id, not a path.
     */
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
        val path = File(entry.id)
        return roots.firstOrNull { path.absolutePath.startsWith(it.absolutePath) } ?: roots.firstOrNull()
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
        if (counts.needsPicking > 0) append(", ${counts.needsPicking} need a match you pick (Choose match on the game)")
        if (counts.noMatch > 0) append(", ${counts.noMatch} had no result at all")
        if (counts.failed > 0) append(", ${counts.failed} failed")
        if (counts.refused > 0) append(", ${counts.refused} refused by the server")
        if (counts.attempted < counts.targeted) append(", ${counts.targeted - counts.attempted} not asked for after the pass gave up")
        append('.')
        if (counts.refused > 0) append(describeRefusal(counts.refused, counts.attempted, counts.lastRefusal))
        if (counts.flavourNotes.isNotEmpty()) append(' ').append(counts.flavourNotes)
    }
}
