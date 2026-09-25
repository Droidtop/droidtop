package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.consoles.RomDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The scrape ENGINE, moved here from :app (directed: actions live on
// the MAIN screen, so the Gaming shell must be able to run scrapes
// and gamelist imports itself -- and every type this uses already lived
// in library-core). :app's settings catalogs call the same functions.

suspend fun scrapeSystemArtwork(
    context: android.content.Context,
    folder: File,
    system: ConsoleSystemDef,
    // Before onProgress on purpose: every existing call site passes the
    // progress callback as a trailing lambda, which binds to the LAST
    // parameter.
    onlyRom: File? = null,
    // Told when the source refused every request this pass made: a
    // whole-library pass stops there rather than asking the same source
    // about every other system only to be refused again.
    onRefusedEverything: () -> Unit = {},
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): String = withContext(Dispatchers.IO) {
    // A source that cannot be asked at all (a key that is not set) says
    // so, with the fix, before anything is walked or counted.
    ScraperReadiness.romSourceProblem(context)?.let { return@withContext it }
    val gamesRoot = folder.parentFile ?: folder
    // One walk, shared with the library scan -- see RomScanWalk for why
    // a DLC/update directory is not twelve games.
    val romScan = dev.droidtop.library.consoles.RomScanWalk.walk(folder, system.extensions)
    val romFiles = romScan.files
    romScan.skipped.forEach { (directory, reason) ->
        android.util.Log.i("droidtop.Scraper", "Not scraping ${directory.name}: $reason")
    }
    val dao = RomDatabase.get(context).romDao()
    val metadataRows = dao.getGameMetadata(romFiles.map { it.absolutePath })
    val existingMetadataIds = metadataRows.map { it.id }.toSet()
    val favoriteIds = metadataRows.filter { it.favorite }.map { it.id }.toSet()
    // The ES-DE-style game filter (ScrapeOptionsPrefs, set in the
    // scraper settings screen) decides WHICH games this pass touches.
    val filter = dev.droidtop.library.scraper.ScrapeOptionsPrefs.filter(context)
    val wantMetadata = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeMetadata(context)
    val wantArtwork = dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeArtwork(context)
    if (!wantMetadata && !wantArtwork) {
        return@withContext "${system.displayName}: both content types are disabled in scrape options."
    }
    val missing = romFiles.filter { romFile ->
        val noArt = EsDeArtwork.resolve(gamesRoot, system.id, romFile.nameWithoutExtension) == null
        val noMeta = romFile.absolutePath !in existingMetadataIds
        when (filter) {
            dev.droidtop.library.scraper.ScrapeFilter.MISSING_ANY -> noArt || noMeta
            dev.droidtop.library.scraper.ScrapeFilter.MISSING_ARTWORK -> noArt
            dev.droidtop.library.scraper.ScrapeFilter.MISSING_METADATA -> noMeta
            dev.droidtop.library.scraper.ScrapeFilter.FAVORITES -> romFile.absolutePath in favoriteIds
            dev.droidtop.library.scraper.ScrapeFilter.ALL -> true
        }
    }
    val targets = if (onlyRom != null) {
        romFiles.filter { it.absolutePath == onlyRom.absolutePath }
    } else {
        missing
    }
    if (targets.isEmpty()) {
        return@withContext if (onlyRom != null) {
            "${system.displayName}: ${onlyRom.name} isn't in this folder's scan."
        } else {
            "${system.displayName}: nothing matches the \"${filter.label}\" scrape filter."
        }
    }

    val source = ScraperSourcePrefs.get(context)
    val screenScraperSystemId = if (source == ScraperSource.SCREENSCRAPER) ScreenScraperSystemIds.forSystemId(system.id) else null
    val gamesDbSystemId = if (source == ScraperSource.THEGAMESDB) TheGamesDbSystemIds.forSystemId(system.id) else null
    val gamesDbApiKey = TheGamesDbPrefs.apiKey(context)
    val devId = ScreenScraperPrefs.devId(context)
    val devPassword = ScreenScraperPrefs.devPassword(context)
    val userId = ScreenScraperPrefs.userId(context)
    val userPassword = ScreenScraperPrefs.userPassword(context)
    if (source == ScraperSource.SCREENSCRAPER && screenScraperSystemId == null) {
        return@withContext "${system.displayName}: ScreenScraper has no platform id for this system."
    }
    if (source == ScraperSource.THEGAMESDB && gamesDbSystemId == null) {
        return@withContext "${system.displayName}: TheGamesDB has no platform id for this system."
    }
    // The keyless libretro-database source: one cached DAT set per
    // system, matched by the same No-Intro naming as the thumbnails.
    val libretroLookup = if (source == ScraperSource.LIBRETRO) {
        when (val loaded = LibretroMetadata.load(context, system.id)) {
            null -> return@withContext "${system.displayName}: no libretro database name is mapped for this system."
            is ScrapeLookup.Refused -> {
                onRefusedEverything()
                return@withContext totalRefusalSummary(
                    subject = system.displayName,
                    attempted = 1,
                    found = 0,
                    refused = 1,
                    lastRefusal = loaded,
                ).orEmpty()
            }
            else -> loaded.foundOrNull
        }
    } else null

    var found = 0
    var failed = 0
    var refused = 0
    // Requests the selected source refused, whatever else then found the
    // game: a refused ROM that the keyless thumbnails still gave a cover
    // is counted as found below, and the refusal must not vanish with it
    // (rig, dq-shell2-01: a wrong TheGamesDB key read "found 1").
    var sourceRefused = 0
    var consecutiveRefusals = 0
    var lastRefusal: ScrapeLookup.Refused? = null
    var attempted = 0
    var hashMatched = 0
    var thumbnailed = 0
    var miximaged = 0
    targets.forEachIndexed { index, romFile ->
        // A run that the server is refusing outright makes no progress,
        // and 46 refusals paced ~11s apart cost the user most of a night
        // to learn nothing. Stop once the refusals are plainly not about
        // any individual game, and report what the server said.
        if (consecutiveRefusals >= REFUSAL_ABORT_THRESHOLD) return@forEachIndexed
        onProgress(index, targets.size)
        attempted++
        try {
            // Real ES-DE automatic mode: hash the file (up to its own
            // 384 MiB default cap) and search WITH the digest — hash
            // identity in the response is the confidence check, not any
            // string similarity (ported from GuiScraperSearch.cpp /
            // ScreenScraper.cpp, read before porting).
            val localMd5 = if (screenScraperSystemId != null) {
                dev.droidtop.library.scraper.RomHash.md5OrNull(romFile)
            } else null
            val screenScraperLookup = screenScraperSystemId?.let {
                ScreenScraperClient.findMetadata(
                    systemeId = it.toString(),
                    romName = romFile.name,
                    romSizeBytes = romFile.length(),
                    devId = devId,
                    devPassword = devPassword,
                    userId = userId,
                    userPassword = userPassword,
                    md5 = localMd5.orEmpty(),
                )
            }
            val gamesDbLookup = gamesDbSystemId?.let {
                TheGamesDbClient.findMetadata(gamesDbApiKey, context.cacheDir, it, romFile.nameWithoutExtension)
            }
            // The whole point of ScrapeLookup: a refusal is the server's
            // problem, a NoMatch is a fact about the library, and only
            // the second one may ever be counted as "no match". Exactly
            // one of the two lookups ran (one selected source).
            val refusal = (screenScraperLookup ?: gamesDbLookup) as? ScrapeLookup.Refused
            if (refusal != null) {
                consecutiveRefusals++
                sourceRefused++
                lastRefusal = refusal
            } else {
                consecutiveRefusals = 0
            }
            val screenScraperResult = screenScraperLookup?.foundOrNull
            val confidence = when {
                screenScraperResult == null -> null
                localMd5 != null && screenScraperResult.romMd5 == localMd5 -> {
                    hashMatched++
                    "hash"
                }
                else -> "name"
            }
            val gamesDbResult = gamesDbLookup?.foundOrNull
            val libretroResult = libretroLookup?.find(romFile.nameWithoutExtension)

            // Keyless boxart fallback, consulted only when the selected
            // credentialed source produced no cover (fresh installs have
            // no ScreenScraper dev ID and no TheGamesDB key at all).
            val thumbnailUrl = if (
                wantArtwork &&
                screenScraperResult?.coverUrl == null &&
                gamesDbResult?.coverUrl == null &&
                EsDeArtwork.resolve(gamesRoot, system.id, romFile.nameWithoutExtension) == null
            ) {
                dev.droidtop.library.scraper.LibretroThumbnails.coverUrl(system.id, romFile.nameWithoutExtension)
            } else null
            if (thumbnailUrl != null) thumbnailed++
            val coverUrl = screenScraperResult?.coverUrl ?: gamesDbResult?.coverUrl ?: thumbnailUrl
            val mediaRoot = File(File(gamesRoot, "downloaded_media"), system.id)
            val baseName = romFile.nameWithoutExtension
            val coverWritten = wantArtwork && coverUrl != null && EsDeArtwork.resolve(gamesRoot, system.id, baseName) == null
            if (coverWritten) downloadImage(coverUrl!!, File(mediaRoot, "covers/$baseName.png"))
            // The full media set (real ES-DE's own Scrape* toggles, all
            // default-on): each type lands in its ES-DE downloaded_media
            // directory, skipped when the file already exists. The
            // keyless libretro repos stand in for screenshots and title
            // screens when the selected source has nothing.
            val ssMedia = screenScraperResult?.mediaUrls ?: emptyMap()
            fun fetchMedia(enabled: Boolean, dir: String, url: String?, extension: String = "png") {
                if (!enabled || url == null) return
                val destination = File(mediaRoot, "$dir/$baseName.$extension")
                if (destination.isFile) return
                try {
                    downloadImage(url, destination)
                } catch (t: Exception) {
                    android.util.Log.w("droidtop.Scraper", "Media $dir for ${romFile.name} failed: ${t.message}")
                }
            }
            val wantLibretroFallback = source == ScraperSource.LIBRETRO || ssMedia.isEmpty()
            fetchMedia(
                dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeScreenshots(context),
                "screenshots",
                ssMedia["ss"] ?: if (wantLibretroFallback) {
                    dev.droidtop.library.scraper.LibretroThumbnails.screenshotUrl(system.id, baseName)
                } else null,
            )
            fetchMedia(
                dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeTitleScreens(context),
                "titlescreens",
                ssMedia["sstitle"] ?: if (wantLibretroFallback) {
                    dev.droidtop.library.scraper.LibretroThumbnails.titleUrl(system.id, baseName)
                } else null,
            )
            fetchMedia(
                dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeMarquees(context),
                "marquees",
                ssMedia["wheel-hd"] ?: ssMedia["wheel"],
            )
            fetchMedia(
                dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapePhysicalMedia(context),
                "physicalmedia",
                ssMedia["support-2D"],
            )
            fetchMedia(
                dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeFanArt(context),
                "fanart",
                ssMedia["fanart"],
            )
            fetchMedia(
                dev.droidtop.library.scraper.ScrapeOptionsPrefs.scrapeVideos(context),
                "videos",
                ssMedia["video-normalized"] ?: ssMedia["video"],
                extension = "mp4",
            )
            if (dev.droidtop.library.scraper.ScrapeOptionsPrefs.generateMiximages(context)) {
                val miximage = File(mediaRoot, "miximages/$baseName.png")
                val screenshotFile = File(mediaRoot, "screenshots/$baseName.png")
                if (!miximage.isFile && screenshotFile.isFile) {
                    val composed = dev.droidtop.library.scraper.MiximageGenerator.generate(
                        screenshot = screenshotFile,
                        marquee = File(mediaRoot, "marquees/$baseName.png").takeIf { it.isFile },
                        box3D = File(mediaRoot, "3dboxes/$baseName.png").takeIf { it.isFile },
                        cover = File(mediaRoot, "covers/$baseName.png").takeIf { it.isFile },
                        physicalMedia = File(mediaRoot, "physicalmedia/$baseName.png").takeIf { it.isFile },
                        output = miximage,
                        rotateHorizontalBoxes = dev.droidtop.library.scraper.ScrapeOptionsPrefs.miximageRotateHorizontalBoxes(context),
                    )
                    if (composed) miximaged++
                }
            }

            // Each field records the source it came from (FieldSources).
            val sources = mutableMapOf<String, String>()
            fun <T> pick(field: String, vararg candidates: Pair<String, T?>): T? =
                candidates.firstOrNull { it.second != null }?.let { (source, value) ->
                    sources[field] = source
                    value
                }
            val ss = "ScreenScraper"
            val tgdb = "TheGamesDB"
            val libretro = "libretro database"
            val description = pick(FieldSources.DESCRIPTION, ss to screenScraperResult?.description, tgdb to gamesDbResult?.description)
            val developer = pick(
                FieldSources.DEVELOPER,
                ss to screenScraperResult?.developer, tgdb to gamesDbResult?.developer, libretro to libretroResult?.developer,
            )
            val publisher = pick(
                FieldSources.PUBLISHER,
                ss to screenScraperResult?.publisher, tgdb to gamesDbResult?.publisher, libretro to libretroResult?.publisher,
            )
            val genre = pick(FieldSources.GENRE, ss to screenScraperResult?.genre, tgdb to gamesDbResult?.genre, libretro to libretroResult?.genre)
            val releaseDate = pick(
                FieldSources.RELEASE_DATE,
                ss to screenScraperResult?.releaseDate, tgdb to gamesDbResult?.releaseDate, libretro to libretroResult?.releaseDate,
            )
            val players = pick(
                FieldSources.PLAYERS,
                ss to screenScraperResult?.players, tgdb to gamesDbResult?.players, libretro to libretroResult?.players,
            )
            val rating = pick(FieldSources.RATING, ss to screenScraperResult?.rating)
            if (coverWritten) {
                sources[FieldSources.COVER] = when (coverUrl) {
                    screenScraperResult?.coverUrl -> ss
                    gamesDbResult?.coverUrl -> tgdb
                    else -> "libretro thumbnails"
                }
            }
            val hasAnyMetadata = wantMetadata &&
                listOfNotNull(description, developer, publisher, genre, releaseDate, players, rating).isNotEmpty()
            // Text the options switched off is neither written nor recorded.
            if (!wantMetadata) sources.keys.retainAll(setOf(FieldSources.COVER))
            fun <T> text(value: T?): T? = if (wantMetadata) value else null
            if (hasAnyMetadata || coverWritten) {
                // Real fix: this used to build a fresh GameMetadataEntity
                // with a hardcoded favorite=false, silently wiping out a
                // real user's favorite toggle (and, now that
                // GameMetadataEditor exists, every other real user-edited
                // field too) on every rescrape. Read the existing row
                // first and only overwrite the real scraper-owned fields
                // -- same real "don't clobber user data with a rescan"
                // principle RomEntity/GameMetadataEntity's own doc
                // comments already establish for the filesystem-scan
                // side of this database.
                val existing = dao.getGameMetadataSingle(romFile.absolutePath)
                val had = existing?.fieldSources
                dao.upsertGameMetadata(
                    (existing ?: GameMetadataEntity(id = romFile.absolutePath)).copy(
                        scrapeConfidence = confidence ?: existing?.scrapeConfidence,
                        description = FieldSources.keep(had, FieldSources.DESCRIPTION, text(description), existing?.description),
                        developer = FieldSources.keep(had, FieldSources.DEVELOPER, text(developer), existing?.developer),
                        publisher = FieldSources.keep(had, FieldSources.PUBLISHER, text(publisher), existing?.publisher),
                        genre = FieldSources.keep(had, FieldSources.GENRE, text(genre), existing?.genre),
                        releaseDate = FieldSources.keep(had, FieldSources.RELEASE_DATE, text(releaseDate), existing?.releaseDate),
                        rating = FieldSources.keep(had, FieldSources.RATING, text(rating), existing?.rating),
                        players = FieldSources.keep(had, FieldSources.PLAYERS, text(players), existing?.players),
                        fieldSources = FieldSources.merge(had, sources),
                    ),
                )
            }
            // Each ROM lands in exactly one bucket. A refused ROM that the
            // keyless thumbnails still gave a cover is found, not refused:
            // counting it twice is what could drive "no match" below zero.
            if (coverUrl != null || hasAnyMetadata) {
                found++
            } else if (refusal != null) {
                refused++
            }
        } catch (t: Exception) {
            failed++
            android.util.Log.e("droidtop.Scraper", "Failed to scrape ${romFile.name}", t)
        }
    }
    if (attempted > 0 && sourceRefused == attempted) onRefusedEverything()
    formatScrapeSummary(
        systemName = system.displayName,
        targeted = targets.size,
        attempted = attempted,
        found = found,
        hashMatched = hashMatched,
        thumbnailed = thumbnailed,
        miximaged = miximaged,
        failed = failed,
        refused = refused,
        lastRefusal = lastRefusal,
        sourceRefused = sourceRefused,
    )
}

/**
 * The sentence the user actually reads after a scrape, kept pure and out
 * of [scrapeSystemArtwork] so the counting can be tested directly.
 *
 * The rule this enforces, and the reason this function exists at all: a
 * game is only reported as "no match" when the scraper asked and was told
 * the database does not have it. Requests the server refused, requests
 * that threw, and requests never sent because the pass gave up are each
 * reported as themselves. Before this, all four collapsed into "no
 * match", and 46 HTTP 403s were shown to the user as 46 games missing
 * from ScreenScraper -- a claim about their library that was entirely
 * false.
 */
internal fun formatScrapeSummary(
    systemName: String,
    targeted: Int,
    attempted: Int,
    found: Int,
    hashMatched: Int,
    thumbnailed: Int,
    miximaged: Int,
    failed: Int,
    refused: Int,
    lastRefusal: ScrapeLookup.Refused?,
    // Every request the source refused, including those whose game the
    // keyless thumbnails found anyway ([refused] counts only the rest).
    sourceRefused: Int = refused,
): String {
    totalRefusalSummary(systemName, attempted, found, refused, lastRefusal)?.let { return it }
    // The source refused everything, but the keyless fallback found some
    // covers: the refusal leads, because it is what the person has to fix.
    if (attempted > 0 && sourceRefused == attempted && lastRefusal != null) {
        return "$systemName: ${lastRefusal.source} refused every request" +
            describeRefusal(sourceRefused, attempted, lastRefusal) +
            " Only the keyless libretro thumbnails were used: $thumbnailed covers."
    }
    // hashMatched is the ES-DE "perfect match" count -- file digest
    // identical to ScreenScraper's own dump digest. The remainder of
    // $found matched by name search only, which is worth the user
    // knowing: right most of the time, verified never.
    val noMatch = attempted - found - failed - refused
    return buildString {
        append("$systemName: found $found ($hashMatched verified by file hash, ")
        append("$thumbnailed boxarts from libretro thumbnails, $miximaged miximages composed), ")
        append("no match for $noMatch, $failed failed")
        if (refused > 0) append(", $refused refused by the server")
        append(" (of $targeted targeted")
        if (attempted < targeted) append(", $attempted asked for before giving up")
        append(").")
        if (sourceRefused > 0) append(describeRefusal(sourceRefused, attempted, lastRefusal))
    }
}

/**
 * Ingests an external scraper's gamelist.xml for [folder] (docs/SPEC.md
 * section 7b, directed 2026-08-31): metadata upserts into the same
 * game_metadata rows the built-in scraper fills, media stays exactly
 * where the external tool wrote it (referenced by absolute path, never
 * copied). favorite/completed merge as logical OR with what the user
 * already set here; everything else the gamelist carries overwrites the
 * scraper-owned fields, which is what "import" means.
 */
suspend fun importGamelistXml(
    context: android.content.Context,
    folder: File,
): String = withContext(Dispatchers.IO) {
    val gamelist = dev.droidtop.library.consoles.GamelistXml.fileFor(folder)
    if (!gamelist.isFile) return@withContext "No gamelist.xml in ${folder.name} — run an external scraper against this folder first."
    val entries = try {
        dev.droidtop.library.consoles.GamelistXml.parse(gamelist)
    } catch (t: Exception) {
        return@withContext "Couldn't parse ${gamelist.absolutePath}: ${t.message}"
    }
    val dao = RomDatabase.get(context).romDao()
    var imported = 0
    var missing = 0
    entries.forEach { entry ->
        val romFile = dev.droidtop.library.consoles.GamelistXml.resolve(folder, entry.path)
        if (!romFile.isFile) {
            missing++
            return@forEach
        }
        val artwork = entry.imagePath
            ?.let { dev.droidtop.library.consoles.GamelistXml.resolve(folder, it) }
            ?.takeIf { it.isFile }?.absolutePath
        val video = entry.videoPath
            ?.let { dev.droidtop.library.consoles.GamelistXml.resolve(folder, it) }
            ?.takeIf { it.isFile }?.absolutePath
        val existing = dao.getGameMetadataSingle(romFile.absolutePath)
        val had = existing?.fieldSources
        // An imported gamelist is recorded as the source it is: whichever
        // scraper wrote it is not named in the file.
        val source = "gamelist.xml"
        val written = buildMap {
            if (entry.description != null) put(FieldSources.DESCRIPTION, source)
            if (entry.developer != null) put(FieldSources.DEVELOPER, source)
            if (entry.publisher != null) put(FieldSources.PUBLISHER, source)
            if (entry.genre != null) put(FieldSources.GENRE, source)
            if (entry.releaseDate != null) put(FieldSources.RELEASE_DATE, source)
            if (entry.rating != null) put(FieldSources.RATING, source)
            if (entry.players != null) put(FieldSources.PLAYERS, source)
            if (artwork != null) put(FieldSources.COVER, source)
        }
        dao.upsertGameMetadata(
            (existing ?: GameMetadataEntity(id = romFile.absolutePath)).copy(
                description = FieldSources.keep(had, FieldSources.DESCRIPTION, entry.description, existing?.description),
                developer = FieldSources.keep(had, FieldSources.DEVELOPER, entry.developer, existing?.developer),
                publisher = FieldSources.keep(had, FieldSources.PUBLISHER, entry.publisher, existing?.publisher),
                genre = FieldSources.keep(had, FieldSources.GENRE, entry.genre, existing?.genre),
                releaseDate = FieldSources.keep(had, FieldSources.RELEASE_DATE, entry.releaseDate, existing?.releaseDate),
                rating = FieldSources.keep(had, FieldSources.RATING, entry.rating, existing?.rating),
                players = FieldSources.keep(had, FieldSources.PLAYERS, entry.players, existing?.players),
                fieldSources = FieldSources.merge(had, written),
                favorite = (existing?.favorite == true) || entry.favorite,
                completed = (existing?.completed == true) || entry.completed,
                artworkPath = artwork ?: existing?.artworkPath,
                videoPath = video ?: existing?.videoPath,
            ),
        )
        imported++
    }
    "${folder.name}: imported $imported of ${entries.size} gamelist entries" +
        if (missing > 0) " ($missing point at files that aren't here)." else "."
}

/**
 * Applies a match the USER picked (see the shell's manual match picker)
 * to one game: the same metadata write and cover download the automatic
 * scrape performs, without the searching that got it wrong.
 *
 * User-edited fields are preserved exactly as the automatic path
 * preserves them -- a manual match corrects the scraped fields, it does
 * not reset somebody's favourite flag or their own edits.
 */
suspend fun applyManualMatch(
    context: Context,
    entry: dev.droidtop.library.LibraryEntry,
    theGamesDbId: Int,
): String = withContext(Dispatchers.IO) {
    val romFile = File(entry.id)
    val systemId = entry.systemId ?: return@withContext "No system for ${entry.title}."
    val apiKey = TheGamesDbPrefs.apiKey(context)
    if (apiKey.isBlank()) return@withContext ScraperReadiness.THEGAMESDB_KEY_MISSING
    val metadata = when (val lookup = TheGamesDbClient.metadataForId(apiKey, context.cacheDir, theGamesDbId)) {
        is ScrapeLookup.Found -> lookup.value
        ScrapeLookup.NoMatch -> return@withContext "TheGamesDB has no game under that id any more."
        is ScrapeLookup.Refused -> return@withContext "TheGamesDB refused the request (HTTP ${lookup.httpStatus})" +
            (lookup.reason?.let { ": $it." } ?: ".") +
            (ScraperReadiness.credentialFix(lookup)?.let { " $it" } ?: "")
    }

    val gamesRoot = dev.droidtop.library.GamesRoots.current(context)
        .firstOrNull { romFile.absolutePath.startsWith(it.absolutePath) }
        ?: romFile.parentFile?.parentFile
    val baseName = romFile.nameWithoutExtension
    if (gamesRoot != null && metadata.coverUrl != null) {
        val destination = File(
            File(File(File(gamesRoot, "downloaded_media"), systemId), "covers"),
            "$baseName.png",
        )
        // A hand-picked match REPLACES the wrong cover; the automatic
        // path skips an existing file, which here would leave the
        // picture of the game the user just rejected.
        runCatching { downloadImage(metadata.coverUrl, destination) }
        val staleMiximage = File(File(File(gamesRoot, "downloaded_media"), systemId), "miximages/$baseName.png")
        runCatching { staleMiximage.delete() }
        EsDeArtwork.mediaWritten(staleMiximage)
    }

    val dao = RomDatabase.get(context).romDao()
    val existing = dao.getGameMetadataSingle(romFile.absolutePath)
    val had = existing?.fieldSources
    val source = "TheGamesDB"
    val written = buildMap {
        if (metadata.description != null) put(FieldSources.DESCRIPTION, source)
        if (metadata.developer != null) put(FieldSources.DEVELOPER, source)
        if (metadata.publisher != null) put(FieldSources.PUBLISHER, source)
        if (metadata.genre != null) put(FieldSources.GENRE, source)
        if (metadata.releaseDate != null) put(FieldSources.RELEASE_DATE, source)
        if (metadata.players != null) put(FieldSources.PLAYERS, source)
        if (gamesRoot != null && metadata.coverUrl != null) put(FieldSources.COVER, source)
    }
    dao.upsertGameMetadata(
        (existing ?: GameMetadataEntity(id = romFile.absolutePath)).copy(
            description = FieldSources.keep(had, FieldSources.DESCRIPTION, metadata.description, existing?.description),
            developer = FieldSources.keep(had, FieldSources.DEVELOPER, metadata.developer, existing?.developer),
            publisher = FieldSources.keep(had, FieldSources.PUBLISHER, metadata.publisher, existing?.publisher),
            genre = FieldSources.keep(had, FieldSources.GENRE, metadata.genre, existing?.genre),
            releaseDate = FieldSources.keep(had, FieldSources.RELEASE_DATE, metadata.releaseDate, existing?.releaseDate),
            players = FieldSources.keep(had, FieldSources.PLAYERS, metadata.players, existing?.players),
            scrapeConfidence = "manual",
            fieldSources = FieldSources.merge(had, written),
        ),
    )
    "Matched ${entry.title} to ${metadata.name ?: "that entry"}."
}

/** Downloads [imageUrl] straight to [destination], creating parent directories as needed -- a plain generic helper, not tied to any one scraper source, and the single one in this package (the PC/engine scrape in PcScrape.kt shares it rather than carrying a second copy). */
internal fun downloadImage(imageUrl: String, destination: File) {
    destination.parentFile?.mkdirs()
    val connection = (java.net.URL(imageUrl).openConnection() as java.net.HttpURLConnection)
    if (connection.responseCode != 200) {
        throw java.io.IOException("Image download failed: HTTP ${connection.responseCode}")
    }
    connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
    EsDeArtwork.mediaWritten(destination)
}
