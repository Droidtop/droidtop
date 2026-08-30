package dev.droidtop.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.KeyEventType
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.ConsoleSystemEntity
import dev.droidtop.library.consoles.ConsoleSystemsDatabase
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.CustomPlayerPrefs
import dev.droidtop.library.consoles.ES_DE_CONSOLE_SYSTEMS
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.consoles.Player
import dev.droidtop.library.consoles.PlayerOverridePrefs
import dev.droidtop.library.consoles.SystemOverridePrefs
import dev.droidtop.library.consoles.availablePlayers
import dev.droidtop.library.consoles.resolvePlayer
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
import dev.droidtop.library.theme.SystemThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Explicit folder-to-system assignment -- the more robust design Daijishō
 * itself actually uses (its real PlatformEntity has an `itemsSyncTreeUriList`,
 * confirmed via its decompiled sources), letting a user override
 * [dev.droidtop.library.consoles.resolveSystem]'s name-based matching for
 * a folder that isn't named exactly the way ES-DE's data expects, or isn't
 * recognized at all. Structurally modeled on Daijishō's own real settings
 * pattern (a plain list of items, tap one to edit) -- not a copy of its
 * UI, since Daijishō's own screens are traditional View/RecyclerView-based
 * and droidtop's shells are Compose throughout.
 *
 * One flat list of every immediate subfolder across all configured games
 * roots, each showing its currently resolved system (tap to reassign) and
 * its currently resolved [dev.droidtop.library.consoles.Player.AmStart]
 * (tap to pick a different installed one, or add a custom one) -- real
 * per-system Player selection, backed by
 * [dev.droidtop.library.consoles.availablePlayers] (installed-only,
 * covering both [dev.droidtop.library.consoles.KnownPlayers]' real presets
 * and [dev.droidtop.library.consoles.CustomPlayerPrefs] entries) and
 * [dev.droidtop.library.consoles.PlayerOverridePrefs] (the user's explicit
 * choice, when set).
 */
class ConsoleSystemsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // darkTheme = true: this screen opens from inside the Handheld
        // shell (Settings tab -> Console systems), whose surface is
        // always dark -- see DroidtopTheme's own doc comment.
        setContent { dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) { ConsoleSystemsScreen() } }
    }
}

/**
 * Real, confirmed gap this fixes: every row in this screen was a plain
 * `Modifier.clickable{}` with zero focus handling -- functionally
 * unusable with a D-pad/gamepad alone (no visible focus indicator, and
 * nothing here ever requested initial focus, so a real controller had no
 * starting point to navigate from). Same pattern GamepadShell's own
 * SettingsLink/GameCard already use; duplicated here rather than shared
 * across modules since :app has no compile-time dependency on
 * :shell-gamepad (same reasoning as HandheldPrefs' own doc comment for
 * why settings-adjacent code in different modules reads/duplicates
 * rather than depends).
 */
private fun Modifier.gamepadFocusable(onClick: () -> Unit): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .clickable(onClick = onClick)
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp &&
                (event.key == Key.ButtonA || event.key == Key.DirectionCenter || event.key == Key.Enter)
            ) {
                onClick()
                true
            } else {
                false
            }
        }
        .background(if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(6.dp))
}

/**
 * Whether [folder] is worth surfacing as an assignable ROM-system
 * candidate at all -- checked recursively (every file up to
 * [ROM_LOOKALIKE_MAX_DEPTH] folders under [folder], not just its own
 * immediate children), matching ConsoleRomProvider.scanSystemFolder's own
 * recursive walk, since a real collection can nest ROMs under
 * per-letter/per-collection subfolders. [knownExtensions] is the union of
 * every real [ConsoleSystemDef]'s own extensions (built-in + user-added
 * platforms), not a fixed list -- a folder only counts as ROM-like if it
 * contains a file some real, currently-known system would actually claim.
 *
 * Depth is bounded, unlike [ConsoleRomProvider]'s own unlimited walk of an
 * already-*confirmed* system folder: this runs against every folder whose
 * name *didn't* resolve, including ones that are provably not ROM folders
 * at all (a real case this fixes: a GameNative-managed game-library sync
 * folder's own category subfolder, which can hold many real Windows game
 * installs, each with its own deep internal folder structure) -- walking
 * one of those to full depth just to conclude "no ROMs here" would be real,
 * needless work for a folder this check was always going to reject anyway.
 */
private const val ROM_LOOKALIKE_MAX_DEPTH = 4

private fun folderLooksRomLike(folder: File, knownExtensions: Set<String>): Boolean =
    folder.walkTopDown().maxDepth(ROM_LOOKALIKE_MAX_DEPTH).any { it.isFile && it.extension.lowercase() in knownExtensions }

@Composable
private fun ConsoleSystemsScreen() {
    val context = LocalContext.current
    var pickerForFolder by remember { mutableStateOf<File?>(null) }
    var playerPickerForSystem by remember { mutableStateOf<ConsoleSystemDef?>(null) }
    var addCustomPlayerForSystem by remember { mutableStateOf<ConsoleSystemDef?>(null) }
    var scraperSettingsOpen by remember { mutableStateOf(false) }
    var platformsOpen by remember { mutableStateOf(false) }
    var romFoldersOpen by remember { mutableStateOf(false) }
    var scrapingFolder by remember { mutableStateOf<File?>(null) }
    var scrapeStatus by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableStateOf(0) }
    // Bumped whenever a platform is added/edited/deleted/restored (from
    // PlatformsScreen) -- real systems now live in ConsoleSystemsRepository,
    // not a compile-time list, so every screen reading them has to reload
    // rather than trust a `remember` snapshot taken once.
    var systemsVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    var systemsById by remember { mutableStateOf<Map<String, ConsoleSystemDef>>(emptyMap()) }
    LaunchedEffect(systemsVersion) { systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id } }

    // Real bug this fixes: every immediate subfolder of every configured
    // root used to be shown here regardless of what's actually inside it --
    // a folder a root shares for a completely different purpose (e.g. a
    // GameNative-managed game-library sync folder's own category
    // subfolders, which contain zero ROM files) got flagged "Unrecognized
    // -- tap to assign a system" just because its name didn't happen to
    // match a known platform id/alias. Detection should drive this, not a
    // folder's name: a folder whose name doesn't resolve is only worth
    // surfacing here if it genuinely contains at least one file with a
    // known ROM extension SOMEWHERE under it -- checked recursively (not
    // just the folder's own top level), since a real collection can nest
    // ROMs under per-letter/per-collection subfolders the same way
    // ConsoleRomProvider.scanSystemFolder already has to handle. A folder
    // that resolves by name is always kept regardless of content -- a
    // recognized-but-currently-empty system folder is still worth showing
    // (e.g. to fix its player assignment ahead of adding ROMs later).
    // Off the composition/Main thread, not a plain `remember` block --
    // folderLooksRomLike does a real recursive filesystem walk, and a
    // large unresolved folder doing that synchronously during recomposition
    // is exactly the same "j2me, 18,128 files" UI-freeze class of bug
    // already fixed elsewhere in this codebase (Library.scanKinds,
    // ConsoleRomProvider's per-file cascade) -- not something to reintroduce
    // here just because this is a settings screen, not the main scan path.
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(version, systemsById) {
        if (systemsById.isEmpty()) return@LaunchedEffect
        folders = withContext(Dispatchers.IO) {
            val knownExtensions = systemsById.values.flatMap { it.extensions }.toSet()
            GamesRootPrefs.gamesRootPaths(context)
                .map(::File)
                .flatMap { root -> (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.toList() }
                .filter { folder ->
                    SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById) != null ||
                        folderLooksRomLike(folder, knownExtensions)
                }
                .sortedBy { it.name.lowercase() }
        }
    }

    // Plain black, matching GamepadShell's own background -- this screen is
    // reached from inside Handheld (Settings tab -> Console systems), so a
    // different dark-navy background here read as a visual inconsistency,
    // not an intentional design choice.
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val pickingFor = pickerForFolder
        val pickingPlayerFor = playerPickerForSystem
        val addingCustomFor = addCustomPlayerForSystem
        when {
            addingCustomFor != null -> AddCustomPlayerScreen(
                system = addingCustomFor,
                onSave = { name, pkg, args, kill ->
                    CustomPlayerPrefs.add(context, addingCustomFor.id, name, args, pkg, kill)
                    addCustomPlayerForSystem = null
                    version++
                },
                onCancel = { addCustomPlayerForSystem = null },
            )
            pickingPlayerFor != null -> PlayerPicker(
                system = pickingPlayerFor,
                onPick = { player ->
                    PlayerOverridePrefs.set(context, pickingPlayerFor.id, player?.id)
                    playerPickerForSystem = null
                    version++
                },
                onAddCustom = { addCustomPlayerForSystem = pickingPlayerFor },
                onDismiss = { playerPickerForSystem = null },
            )
            pickingFor != null -> SystemPicker(
                systems = systemsById.values.toList(),
                onPick = { system ->
                    SystemOverridePrefs.set(context, pickingFor.absolutePath, system?.id)
                    pickerForFolder = null
                    version++
                },
                onDismiss = { pickerForFolder = null },
            )
            scraperSettingsOpen -> ScraperSettingsScreen(onDismiss = { scraperSettingsOpen = false })
            platformsOpen -> PlatformsScreen(
                onDismiss = { platformsOpen = false; systemsVersion++ },
            )
            romFoldersOpen -> RomFoldersScreen(
                onDismiss = { romFoldersOpen = false; version++ },
            )
            else -> Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("Console systems", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Each folder's system is guessed from its name. Tap the system to " +
                        "assign a different one by hand, or tap the player to choose which " +
                        "installed emulator (or a custom one you add) actually runs it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                )
                val topFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { topFocus.requestFocus() }
                Text(
                    "Manage platforms (add, edit, delete)",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().focusRequester(topFocus).gamepadFocusable { platformsOpen = true }.padding(vertical = 8.dp),
                )
                Text(
                    "ROM folders (add or remove)",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().gamepadFocusable { romFoldersOpen = true }.padding(vertical = 8.dp),
                )
                // Real refresh of the data-driven player database (docs/
                // SPEC.md section 7e2) -- user-driven, same as scraping.
                var playerDbStatus by remember { mutableStateOf<String?>(null) }
                val playerDbScope = rememberCoroutineScope()
                Text(
                    playerDbStatus ?: "Update player database (emulator launch presets)",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().gamepadFocusable {
                        playerDbStatus = "Updating player database…"
                        playerDbScope.launch(Dispatchers.IO) {
                            playerDbStatus = runCatching {
                                val count = dev.droidtop.library.consoles.PlayersDatabaseUpdater.update(context)
                                "Player database updated ($count players)"
                            }.getOrElse { "Player database update failed: ${it.message}" }
                        }
                    }.padding(vertical = 8.dp),
                )
                // Real, matching this screen's other two rows -- this used
                // to be a plain TextButton (Material's touch-ripple
                // widget), the only row on this whole screen with no
                // .gamepadFocusable and no shared visual style, an actual
                // D-pad navigation dead end on a screen that's otherwise
                // fully gamepad-navigable. Not a hypothetical: reported
                // directly as the settings menu being "wired together
                // weirdly."
                run {
                    // ScreenScraper (the real default) works with zero
                    // configuration -- only TheGamesDB genuinely needs a
                    // key before it can scrape at all, so "not yet set up"
                    // only applies when TheGamesDB is the selected source.
                    val needsSetup = ScraperSourcePrefs.get(context) == ScraperSource.THEGAMESDB && !TheGamesDbPrefs.isConfigured(context)
                    Text(
                        if (needsSetup) "Set up ROM scraper credentials" else "ROM scraper credentials -- edit",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().gamepadFocusable { scraperSettingsOpen = true }.padding(vertical = 8.dp),
                    )
                }
                scrapeStatus?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (folders.isEmpty()) {
                    Text("No game folders configured yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folders) { folder ->
                        val resolved = SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById)
                        FolderRow(
                            folderName = folder.name,
                            resolvedSystem = resolved,
                            resolvedPlayer = resolved?.let { resolvePlayer(context, it) },
                            isScraping = scrapingFolder == folder,
                            onClickSystem = { pickerForFolder = folder },
                            onClickPlayer = { if (resolved != null) playerPickerForSystem = resolved },
                            onScrape = if (resolved != null) {
                                // Always offered, not gated on IGDB being
                                // configured -- Lutris (see
                                // scrapeSystemArtwork) needs no setup at
                                // all and is tried first regardless.
                                {
                                    scrapingFolder = folder
                                    scope.launch {
                                        scrapeStatus = scrapeSystemArtwork(context, folder, resolved) { done, total ->
                                            scrapeStatus = "Scraping ${resolved.displayName}: $done/$total"
                                        }
                                        scrapingFolder = null
                                        version++
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Scrapes cover art for every ROM in [folder] that doesn't already have
 * real artwork on disk (see [EsDeArtwork]) -- skips ones that do,
 * since re-scraping unchanged games on every run would waste IGDB's real
 * rate limit for no benefit. Runs on [Dispatchers.IO] (real network I/O,
 * one request per missing game, deliberately sequential rather than
 * parallel -- IGDB's own rate limiting is per-second, not something to
 * hammer from N concurrent coroutines).
 */
/**
 * Real ROM metadata/cover-art scrape -- uses exactly ONE scraper source,
 * matching real ES-DE's own actual architecture (confirmed against real
 * source, `es-app/src/scrapers/Scraper.cpp`): ES-DE has no automatic
 * multi-source fallback/priority chain at all, just a real single
 * user-selected source ([ScraperSourcePrefs], default "screenscraper",
 * ES-DE's own real default too). Both real scrapers ported directly from
 * real ES-DE source this session (see [ScreenScraperClient]/
 * [TheGamesDbClient]'s own doc comments) -- Lutris/IGDB are deliberately
 * NOT used here: they're droidtop's real scrapers for PC/Wine/Linux/
 * engine games, a different real content category from console ROMs.
 *
 * Persists BOTH the cover image (existing `downloaded_media` layout
 * [EsDeArtwork] already reads) AND real per-game metadata (via
 * [GameMetadataEntity]/[RomDao.upsertGameMetadata] -- see that entity's
 * own doc comment for why it's a separate, rescan-durable table). Skips a
 * ROM only when it already has BOTH real artwork AND real metadata,
 * matching a real "actually still missing something" check rather than
 * artwork alone.
 */
private suspend fun scrapeSystemArtwork(
    context: android.content.Context,
    folder: File,
    system: ConsoleSystemDef,
    onProgress: (done: Int, total: Int) -> Unit,
): String = withContext(Dispatchers.IO) {
    val gamesRoot = folder.parentFile ?: folder
    val romFiles = folder.walkTopDown().filter { it.isFile && it.extension.lowercase() in system.extensions }.toList()
    val dao = RomDatabase.get(context).romDao()
    val existingMetadataIds = dao.getGameMetadata(romFiles.map { it.absolutePath }).map { it.id }.toSet()
    val missing = romFiles.filter {
        EsDeArtwork.resolve(gamesRoot, system.id, it.nameWithoutExtension) == null || it.absolutePath !in existingMetadataIds
    }
    if (missing.isEmpty()) return@withContext "${system.displayName}: every ROM already has real artwork and metadata."

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
    missing.forEachIndexed { index, romFile ->
        onProgress(index, missing.size)
        try {
            val screenScraperResult = screenScraperSystemId?.let {
                ScreenScraperClient.findMetadata(
                    systemeId = it.toString(),
                    romName = romFile.name,
                    romSizeBytes = romFile.length(),
                    devId = devId,
                    devPassword = devPassword,
                    userId = userId,
                    userPassword = userPassword,
                )
            }
            val gamesDbResult = gamesDbSystemId?.let {
                TheGamesDbClient.findMetadata(gamesDbApiKey, context.cacheDir, it, romFile.nameWithoutExtension)
            }

            val coverUrl = screenScraperResult?.coverUrl ?: gamesDbResult?.coverUrl
            if (coverUrl != null && EsDeArtwork.resolve(gamesRoot, system.id, romFile.nameWithoutExtension) == null) {
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
            val hasAnyMetadata = listOfNotNull(description, developer, publisher, genre, releaseDate, players, rating).isNotEmpty()
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
    "${system.displayName}: found $found, no match for ${missing.size - found - failed}, $failed failed (of ${missing.size} missing artwork/metadata)."
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

@Composable
private fun FolderRow(
    folderName: String,
    resolvedSystem: ConsoleSystemDef?,
    resolvedPlayer: Player.AmStart?,
    isScraping: Boolean,
    onClickSystem: () -> Unit,
    onClickPlayer: () -> Unit,
    onScrape: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Real per-system accent (see SystemThemeColors) instead of a flat
    // gray card for every row -- matches both the bundled DEcaffe/ES-DE
    // theme's own per-system color data and Daijishō's real per-platform
    // colored borders (a live iiSU screenshot this session showed distinct
    // red/pink/purple/teal/green tiles per system, not one uniform color).
    // A low-alpha tint, not the raw saturated color -- this is a settings
    // list of many rows at once, not a single-item hero card, so full
    // saturation would be visually loud rather than a subtle system cue.
    val accent = resolvedSystem?.let { SystemThemeColors.forSystem(context, it.id) }?.let { Color(it) }

    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min).background(MaterialTheme.colorScheme.background)) {
        if (accent != null) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(accent))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .background(if (accent != null) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth().gamepadFocusable(onClickSystem)) {
                Text(folderName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(
                    resolvedSystem?.displayName ?: "Unrecognized -- tap to assign a system",
                    color = if (resolvedSystem != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (resolvedSystem != null) {
                Text(
                    resolvedPlayer?.let { "Player: ${it.name}" } ?: "No installed player for this system -- tap to add one",
                    color = if (resolvedPlayer != null) (accent ?: MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().gamepadFocusable(onClickPlayer).padding(top = 6.dp),
                )
            }
            if (onScrape != null) {
                Text(
                    if (isScraping) "Scraping artwork..." else "Scrape missing artwork (Lutris + IGDB)",
                    color = if (isScraping) Color.Gray else (accent ?: MaterialTheme.colorScheme.primary),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { if (!isScraping) it.gamepadFocusable(onScrape) else it }
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * Real credential entry for ROM metadata scraping -- ScreenScraper (real
 * ES-DE's own default/primary scraper) and TheGamesDB (real ES-DE's other
 * real scraper), both ported directly from real ES-DE source this session
 * (see [ScreenScraperClient]/[TheGamesDbClient]'s own doc comments).
 * ScreenScraper's four fields are all real but optional (a real anonymous
 * mode exists at a lower rate limit); TheGamesDB needs its own real,
 * self-service API key before it can be used at all. IGDB/Lutris are
 * deliberately NOT configured here -- they're droidtop's real scrapers for
 * PC/Wine/Linux/engine games, a different real content category from the
 * console ROMs this screen scrapes (see [scrapeSystemArtwork]'s own doc
 * comment).
 */
@Composable
private fun ScraperSettingsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(ScraperSourcePrefs.get(context)) }
    var devId by remember { mutableStateOf(ScreenScraperPrefs.devId(context)) }
    var devPassword by remember { mutableStateOf(ScreenScraperPrefs.devPassword(context)) }
    var userId by remember { mutableStateOf(ScreenScraperPrefs.userId(context)) }
    var userPassword by remember { mutableStateOf(ScreenScraperPrefs.userPassword(context)) }
    var gamesDbApiKey by remember { mutableStateOf(TheGamesDbPrefs.apiKey(context)) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("ROM artwork/metadata scraper", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Real ES-DE only ever uses ONE scraper source at a time, not an automatic " +
                "fallback chain -- pick one below, matching ES-DE's own real behavior exactly. " +
                "ScreenScraper works with everything below left blank (a real, lower-rate-limit " +
                "anonymous mode) -- fill in your own account (ssid/sspassword, a free " +
                "screenscraper.fr account) for a higher personal limit. TheGamesDB needs its own " +
                "free API key (thegamesdb.net -> sign up -> API key) before it can be used at all.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Checkbox(checked = source == ScraperSource.SCREENSCRAPER, onCheckedChange = { source = ScraperSource.SCREENSCRAPER })
            Text("ScreenScraper (ES-DE's own real default)", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Checkbox(checked = source == ScraperSource.THEGAMESDB, onCheckedChange = { source = ScraperSource.THEGAMESDB })
            Text("TheGamesDB", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        Text("ScreenScraper dev ID (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(
            value = devId,
            onValueChange = { devId = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text("ScreenScraper dev password (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(
            value = devPassword,
            onValueChange = { devPassword = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text("ScreenScraper account ssid (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(
            value = userId,
            onValueChange = { userId = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text("ScreenScraper account sspassword (optional)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(
            value = userPassword,
            onValueChange = { userPassword = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text("TheGamesDB API key", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BasicTextField(
            value = gamesDbApiKey,
            onValueChange = { gamesDbApiKey = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(
                onClick = {
                    ScraperSourcePrefs.set(context, source)
                    ScreenScraperPrefs.set(context, devId.trim(), devPassword.trim(), userId.trim(), userPassword.trim())
                    TheGamesDbPrefs.set(context, gamesDbApiKey.trim())
                    onDismiss()
                },
            ) { Text("Save") }
        }
    }
}

@Composable
private fun SystemPicker(systems: List<ConsoleSystemDef>, onPick: (ConsoleSystemDef?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, systems) {
        if (query.isBlank()) {
            systems
        } else {
            systems.filter {
                it.displayName.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
            }
        }.sortedBy { it.displayName.lowercase() }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Assign a system", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
        )
        val firstItemFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstItemFocus.requestFocus() }
        TextButton(onClick = { onPick(null) }, modifier = Modifier.focusRequester(firstItemFocus)) {
            Text("Clear override (use automatic matching)")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered) { system ->
                Text(
                    "${system.displayName} (${system.id})",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .gamepadFocusable { onPick(system) }
                        .padding(vertical = 10.dp),
                )
            }
        }
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }
}

/**
 * Every real, currently-installed [Player.AmStart] for [system] (see
 * [availablePlayers] -- custom players, [dev.droidtop.library.consoles.KnownPlayers]'
 * real presets pulled from Daijishō's own wiki, then RetroArch), same
 * "flat list, tap to pick" shape [SystemPicker] already uses. Empty state
 * (no real emulator installed for this system yet) offers "Add a player"
 * directly rather than just saying "nothing here."
 */
@Composable
private fun PlayerPicker(system: ConsoleSystemDef, onPick: (Player.AmStart?) -> Unit, onAddCustom: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val players = remember(system) { availablePlayers(context, system) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Player for ${system.displayName}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Only installed emulators are listed. Pick one to launch this system with it every time.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        if (players.isEmpty()) {
            Text("No installed emulator can run ${system.displayName} yet.", color = MaterialTheme.colorScheme.tertiary)
        } else {
            val firstItemFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { firstItemFocus.requestFocus() }
            TextButton(onClick = { onPick(null) }, modifier = Modifier.focusRequester(firstItemFocus)) {
                Text("Clear override (use first installed)")
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(players) { player ->
                    Text(
                        player.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .gamepadFocusable { onPick(player) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
        TextButton(onClick = onAddCustom) { Text("+ Add a player") }
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }
}

/**
 * droidtop's own version of Daijishō's real "Add a player" form (confirmed
 * via a live screenshot of that exact screen this session: name field,
 * multiline am-start-arguments field pre-filled with a real template shape,
 * "kill package processes" toggle) -- same fields, same real
 * `{file.path}`/`{file.uri}` placeholder convention
 * [dev.droidtop.library.consoles.AmStartCommandToIntentConverter] already
 * implements. The one addition: an explicit package-name field, since
 * [availablePlayers] needs it separately to check install status without
 * re-parsing the arguments template.
 */
@Composable
private fun AddCustomPlayerScreen(
    system: ConsoleSystemDef,
    onSave: (name: String, pkg: String, args: String, kill: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("-a android.intent.action.VIEW\n-n org.example.app/.MainActivity\n-d {file.uri}") }
    var kill by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Add a player for ${system.displayName}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text("Player name", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = name,
            onValueChange = { name = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text("Package name (e.g. org.example.app)", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = pkg,
            onValueChange = { pkg = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text(
            "Player am start arguments -- use \"{file.path}\" and \"{file.uri}\" to specify the file to be played.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
        BasicTextField(
            value = args,
            onValueChange = { args = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = kill, onCheckedChange = { kill = it })
            Text("Kill package processes before am start", color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(
                onClick = { onSave(name.ifBlank { pkg }, pkg, args, kill) },
                enabled = name.isNotBlank() && pkg.isNotBlank() && args.isNotBlank(),
            ) { Text("Save") }
        }
    }
}

/**
 * Real, full platform CRUD -- the actual "Daijishō-level" ask this
 * answers: add a brand-new platform (not just override which existing
 * one a folder maps to), edit any platform's display name/extensions/
 * RetroArch core (built-in ones included, same permissiveness Daijishō
 * itself allows), delete one, or restore every built-in back to
 * [ES_DE_CONSOLE_SYSTEMS]'s own defaults. Reads/writes exclusively
 * through [ConsoleSystemsRepository] -- see its own doc comment for why
 * that replaced the compile-time list as the real source of truth.
 */
@Composable
private fun PlatformsScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var systems by remember { mutableStateOf<List<ConsoleSystemEntity>>(emptyList()) }
    var editing by remember { mutableStateOf<ConsoleSystemEntity?>(null) }
    var addingNew by remember { mutableStateOf(false) }
    var reloadVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadVersion) {
        val dao = ConsoleSystemsDatabase.get(context).consoleSystemDao()
        if (dao.count() == 0) ConsoleSystemsRepository.allSystems(context) // triggers the real seed-if-empty path
        systems = dao.getAll()
    }

    val editingNow = editing
    when {
        addingNew || editingNow != null -> PlatformEditScreen(
            entity = editingNow,
            onSave = { entity ->
                scope.launch {
                    ConsoleSystemsDatabase.get(context).consoleSystemDao().upsert(entity)
                    addingNew = false
                    editing = null
                    reloadVersion++
                }
            },
            onDelete = if (editingNow != null) {
                {
                    scope.launch {
                        ConsoleSystemsDatabase.get(context).consoleSystemDao().delete(editingNow.id)
                        editing = null
                        reloadVersion++
                    }
                }
            } else {
                null
            },
            onCancel = { addingNew = false; editing = null },
        )
        else -> Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Manage platforms", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Every platform droidtop recognizes -- add a new one, edit any field " +
                    "(built-in platforms included), or delete one. \"Restore defaults\" " +
                    "resets every built-in platform back to its original values without " +
                    "touching any platform you added yourself.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { firstFocus.requestFocus() }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    "+ Add platform",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.focusRequester(firstFocus).gamepadFocusable { addingNew = true }.padding(8.dp),
                )
                Text(
                    "Restore defaults",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.gamepadFocusable {
                        scope.launch {
                            ConsoleSystemsRepository.restoreDefaults(context)
                            reloadVersion++
                        }
                    }.padding(8.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(systems) { system ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .gamepadFocusable { editing = system }
                            .padding(vertical = 10.dp),
                    ) {
                        Text("${system.displayName} (${system.id})", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                system.extensionsCsv.ifBlank { null }?.let { "extensions: $it" },
                                system.retroArchCore?.let { "core: $it" },
                                if (system.isBuiltIn) "built-in" else "custom",
                            ).joinToString("  ·  "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss) { Text("Back") }
        }
    }
}

@Composable
private fun PlatformEditScreen(
    entity: ConsoleSystemEntity?,
    onSave: (ConsoleSystemEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var id by remember { mutableStateOf(entity?.id.orEmpty()) }
    var displayName by remember { mutableStateOf(entity?.displayName.orEmpty()) }
    var extensionsCsv by remember { mutableStateOf(entity?.extensionsCsv.orEmpty()) }
    var retroArchCore by remember { mutableStateOf(entity?.retroArchCore.orEmpty()) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(if (entity != null) "Edit platform" else "Add platform", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text("Id (used as the ROMs subfolder name, e.g. \"psx\")", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = id,
            onValueChange = { if (entity == null) id = it }, // real id is the primary key -- editable only when adding new, never after (would silently orphan every SystemOverridePrefs entry pointing at the old id)
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (entity == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text("Display name", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = displayName,
            onValueChange = { displayName = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text("File extensions (comma-separated, e.g. \"nes,unf\")", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = extensionsCsv,
            onValueChange = { extensionsCsv = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        Text("RetroArch core (optional, e.g. \"nestopia\")", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
        BasicTextField(
            value = retroArchCore,
            onValueChange = { retroArchCore = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        )
        if (entity?.isBuiltIn == true && !confirmingDelete) {
            Text(
                "This is a built-in platform -- deleting it can be undone with \"Restore defaults\".",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            if (onDelete != null) {
                TextButton(onClick = { if (confirmingDelete) onDelete() else confirmingDelete = true }) {
                    Text(if (confirmingDelete) "Confirm delete" else "Delete")
                }
            }
            TextButton(
                onClick = {
                    onSave(
                        ConsoleSystemEntity(
                            id = id.trim(),
                            displayName = displayName.ifBlank { id }.trim(),
                            extensionsCsv = extensionsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","),
                            retroArchCore = retroArchCore.trim().ifBlank { null },
                            isBuiltIn = entity?.isBuiltIn ?: false,
                        ),
                    )
                },
                enabled = id.isNotBlank(),
            ) { Text("Save") }
        }
    }
}

/**
 * Real, user-facing ROM-folder management -- previously the only way to
 * add or remove a games root after first-run onboarding was clearing app
 * data by hand over adb. Reuses the exact SAF `OpenDocumentTree` ->
 * `takePersistableUriPermission` -> `resolveStoragePath` -> `addGamesRoot`
 * sequence [OnboardingActivity]'s own GAMES_FOLDERS step already proves
 * out -- same real, established flow, not reinvented here.
 */
@Composable
private fun RomFoldersScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var roots by remember { mutableStateOf(GamesRootPrefs.gamesRootPaths(context).sorted()) }
    var unresolvedWarning by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resolved = GamesRootPrefs.resolveStoragePath(uri)
        if (resolved != null) {
            GamesRootPrefs.addGamesRoot(context, resolved)
            roots = GamesRootPrefs.gamesRootPaths(context).sorted()
        }
        unresolvedWarning = resolved == null
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("ROM folders", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text(
            "droidtop scans <folder>/<system>/<romFile> under each of these. Add another " +
                "folder (an SD card, a second internal folder, ...), or remove one you no " +
                "longer want scanned. Changes take effect next time the library rescans.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        if (unresolvedWarning) {
            Text(
                "Couldn't resolve that folder to a real path on this device -- not added.",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val firstFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { firstFocus.requestFocus() }
        Text(
            "+ Add a folder",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus).gamepadFocusable { pickFolder.launch(null) }.padding(vertical = 8.dp),
        )
        if (roots.isEmpty()) {
            Text("No ROM folders configured.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(roots) { path ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(path, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "Remove",
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.gamepadFocusable {
                            GamesRootPrefs.removeGamesRoot(context, path)
                            roots = GamesRootPrefs.gamesRootPaths(context).sorted()
                        }.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
    }
}
