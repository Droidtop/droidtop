package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import kotlinx.coroutines.launch

/**
 * Full-screen, controller-first library shell — the "handheld style" UI.
 * Never the default; a user opts into this the same way they'd opt into
 * :shell-desktop (see dev.droidtop.app.ShellPreference in :app).
 *
 * **Structure, not pixels**: three top-level sections — Games, Apps,
 * Settings (an emulation-focused section, a general-apps section, a
 * config section) — a genuinely good structure for this kind of
 * launcher. Original composition throughout, droidtop's own visual
 * language, not a port of any reference app.
 * Games browses engine-first then per-engine game-second — the same
 * System → Game hierarchy ES-DE uses — since [LibraryEntryKind]'s
 * emulated/interpreted kinds (Ren'Py, RPG Maker) are droidtop's
 * equivalent of ES-DE's "systems." Apps is a flat, kind-sectioned browser
 * for everything that isn't emulated content (native Android apps, Wine
 * profiles, Linux-container apps, remote streams) — droidtop's actual
 * differentiator (§1) is treating all of that as equally first-class,
 * which is exactly why it's a separate top-level section from Games
 * rather than folded in as just another "system."
 *
 * D-pad navigation between cards is Compose's own default focus-key
 * handling — every focusable() node already responds to DPAD_UP/DOWN/LEFT/
 * RIGHT key events without custom code. This file only needs to grab
 * initial focus, make cards visibly react when focused, and handle the
 * back-out-of-a-drill-down case explicitly (Games' engine → per-engine
 * grid navigation).
 *
 * Deliberately NOT implemented: analog left-stick-as-navigation (needs a
 * real controller to tune against, none was available while writing
 * this), a real Settings screen (placeholder card only — droidtop's
 * actual settings live in `:shell-default`'s `SettingsActivity` per
 * docs/SPEC.md §4; whether Handheld gets its own in-shell settings
 * surface or just launches that one isn't decided), and dual-screen
 * presentation (§4's `DualScreenCoordinator` decides role assignment,
 * nothing here renders companion content on a second screen yet).
 */
@Composable
fun GamepadShell(library: Library, onFocusedEntryChanged: (LibraryEntry?) -> Unit = {}) {
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var section by remember { mutableStateOf(HandheldSection.GAMES) }
    var canGoBack by remember { mutableStateOf(false) }
    var detailEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    val scope = rememberCoroutineScope()
    val onLaunch: (LibraryEntry) -> Unit = { entry -> scope.launch { library.launch(entry) } }

    LaunchedEffect(library) {
        entries = library.scanAll()
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        SectionTabBar(current = section, onSelect = { section = it })
        Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            val currentEntries = entries
            val entry = detailEntry
            when {
                currentEntries == null -> CircularProgressIndicator(color = Color.White)
                entry != null -> EntryDetailScreen(
                    entry = entry,
                    onLaunch = { onLaunch(entry); detailEntry = null },
                    onClose = { detailEntry = null },
                )
                else -> when (section) {
                    HandheldSection.GAMES -> GamesSection(
                        entries = currentEntries.filter { it.kind in GAME_KINDS },
                        onLaunch = onLaunch,
                        onShowDetail = { detailEntry = it },
                        onDrillDownChanged = { canGoBack = it },
                        onFocusedEntryChanged = onFocusedEntryChanged,
                    )
                    HandheldSection.APPS -> {
                        canGoBack = false
                        AppsSection(
                            entries = currentEntries.filter { it.kind in APP_KINDS },
                            onLaunch = onLaunch,
                            onShowDetail = { detailEntry = it },
                            onFocusedEntryChanged = onFocusedEntryChanged,
                        )
                    }
                    HandheldSection.SETTINGS -> {
                        canGoBack = false
                        SettingsSection()
                    }
                }
            }
        }
        ButtonHintFooter(canGoBack = canGoBack || detailEntry != null, showInfo = detailEntry == null)
    }
}

/**
 * Full-screen detail view for one entry — one horizontal row of primary
 * actions (Launch, leading/highlighted) instead of launch being a card's
 * only behavior. Reached via a card's Y/Info action, closed via B/Back.
 */
@Composable
private fun EntryDetailScreen(entry: LibraryEntry, onLaunch: () -> Unit, onClose: () -> Unit) {
    val launchFocus = remember { FocusRequester() }
    LaunchedEffect(entry) { launchFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && (event.key == Key.Back || event.key == Key.ButtonB)) {
                    onClose()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(entry.title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
        Text(entry.kind.displayName(), color = Color.Gray, style = MaterialTheme.typography.titleMedium)
        if (entry.playtimeSeconds > 0) {
            Text("Played ${entry.playtimeSeconds / 60} min", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        }
        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionChip("Launch", highlighted = true, modifier = Modifier.focusRequester(launchFocus), onClick = onLaunch)
            ActionChip("Back", highlighted = false, onClick = onClose)
        }
    }
}

@Composable
private fun ActionChip(label: String, highlighted: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (highlighted) Color.Black else Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
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
            .background(
                if (highlighted) Color.White else if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                RoundedCornerShape(50),
            )
            .border(width = if (focused && !highlighted) 2.dp else 0.dp, color = Color.White, shape = RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/**
 * A persistent, always-visible legend for what the controller's face
 * buttons currently do — never leaves the user guessing, a real,
 * valuable pattern not present in this shell before (docs/SPEC.md §7).
 */
@Composable
private fun ButtonHintFooter(canGoBack: Boolean, showInfo: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .padding(horizontal = 48.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        ButtonHint("A", "Select")
        if (showInfo) ButtonHint("Y", "Info")
        if (canGoBack) ButtonHint("B", "Back")
    }
}

@Composable
private fun ButtonHint(button: String, action: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            button,
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Text(action, color = Color.Gray, style = MaterialTheme.typography.labelMedium)
    }
}

internal enum class HandheldSection { GAMES, APPS, SETTINGS }

/** Emulated/interpreted content — droidtop's equivalent of ES-DE's "systems." Everything else (native/Wine/Linux/remote) is Apps, not a system. */
private val GAME_KINDS = setOf(
    LibraryEntryKind.RENPY,
    LibraryEntryKind.RPG_MAKER_MV,
    LibraryEntryKind.RPG_MAKER_MZ,
    LibraryEntryKind.RPG_MAKER_VX_ACE,
)
private val APP_KINDS = setOf(
    LibraryEntryKind.NATIVE_ANDROID_APP,
    LibraryEntryKind.WINE_PROFILE,
    LibraryEntryKind.LINUX_CONTAINER_APP,
    LibraryEntryKind.REMOTE_STREAM,
)

@Composable
private fun SectionTabBar(current: HandheldSection, onSelect: (HandheldSection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        HandheldSection.entries.forEach { entrySection ->
            val focused = entrySection == current
            Text(
                text = entrySection.displayName(),
                color = if (focused) Color.White else Color.Gray,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp &&
                            (event.key == Key.ButtonA || event.key == Key.DirectionCenter || event.key == Key.Enter)
                        ) {
                            onSelect(entrySection)
                            true
                        } else {
                            false
                        }
                    },
            )
        }
    }
}

private fun HandheldSection.displayName(): String = when (this) {
    HandheldSection.GAMES -> "Games"
    HandheldSection.APPS -> "Apps"
    HandheldSection.SETTINGS -> "Settings"
}

/**
 * Engine-first, then per-engine game grid — ES-DE's System → Game
 * hierarchy, applied to droidtop's own [LibraryEntryKind]s. `selectedEngine
 * == null` shows the engine list; picking one drills into that engine's
 * games. Back (D-pad-focused "Back" card, or the controller's B/Back key)
 * returns to the engine list.
 */
@Composable
private fun GamesSection(
    entries: List<LibraryEntry>,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onDrillDownChanged: (Boolean) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
) {
    var selectedEngine by remember { mutableStateOf<LibraryEntryKind?>(null) }
    var recentOnly by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(selectedEngine) { onDrillDownChanged(selectedEngine != null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Back || event.key == Key.ButtonB) &&
                    selectedEngine != null
                ) {
                    selectedEngine = null
                    true
                } else {
                    false
                }
            },
    ) {
        val engine = selectedEngine
        if (engine == null) {
            val continuePlaying = entries.filter { it.lastPlayedEpochMs != null }.sortedByDescending { it.lastPlayedEpochMs }
            val byEngine = entries.groupBy { it.kind }
            LaunchedEffect(entries) { firstFocus.requestFocus() }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                if (continuePlaying.isNotEmpty()) {
                    item(key = "continue-playing") {
                        HomeSectionRow(
                            HomeSection("Continue Playing", continuePlaying),
                            firstCardFocus = null,
                            onLaunch = onLaunch,
                            onShowDetail = onShowDetail,
                            onFocusedEntryChanged = onFocusedEntryChanged,
                        )
                    }
                }
                items(GAME_KINDS.filter { byEngine.containsKey(it) }, key = { it.name }) { kind ->
                    EngineCard(
                        kind = kind,
                        count = byEngine.getValue(kind).size,
                        modifier = if (kind == GAME_KINDS.filter { byEngine.containsKey(it) }.firstOrNull()) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                        onSelect = { selectedEngine = kind },
                    )
                }
                if (entries.isEmpty()) {
                    item(key = "empty") { Text("No games detected yet.", color = Color.White, modifier = Modifier.padding(horizontal = 48.dp)) }
                }
            }
        } else {
            val allGames = entries.filter { it.kind == engine }
            val recentCount = allGames.count { it.lastPlayedEpochMs != null }
            val games = if (recentOnly) allGames.filter { it.lastPlayedEpochMs != null } else allGames
            LaunchedEffect(engine, recentOnly) { firstFocus.requestFocus() }
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FilterChip("${allGames.size} items", selected = !recentOnly, onClick = { recentOnly = false })
                    if (recentCount > 0) {
                        FilterChip("$recentCount recent", selected = recentOnly, onClick = { recentOnly = true })
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    gridItemsIndexed(games, key = { _, entry -> entry.id }) { index, entry ->
                        GameCard(
                            entry = entry,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            onLaunch = { onLaunch(entry) },
                            onShowDetail = { onShowDetail(entry) },
                            onFocused = { onFocusedEntryChanged(entry) },
                        )
                    }
                }
            }
        }
    }
}

/** Inline quick-filter chip — a view/scope toggle right in the header, no separate filter menu. */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (selected) Color.Black else Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
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
            .background(
                if (selected) Color.White else if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                RoundedCornerShape(50),
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun EngineCard(kind: LibraryEntryKind, count: Int, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .padding(horizontal = 48.dp)
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.ButtonA || event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onSelect()
                    true
                } else {
                    false
                }
            }
            .border(
                width = if (focused) 4.dp else 1.dp,
                color = if (focused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(12.dp),
            )
            .background(if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .padding(24.dp),
    ) {
        Text(kind.displayName(), color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("  ($count)", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
    }
}

/** Flat, kind-sectioned browser — no drill-down, unlike Games: apps aren't organized into "systems." */
@Composable
private fun AppsSection(
    entries: List<LibraryEntry>,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
) {
    val sections = buildAppSections(entries)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(entries) { firstFocus.requestFocus() }

    if (sections.isEmpty()) {
        Text("No apps detected yet.", color = Color.White)
        return
    }
    var firstAssigned = false
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        items(sections, key = { it.title }) { homeSection ->
            HomeSectionRow(
                homeSection,
                firstCardFocus = if (!firstAssigned) firstFocus else null,
                onLaunch = onLaunch,
                onShowDetail = onShowDetail,
                onFocusedEntryChanged = onFocusedEntryChanged,
            )
            firstAssigned = true
        }
    }
}

@Composable
private fun SettingsSection() {
    Text("Settings live in the Standard shell for now — see docs/SPEC.md §4.", color = Color.White)
}

internal data class HomeSection(val title: String, val entries: List<LibraryEntry>)

/** One section per display name actually present among [entries], in [LibraryEntryKind] declaration order. */
internal fun buildAppSections(entries: List<LibraryEntry>): List<HomeSection> {
    val byDisplayName = entries.groupBy { it.kind.displayName() }
    val order = LibraryEntryKind.entries.map { it.displayName() }.distinct()
    return order.mapNotNull { name -> byDisplayName[name]?.let { HomeSection(name, it) } }
}

private fun LibraryEntryKind.displayName(): String = when (this) {
    LibraryEntryKind.NATIVE_ANDROID_APP -> "Apps"
    LibraryEntryKind.WINE_PROFILE -> "Windows"
    LibraryEntryKind.LINUX_CONTAINER_APP -> "Linux"
    LibraryEntryKind.REMOTE_STREAM -> "Remote PC"
    LibraryEntryKind.RENPY, LibraryEntryKind.KIRIKIRI -> "Visual Novels"
    LibraryEntryKind.RPG_MAKER_MV, LibraryEntryKind.RPG_MAKER_MZ, LibraryEntryKind.RPG_MAKER_VX_ACE -> "RPG Maker"
}

@Composable
private fun HomeSectionRow(
    section: HomeSection,
    firstCardFocus: FocusRequester?,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
) {
    Column {
        Text(
            section.title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(section.entries, key = { _, entry -> entry.id }) { index, entry ->
                GameCard(
                    entry = entry,
                    modifier = if (index == 0 && firstCardFocus != null) Modifier.focusRequester(firstCardFocus) else Modifier,
                    onLaunch = { onLaunch(entry) },
                    onShowDetail = { onShowDetail(entry) },
                    onFocused = { onFocusedEntryChanged(entry) },
                )
            }
        }
    }
}

@Composable
private fun GameCard(entry: LibraryEntry, modifier: Modifier = Modifier, onLaunch: () -> Unit, onShowDetail: () -> Unit, onFocused: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .size(width = 220.dp, height = 260.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            // DPAD_CENTER/Enter already trigger Modifier.clickable's default
            // key handling on a focused node, but a controller's face button
            // (A / cross) reports as a distinct keycode on most Android
            // gamepad mappings — handled explicitly here rather than relying
            // on clickable() to cover it. Y opens the detail screen (§7).
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (event.key) {
                    Key.ButtonA, Key.DirectionCenter, Key.Enter -> {
                        onLaunch()
                        true
                    }
                    Key.ButtonY -> {
                        onShowDetail()
                        true
                    }
                    else -> false
                }
            }
            .border(
                width = if (focused) 4.dp else 1.dp,
                color = if (focused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A),
                RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(entry.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(entry.kind.name, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
    }
}
