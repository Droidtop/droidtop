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
 * The sources droidtop can reach for a PC or engine title.
 *
 * Deliberately a different list from [ScraperSource]: the ROM scrapers
 * index console dumps by platform id and file hash, and none of them
 * covers "some Ren'Py build in a folder". The selection model is the
 * same as ES-DE's, and as the ROM side's -- exactly ONE source is
 * queried for a given scrape, never a silent fallback chain.
 */
enum class PcScraperSource(val label: String) {
    /** Keyless, so it works on a fresh install with nothing configured. Covers art and release year only. */
    LUTRIS("Lutris (no account needed)"),

    /** Needs the user's own free Twitch/IGDB credentials, and in exchange returns descriptions, developer, publisher and genre. */
    IGDB("IGDB (needs your own free API credentials)"),
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
        return if (raw == "igdb") PcScraperSource.IGDB else PcScraperSource.LUTRIS
    }

    fun set(context: Context, source: PcScraperSource) {
        val raw = if (source == PcScraperSource.IGDB) "igdb" else "lutris"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_SOURCE, raw).apply()
    }
}

/**
 * One candidate a source returned, in the shape the write path needs.
 * Sources fill what they actually have and leave the rest null -- Lutris
 * genuinely has no description or developer field at all, and inventing
 * a plausible one would be exactly the fabrication this project refuses.
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
        File(File(File(gamesRoot, "downloaded_media"), systemFolder), "covers/$baseName.png")

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
        PcScraperSource.IGDB -> {
            val clientId = ScraperPrefs.clientId(context)
            val clientSecret = ScraperPrefs.clientSecret(context)
            if (clientId.isBlank() || clientSecret.isBlank()) null else IgdbSource(clientId, clientSecret)
        }
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
                        // Lutris's API carries no description, developer,
                        // publisher, genre or rating at all, and its `year`
                        // is a year with no month or day. ES-DE's own MD_DATE
                        // is a full "YYYYMMDDT000000" string, so writing one
                        // would mean inventing a January 1st that Lutris
                        // never said: the year is shown in the picker, where
                        // it helps a person choose, and nothing is written.
                    )
                }
            }
    }

    private class IgdbSource(private val clientId: String, private val clientSecret: String) : PcMetadataSource {
        override val label = "IGDB"
        override fun search(title: String): ScrapeLookup<List<PcMatch>> =
            IgdbScraperClient.search(clientId, clientSecret, title).mapFound { results ->
                results.map { result ->
                    PcMatch(
                        name = result.name,
                        sourceLabel = label,
                        year = result.releaseDate?.take(4)?.toIntOrNull(),
                        coverUrl = result.coverUrl,
                        description = result.description,
                        developer = result.developer,
                        publisher = result.publisher,
                        genre = result.genre,
                        releaseDate = result.releaseDate,
                        rating = result.rating,
                    )
                }
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
                    (lookup.reason?.let { ": $it" } ?: "."),
            )
        }
    }

    /**
     * Writes one match the USER picked. Replaces the cover (the previous
     * one is the picture of the game they just rejected) and overwrites
     * the scraper-owned fields only -- favourites, collections, and
     * anything edited in the metadata editor survive untouched, exactly
     * as [applyManualMatch] does for ROMs.
     */
    suspend fun apply(context: Context, entry: LibraryEntry, match: PcMatch): String = withContext(Dispatchers.IO) {
        write(context, entry, match, confidence = "manual", replaceExistingCover = true)
        "Matched ${entry.title} to ${match.name} (${match.sourceLabel})."
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
        var consecutiveRefusals = 0
        for ((index, entry) in targets.withIndex()) {
            // The same rule as the ROM pass: several refusals in a row are
            // about the server, not about any game, and asking again only
            // spends the user's time to be told the same thing.
            if (consecutiveRefusals >= REFUSAL_ABORT_THRESHOLD) break
            onProgress(index, targets.size)
            counts.attempted++
            try {
                val outcome = scrapeOne(context, entry, source)
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
     * One game. A Steam game is asked of Steam's own store by its app id
     * first: that answer is certain by construction, the way a hash match
     * is for a ROM, so nothing about it is a guess or a fallback chain.
     * Only when the store has no public record of the app (a real miss)
     * does the game go to the selected title source like any other; a
     * store REFUSAL is reported as one and does not quietly turn into a
     * name search.
     */
    private suspend fun scrapeOne(context: Context, entry: LibraryEntry, source: PcMetadataSource): PcOutcome {
        steamAppIdOf(entry)?.let { appId ->
            when (val lookup = SteamStoreClient.appDetails(appId)) {
                is ScrapeLookup.Found -> {
                    write(context, entry, lookup.value, confidence = "id", replaceExistingCover = false)
                    return PcOutcome.ByStoreId
                }
                is ScrapeLookup.Refused -> return PcOutcome.Refused(lookup)
                ScrapeLookup.NoMatch -> Unit
            }
        }
        val title = PcScrapeTitle.clean(baseNameFor(entry))
        return when (val lookup = source.search(title)) {
            is ScrapeLookup.Refused -> PcOutcome.Refused(lookup)
            ScrapeLookup.NoMatch -> PcOutcome.NoMatch
            is ScrapeLookup.Found -> when (val decision = PcMatching.decide(title, lookup.value)) {
                is PcMatching.Decision.Confident -> {
                    write(context, entry, decision.match, confidence = "name", replaceExistingCover = false)
                    PcOutcome.ByName
                }
                is PcMatching.Decision.Ambiguous -> PcOutcome.NeedsPicking
                PcMatching.Decision.None -> PcOutcome.NoMatch
            }
        }
    }

    /** The Steam app id behind an entry: its own id, or the store id a store-installed engine game carries. */
    internal fun steamAppIdOf(entry: LibraryEntry): Int? =
        SteamStoreClient.appIdOf(entry.id) ?: SteamStoreClient.appIdOf(entry.pcInfo?.storeId)

    /**
     * The one write path, shared by the automatic and manual routes.
     *
     * The cover lands in ES-DE's own `downloaded_media` layout so the
     * next scan picks it up the same way a ROM's does, AND its path goes
     * into the metadata row: the PC providers key entries by store id or
     * shortcut path rather than by a file under a games root, so the row
     * is the only thing that can carry artwork back to them.
     */
    private suspend fun write(
        context: Context,
        entry: LibraryEntry,
        match: PcMatch,
        confidence: String,
        replaceExistingCover: Boolean,
    ) {
        val dao = RomDatabase.get(context).romDao()
        val row = dao.getGameMetadataSingle(entry.id)
        var coverPath: String? = null
        val systemFolder = PcMediaLayout.systemFolderFor(entry)
        val gamesRoot = mediaRootFor(context, entry)
        if (ScrapeOptionsPrefs.scrapeArtwork(context) && match.coverUrl != null && systemFolder != null && gamesRoot != null) {
            // A title is not a file name: "Half-Life: Alyx" cannot be
            // written to an exFAT SD card, which is where a handheld's
            // games root usually is. A folder name already is one.
            val destination = PcMediaLayout.coverFile(gamesRoot, systemFolder, PcMediaLayout.fileSafe(baseNameFor(entry)))
            if (replaceExistingCover || !destination.isFile) {
                val downloaded = listOfNotNull(match.coverUrl, match.alternateCoverUrl).any { url ->
                    runCatching { downloadImage(url, destination) }
                        .onFailure { android.util.Log.w("droidtop.Scraper", "Cover for ${entry.title} failed: ${it.message}") }
                        .isSuccess
                }
                if (downloaded) coverPath = destination.absolutePath
            } else {
                coverPath = destination.absolutePath
            }
        }
        val wantMetadata = ScrapeOptionsPrefs.scrapeMetadata(context)
        dao.upsertGameMetadata(
            (row ?: GameMetadataEntity(id = entry.id)).copy(
                scrapeConfidence = confidence,
                description = (if (wantMetadata) match.description else null) ?: row?.description,
                developer = (if (wantMetadata) match.developer else null) ?: row?.developer,
                publisher = (if (wantMetadata) match.publisher else null) ?: row?.publisher,
                genre = (if (wantMetadata) match.genre else null) ?: row?.genre,
                releaseDate = (if (wantMetadata) match.releaseDate else null) ?: row?.releaseDate,
                rating = (if (wantMetadata) match.rating else null) ?: row?.rating,
                artworkPath = coverPath ?: row?.artworkPath,
            ),
        )
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
    }
}
