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
// the MAIN screen, so the Handheld shell must be able to run scrapes
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
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): String = withContext(Dispatchers.IO) {
    val gamesRoot = folder.parentFile ?: folder
    val romFiles = folder.walkTopDown().filter { it.isFile && it.extension.lowercase() in system.extensions }.toList()
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
    if (source == ScraperSource.THEGAMESDB && (gamesDbSystemId == null || !TheGamesDbPrefs.isConfigured(context))) {
        return@withContext "${system.displayName}: TheGamesDB needs a configured API key and platform support for this system."
    }
    // The keyless libretro-database source: one cached DAT set per
    // system, matched by the same No-Intro naming as the thumbnails.
    val libretroLookup = if (source == ScraperSource.LIBRETRO) {
        dev.droidtop.library.scraper.LibretroMetadata.load(context, system.id)
            ?: return@withContext "${system.displayName}: no libretro database name is mapped for this system."
    } else null

    var found = 0
    var failed = 0
    var hashMatched = 0
    var thumbnailed = 0
    var miximaged = 0
    targets.forEachIndexed { index, romFile ->
        onProgress(index, targets.size)
        try {
            // Real ES-DE automatic mode: hash the file (up to its own
            // 384 MiB default cap) and search WITH the digest — hash
            // identity in the response is the confidence check, not any
            // string similarity (ported from GuiScraperSearch.cpp /
            // ScreenScraper.cpp, read before porting).
            val localMd5 = if (screenScraperSystemId != null) {
                dev.droidtop.library.scraper.RomHash.md5OrNull(romFile)
            } else null
            val screenScraperResult = screenScraperSystemId?.let {
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
            val confidence = when {
                screenScraperResult == null -> null
                localMd5 != null && screenScraperResult.romMd5 == localMd5 -> {
                    hashMatched++
                    "hash"
                }
                else -> "name"
            }
            val gamesDbResult = gamesDbSystemId?.let {
                TheGamesDbClient.findMetadata(gamesDbApiKey, context.cacheDir, it, romFile.nameWithoutExtension)
            }
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
            if (wantArtwork && coverUrl != null && EsDeArtwork.resolve(gamesRoot, system.id, baseName) == null) {
                downloadImage(coverUrl, File(mediaRoot, "covers/$baseName.png"))
            }
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
                        cover = File(mediaRoot, "covers/$baseName.png").takeIf { it.isFile },
                        physicalMedia = File(mediaRoot, "physicalmedia/$baseName.png").takeIf { it.isFile },
                        output = miximage,
                    )
                    if (composed) miximaged++
                }
            }

            val description = screenScraperResult?.description ?: gamesDbResult?.description
            val developer = screenScraperResult?.developer ?: gamesDbResult?.developer ?: libretroResult?.developer
            val publisher = screenScraperResult?.publisher ?: gamesDbResult?.publisher ?: libretroResult?.publisher
            val genre = screenScraperResult?.genre ?: gamesDbResult?.genre ?: libretroResult?.genre
            val releaseDate = screenScraperResult?.releaseDate ?: gamesDbResult?.releaseDate ?: libretroResult?.releaseDate
            val players = screenScraperResult?.players ?: gamesDbResult?.players ?: libretroResult?.players
            val rating = screenScraperResult?.rating
            val hasAnyMetadata = wantMetadata &&
                listOfNotNull(description, developer, publisher, genre, releaseDate, players, rating).isNotEmpty()
            if (hasAnyMetadata) {
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
                dao.upsertGameMetadata(
                    (existing ?: GameMetadataEntity(id = romFile.absolutePath)).copy(
                        scrapeConfidence = confidence ?: existing?.scrapeConfidence,
                        description = description ?: existing?.description,
                        developer = developer ?: existing?.developer,
                        publisher = publisher ?: existing?.publisher,
                        genre = genre ?: existing?.genre,
                        releaseDate = releaseDate ?: existing?.releaseDate,
                        rating = rating ?: existing?.rating,
                        players = players ?: existing?.players,
                    ),
                )
            }
            if (coverUrl != null || hasAnyMetadata) found++
        } catch (t: Exception) {
            failed++
            android.util.Log.e("droidtop.Scraper", "Failed to scrape ${romFile.name}", t)
        }
    }
    // hashMatched is the ES-DE "perfect match" count -- file digest
    // identical to ScreenScraper's own dump digest. The remainder of
    // $found matched by name search only, which is worth the user
    // knowing: right most of the time, verified never.
    "${system.displayName}: found $found ($hashMatched verified by file hash, " +
        "$thumbnailed boxarts from libretro thumbnails, $miximaged miximages composed), no match for " +
        "${targets.size - found - failed}, $failed failed (of ${targets.size} targeted)."
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
    if (!gamelist.isFile) return@withContext "No gamelist.xml in ${folder.name} -- run an external scraper against this folder first."
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
        dao.upsertGameMetadata(
            (existing ?: GameMetadataEntity(id = romFile.absolutePath)).copy(
                description = entry.description ?: existing?.description,
                developer = entry.developer ?: existing?.developer,
                publisher = entry.publisher ?: existing?.publisher,
                genre = entry.genre ?: existing?.genre,
                releaseDate = entry.releaseDate ?: existing?.releaseDate,
                rating = entry.rating ?: existing?.rating,
                players = entry.players ?: existing?.players,
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
    if (apiKey.isBlank()) return@withContext "TheGamesDB needs its API key."
    val metadata = TheGamesDbClient.metadataForId(apiKey, context.cacheDir, theGamesDbId)
        ?: return@withContext "That match returned nothing."

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
        runCatching { File(File(File(gamesRoot, "downloaded_media"), systemId), "miximages/$baseName.png").delete() }
    }

    val dao = RomDatabase.get(context).romDao()
    val existing = dao.getGameMetadataSingle(romFile.absolutePath)
    dao.upsertGameMetadata(
        (existing ?: GameMetadataEntity(id = romFile.absolutePath)).copy(
            description = metadata.description ?: existing?.description,
            developer = metadata.developer ?: existing?.developer,
            publisher = metadata.publisher ?: existing?.publisher,
            genre = metadata.genre ?: existing?.genre,
            releaseDate = metadata.releaseDate ?: existing?.releaseDate,
            players = metadata.players ?: existing?.players,
            scrapeConfidence = "manual",
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
}
