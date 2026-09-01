package dev.droidtop.shell.gamepad

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.GamesRoots
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.consoles.SystemOverridePrefs
import dev.droidtop.library.scraper.importGamelistXml
import dev.droidtop.library.scraper.scrapeSystemArtwork
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

object GamelistSortPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var focusIndex by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf(GamelistSortPrefs.get(context, groupKey)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val actions = buildList {
        add("Sort: ${sort.label}")
        if (systemId != null) {
            add("Scrape this system")
            add("Import gamelist.xml")
        }
        add("Close")
    }

    fun consoleFoldersFor(id: String): List<java.io.File> {
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        return GamesRoots.current(context).flatMap { root ->
            (root.listFiles() ?: emptyArray()).filter { folder ->
                folder.isDirectory &&
                    SystemOverridePrefs.resolveForFolder(context, folder.absolutePath, folder.name, systemsById)?.id == id
            }
        }
    }

    fun activate(index: Int) {
        when (actions[index]) {
            "Sort: ${sort.label}" -> {
                sort = GamelistSortPrefs.cycle(context, groupKey)
                onSortChanged()
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
                            scrapeSystemArtwork(context, folder, system) { done, total ->
                                status = "Scraping ${system.displayName}: $done/$total"
                            }
                        }
                    }
                    status = results.joinToString("\n")
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
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .width(520.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    when (GamepadKeyMap.actionFor(event.key)) {
                        GamepadAction.UP -> {
                            focusIndex = (focusIndex - 1 + actions.size) % actions.size
                            EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.DOWN -> {
                            focusIndex = (focusIndex + 1) % actions.size
                            EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.A -> {
                            activate(focusIndex)
                            true
                        }
                        GamepadAction.B, GamepadAction.BACK, GamepadAction.SELECT -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(groupLabel, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                actions.forEachIndexed { index, label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (index == focusIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (index == focusIndex) MaterialTheme.colorScheme.surfaceVariant
                                else MaterialTheme.colorScheme.surface,
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Up/Down moves, A activates, B closes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
