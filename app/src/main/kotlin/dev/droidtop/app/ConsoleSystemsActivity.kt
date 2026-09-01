package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import dev.droidtop.app.settings.AppSettingsCatalogs
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.scraper.ScraperSource
import dev.droidtop.library.scraper.ScraperSourcePrefs
import dev.droidtop.library.scraper.ScreenScraperClient
import dev.droidtop.library.scraper.ScreenScraperPrefs
import dev.droidtop.library.scraper.ScreenScraperSystemIds
import dev.droidtop.library.scraper.TheGamesDbClient
import dev.droidtop.library.scraper.TheGamesDbPrefs
import dev.droidtop.library.scraper.TheGamesDbSystemIds
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.CatalogNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Console-systems management, rendered from the shared settings catalog
 * (docs/SPEC.md settings architecture): the actual screen DATA -- folder
 * list, per-folder system/player choices, scraping, platform CRUD, ROM
 * folders, scraper credentials -- lives in
 * [dev.droidtop.app.settings.AppSettingsCatalogs] and is chromed here by
 * the same [CatalogNavigator] the Handheld shell's own Settings section
 * uses, so reaching this from anywhere looks and drives exactly like the
 * rest of settings instead of a one-off hand-rolled screen (which this
 * used to be -- reported directly as "ew" on sight). This Activity is
 * just a host for entry points that aren't already inside a settings
 * surface.
 */
class ConsoleSystemsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettingsCatalogs.ensureRegistered()
        val screen = SettingsScreenRegistry.get(AppSettingsCatalogs.SCREEN_CONSOLE_SYSTEMS)!!
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CatalogNavigator(root = screen, onExit = { finish() })
                }
            }
        }
    }
}

/**
 * Real ROM metadata/cover-art scrape -- uses exactly ONE scraper source,
 * matching real ES-DE's own actual architecture (confirmed against real
 * source, `es-app/src/scrapers/Scraper.cpp`): ES-DE has no automatic
 * multi-source fallback/priority chain at all, just a real single
 * user-selected source ([ScraperSourcePrefs], default "screenscraper",
 * ES-DE's own real default too). Both real scrapers ported directly from
 * real ES-DE source (see [ScreenScraperClient]/[TheGamesDbClient]'s own
 * doc comments) -- Lutris/IGDB are deliberately NOT used here: they're
 * droidtop's real scrapers for PC/Wine/Linux/engine games, a different
 * real content category from console ROMs.
 *
 * Persists BOTH the cover image (existing `downloaded_media` layout
 * [EsDeArtwork] already reads) AND real per-game metadata (via
 * [GameMetadataEntity]/[RomDao.upsertGameMetadata] -- see that entity's
 * own doc comment for why it's a separate, rescan-durable table). Skips a
 * ROM only when it already has BOTH real artwork AND real metadata,
 * matching a real "actually still missing something" check rather than
 * artwork alone. Internal: invoked by the settings catalog's per-folder
 * scrape action (AppSettingsCatalogs).
 */
internal suspend fun scrapeSystemArtwork(
    context: android.content.Context,
    folder: File,
    system: ConsoleSystemDef,
    onProgress: (done: Int, total: Int) -> Unit,
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
    if (missing.isEmpty()) return@withContext "${system.displayName}: nothing matches the \"${filter.label}\" scrape filter."

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

    var found = 0
    var failed = 0
    var hashMatched = 0
    var thumbnailed = 0
    missing.forEachIndexed { index, romFile ->
        onProgress(index, missing.size)
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
            if (wantArtwork && coverUrl != null && EsDeArtwork.resolve(gamesRoot, system.id, romFile.nameWithoutExtension) == null) {
                val destination = File(File(File(gamesRoot, "downloaded_media"), system.id), "covers/${romFile.nameWithoutExtension}.png")
                downloadImage(coverUrl, destination)
            }

            val description = screenScraperResult?.description ?: gamesDbResult?.description
            val developer = screenScraperResult?.developer ?: gamesDbResult?.developer
            val publisher = screenScraperResult?.publisher ?: gamesDbResult?.publisher
            val genre = screenScraperResult?.genre ?: gamesDbResult?.genre
            val releaseDate = screenScraperResult?.releaseDate ?: gamesDbResult?.releaseDate
            val players = screenScraperResult?.players ?: gamesDbResult?.players
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
        "$thumbnailed boxarts from libretro thumbnails), no match for " +
        "${missing.size - found - failed}, $failed failed (of ${missing.size} matching the filter)."
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
internal suspend fun importGamelistXml(
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

/** Downloads [imageUrl] straight to [destination], creating parent directories as needed -- a plain generic helper, not tied to any one scraper source. */
private fun downloadImage(imageUrl: String, destination: File) {
    destination.parentFile?.mkdirs()
    val connection = (java.net.URL(imageUrl).openConnection() as java.net.HttpURLConnection)
    if (connection.responseCode != 200) {
        throw java.io.IOException("Image download failed: HTTP ${connection.responseCode}")
    }
    connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
}
