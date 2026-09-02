package dev.droidtop.shell.gamepad

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemOverridePrefs
import dev.droidtop.library.scraper.importGamelistXml
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.library.scraper.scrapeSystemArtwork
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Per-gamelist sort order, persisted per group (the pattern is real
 * ES-DE's GuiGamelistOptions "SORT GAMES BY"; the placement and controls
 * are droidtop's own -- per direction, ES-DE's general UI is the copy
 * target, never its literal control scheme).
 */
enum class GamelistSort(val label: String) {
    NAME("Name"),
    RATING("Rating"),
    RELEASE_DATE("Release date"),
    LAST_PLAYED("Last played"),
}

/**
 * Which games a gamelist shows (the GuiGamelistFilter idea, kept to the
 * states droidtop actually stores per game). Persisted per group like
 * the sort order, so a filtered list stays filtered on the way back.
 */
enum class GamelistFilter(val label: String) {
    ALL("All games"),
    FAVORITES("Favorites"),
    COMPLETED("Completed"),
    UNPLAYED("Never played"),
    ;

    fun matches(entry: LibraryEntry): Boolean = when (this) {
        ALL -> true
        FAVORITES -> entry.favorite
        COMPLETED -> entry.completed
        UNPLAYED -> entry.lastPlayedEpochMs == null
    }
}

object GamelistFilterPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_PREFIX = "droidtop_gamelist_filter_"

    fun get(context: Context, groupKey: String): GamelistFilter {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + groupKey, null) ?: return GamelistFilter.ALL
        return runCatching { GamelistFilter.valueOf(raw) }.getOrDefault(GamelistFilter.ALL)
    }

    fun cycle(context: Context, groupKey: String): GamelistFilter {
        val next = GamelistFilter.entries[(get(context, groupKey).ordinal + 1) % GamelistFilter.entries.size]
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + groupKey, next.name).apply()
        return next
    }
}

object GamelistSortPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_PREFIX = "droidtop_gamelist_sort_"

    fun get(context: Context, groupKey: String): GamelistSort {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + groupKey, null) ?: return GamelistSort.NAME
        return runCatching { GamelistSort.valueOf(raw) }.getOrDefault(GamelistSort.NAME)
    }

    fun cycle(context: Context, groupKey: String): GamelistSort {
        val next = GamelistSort.entries[(get(context, groupKey).ordinal + 1) % GamelistSort.entries.size]
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + groupKey, next.name).apply()
        return next
    }

    fun comparator(sort: GamelistSort): Comparator<LibraryEntry> = when (sort) {
        GamelistSort.NAME -> compareBy { it.title.lowercase() }
        GamelistSort.RATING -> compareByDescending<LibraryEntry> { it.rating ?: -1f }.thenBy { it.title.lowercase() }
        GamelistSort.RELEASE_DATE -> compareBy<LibraryEntry> { it.releaseDate ?: "99999999" }.thenBy { it.title.lowercase() }
        GamelistSort.LAST_PLAYED -> compareByDescending<LibraryEntry> { it.lastPlayedEpochMs ?: 0L }.thenBy { it.title.lowercase() }
    }
}

/** The one label for the PC/engine scrape action, shared by the list that offers it and the handler that runs it. */
private const val SCRAPE_PC_GAMES = "Scrape PC & engine games"

/**
 * The in-gamelist options overlay (the ES-DE GuiGamelistOptions
 * PATTERN: sort, scrape, and library actions right where the user is,
 * never a settings detour -- per direction, actions live on the main
 * screen and Settings is configuration only). Entirely
 * controller-driven: Up/Down moves, A activates, B closes.
 *
 * Scrape and import resolve the group's real folders themselves (every
 * configured games root's child folders whose resolved system is this
 * group's), run in place with live status, and never navigate away.
 */
@Composable
internal fun GamelistOptionsMenu(
    groupKey: String,
    groupLabel: String,
    systemId: String?,
    onSortChanged: () -> Unit,
    onScraped: () -> Unit,
    onDismiss: () -> Unit,
    // The gamelist as it is currently shown, so jump and random address
    // exactly what the user is looking at rather than a second copy.
    games: List<LibraryEntry> = emptyList(),
    onJumpTo: (Int) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var focusIndex by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf(GamelistSortPrefs.get(context, groupKey)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(GamelistFilterPrefs.get(context, groupKey)) }
    // Per-system launch-screen default (docs/SPEC.md section 4c: "Select
    // which display to open ROMs from this tab" / "Tune individual
    // platforms"). A per-game choice still wins over this; cycling back
    // to "Ask" clears it.
    var systemLaunchScreen by remember {
        mutableStateOf(systemId?.let { dev.droidtop.library.LaunchScreenMemory.systemChoice(context, it) })
    }
    // The letter list replaces the action list in place: a menu that
    // pushes a second dialog on a handheld is a menu you get lost in.
    var pickingLetter by remember { mutableStateOf(false) }
    val letters = remember(games) {
        games.map { entry ->
            entry.title.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
        }.distinct().sorted()
    }

    val libraryScope = groupKey.isEmpty()
    val actions = buildList {
        if (libraryScope) {
            // The library-wide actions that used to live in Settings.
            add("Rescan library")
            add("Scrape all systems")
            add("Update platform databases")
        } else {
            add("Sort: ${sort.label}")
            add("Show: ${filter.label}")
            if (games.isNotEmpty()) {
                add("Jump to letter")
                add("Random game")
            }
            if (systemId != null) {
                add("Launch screen: " + (systemLaunchScreen?.label ?: "Ask"))
                add("Scrape this system")
                add("Import gamelist.xml")
            }
            // Offered wherever PC or engine games are actually on
            // screen, which is the same "act on what you are looking
            // at" placement every other action here uses. Those groups
            // have no console systemId, so the action above never
            // covered them and there was no way to scrape them at all.
            if (games.any { it.isPcOrEngineGame }) add(SCRAPE_PC_GAMES)
        }
        add("Close")
    }

    suspend fun consoleFoldersFor(id: String): List<java.io.File> {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        return GamesRoots.current(context).flatMap { root ->
            (root.listFiles() ?: emptyArray()).filter { folder ->
                folder.isDirectory &&
                    SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById)?.id == id
            }
        }
    }

    fun jumpToLetter(index: Int) {
        val letter = letters.getOrNull(index) ?: return
        val target = games.indexOfFirst { entry ->
            (entry.title.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#') == letter
        }
        if (target >= 0) {
            onJumpTo(target)
            onDismiss()
        }
    }

    fun activate(index: Int) {
        when (actions[index]) {
            "Rescan library" -> {
                onScraped()
                onDismiss()
            }
            "Scrape all systems" -> {
                if (busy) return
                busy = true
                scope.launch {
                    val summary = withContext(Dispatchers.IO) {
                        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
                        val folders = GamesRoots.current(context).flatMap { root ->
                            (root.listFiles() ?: emptyArray()).filter { it.isDirectory }
                        }
                        val targets = folders.mapNotNull { folder ->
                            SystemOverridePrefs.resolveForFolder(
                                context,
                                folder.absolutePath,
                                folder.name,
                                systemsById,
                            )?.let { folder to it }
                        }
                        if (targets.isEmpty()) {
                            "No game folders to scrape."
                        } else {
                            var done = 0
                            targets.forEach { (folder, system) ->
                                done++
                                status = "[$done/${targets.size}] ${system.displayName}"
                                scrapeSystemArtwork(context, folder, system) { fileDone, total ->
                                    status = "[$done/${targets.size}] ${system.displayName}: $fileDone/$total"
                                }
                            }
                            "Scraped ${targets.size} systems."
                        }
                    }
                    status = summary
                    busy = false
                    onScraped()
                }
            }
            "Update platform databases" -> {
                if (busy) return
                busy = true
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        runCatching {
                            val players = dev.droidtop.library.consoles.PlayersDatabaseUpdater.update(context)
                            val engines = dev.droidtop.library.EnginesDatabase.update(context)
                            "Updated: $players players, $engines engines."
                        }.getOrElse { "Update failed: ${it.message}" }
                    }
                    busy = false
                }
            }
            "Sort: ${sort.label}" -> {
                sort = GamelistSortPrefs.cycle(context, groupKey)
                onSortChanged()
            }
            "Show: ${filter.label}" -> {
                filter = GamelistFilterPrefs.cycle(context, groupKey)
                onSortChanged()
            }
            "Launch screen: " + (systemLaunchScreen?.label ?: "Ask") -> {
                val next = when (systemLaunchScreen) {
                    null -> dev.droidtop.library.LaunchScreen.BUILT_IN
                    dev.droidtop.library.LaunchScreen.BUILT_IN -> dev.droidtop.library.LaunchScreen.SECOND
                    dev.droidtop.library.LaunchScreen.SECOND -> null
                }
                systemLaunchScreen = next
                systemId?.let { dev.droidtop.library.LaunchScreenMemory.setSystemChoice(context, it, next) }
            }
            "Jump to letter" -> {
                pickingLetter = true
                focusIndex = 0
            }
            "Random game" -> {
                if (games.isNotEmpty()) {
                    onJumpTo(games.indices.random())
                    onDismiss()
                }
            }
            "Scrape this system" -> {
                if (busy) return
                busy = true
                scope.launch {
                    status = "Scraping $groupLabel…"
                    val results = withContext(Dispatchers.IO) {
                        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
                        val system = systemsById[systemId] ?: return@withContext listOf("Unknown system $systemId")
                        val folders = consoleFoldersFor(system.id)
                        if (folders.isEmpty()) return@withContext listOf("No folder for ${system.displayName} in any games root.")
                        folders.map { folder ->
                            scrapeSystemArtwork(
                                context,
                                folder,
                                system,
                                onProgress = { done, total ->
                                    status = "Scraping ${system.displayName}: $done/$total"
                                },
                            )
                        }
                    }
                    status = results.joinToString("\n")
                    busy = false
                    onScraped()
                }
            }
            SCRAPE_PC_GAMES -> {
                if (busy) return
                busy = true
                val pcGames = games.filter { it.isPcOrEngineGame }
                scope.launch {
                    status = withContext(Dispatchers.IO) {
                        dev.droidtop.library.scraper.PcScraper.scrape(context, pcGames) { done, total ->
                            status = "Scraping PC & engine games: $done/$total"
                        }
                    }
                    busy = false
                    onScraped()
                }
            }
            "Import gamelist.xml" -> {
                if (busy) return
                busy = true
                scope.launch {
                    val results = withContext(Dispatchers.IO) {
                        consoleFoldersFor(systemId!!).ifEmpty { null }
                            ?.map { folder -> importGamelistXml(context, folder) }
                            ?: listOf("No folder for $groupLabel in any games root.")
                    }
                    status = results.joinToString("\n")
                    busy = false
                    onScraped()
                }
            }
            "Close" -> onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(520.dp),
            focusLabel = "Gamelist options",
            onKey = { event ->
                if (event.type != KeyEventType.KeyUp) {
                    false
                } else {
                    val itemCount = if (pickingLetter) letters.size else actions.size
                    when (GamepadKeyMap.actionFor(event.key)) {
                        GamepadAction.UP -> {
                            if (itemCount > 0) focusIndex = (focusIndex - 1 + itemCount) % itemCount
                            EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.DOWN -> {
                            if (itemCount > 0) focusIndex = (focusIndex + 1) % itemCount
                            EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.A -> {
                            if (pickingLetter) jumpToLetter(focusIndex) else activate(focusIndex)
                            true
                        }
                        GamepadAction.B, GamepadAction.BACK, GamepadAction.SELECT -> {
                            // Back out of the letter list to the actions
                            // first; only then close the menu.
                            if (pickingLetter) {
                                pickingLetter = false
                                focusIndex = 0
                            } else {
                                onDismiss()
                            }
                            true
                        }
                        else -> false
                    }
                }
            },
        ) {
            Text(
                groupLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MenuTokens.OnSurface,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            if (pickingLetter) {
                letters.forEachIndexed { index, letter ->
                    MenuRow(
                        title = letter.toString(),
                        selected = index == focusIndex,
                        onClick = {
                            focusIndex = index
                            jumpToLetter(index)
                        },
                    )
                }
            } else {
                actions.forEachIndexed { index, label ->
                    MenuRow(
                        title = label,
                        selected = index == focusIndex,
                        onClick = {
                            focusIndex = index
                            activate(index)
                        },
                    )
                }
            }
            status?.let { MenuRow(title = it.lineSequence().first(), subtitle = it.substringAfter('\n', "").ifEmpty { null }) }
            MenuHint(
                if (pickingLetter) "Up/Down moves, A jumps, B goes back" else "Up/Down moves, A activates, B closes",
            )
        }
    }
}
