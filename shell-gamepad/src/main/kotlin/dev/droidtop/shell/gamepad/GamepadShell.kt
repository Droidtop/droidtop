package dev.droidtop.shell.gamepad

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.ES_DE_CONSOLE_SYSTEMS
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
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var section by remember { mutableStateOf(HandheldPrefs.defaultSection(context)) }
    var canGoBack by remember { mutableStateOf(false) }
    var detailEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    val scope = rememberCoroutineScope()
    val onLaunch: (LibraryEntry) -> Unit = { entry -> scope.launch { library.launch(entry) } }
    val tabBarFocus = remember { FocusRequester() }

    LaunchedEffect(library) {
        entries = library.scanAll()
    }
    // Real bug this fixes: onKeyEvent modifiers (L1/R1 section-switching
    // below, GamesSection's Left/Right sibling-system switching) only ever
    // see a key event by it bubbling up from whatever's currently focused
    // -- if literally nothing has focus yet (the initial frame before
    // `entries` loads, or an empty section with no focusable card at all),
    // there is no focused node to bubble from, so those handlers silently
    // never fire. Confirmed as the real cause of a reported "L1/R1 doesn't
    // do anything" -- landing on the default Games tab with an empty
    // library left nothing focused. Grabbing focus onto the tab bar itself
    // on first composition guarantees a valid focus target always exists;
    // GamesSection/AppsSection still steal focus onto real content once it
    // loads, same as before.
    LaunchedEffect(Unit) { tabBarFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Shoulder-button section switching -- a standard console-UI
            // pattern (Daijishō and most console launchers use L1/R1 to
            // cycle top-level tabs) that works regardless of what currently
            // has focus, unlike D-pad navigation up into the tab bar. Only
            // active when nothing has already consumed the event (detail
            // screen's own Back handling, individual card key handlers,
            // etc. all take priority since they're closer to the focused
            // node in the bubbling chain).
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp || detailEntry != null) return@onKeyEvent false
                val sections = HandheldSection.entries
                val currentIndex = sections.indexOf(section)
                when (event.key) {
                    Key.ButtonL1 -> {
                        section = sections[(currentIndex - 1 + sections.size) % sections.size]
                        true
                    }
                    Key.ButtonR1 -> {
                        section = sections[(currentIndex + 1) % sections.size]
                        true
                    }
                    else -> false
                }
            },
    ) {
        SectionTabBar(current = section, onSelect = { section = it }, currentTabFocus = tabBarFocus)
        Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            val currentEntries = entries
            val entry = detailEntry
            when {
                // Real bug this fixes: the loading spinner used to gate this
                // entire content area unconditionally, before `section` was
                // ever checked -- Settings (which needs zero scan data) was
                // stuck behind the same load state as Games/Apps, showing as
                // "empty" even though it's a plain static list with nothing
                // to wait for. detailEntry is also section-independent, so
                // it stays checked before the loading gate too.
                entry != null -> EntryDetailScreen(
                    entry = entry,
                    onLaunch = { onLaunch(entry); detailEntry = null },
                    onClose = { detailEntry = null },
                )
                section == HandheldSection.SETTINGS -> {
                    canGoBack = false
                    SettingsSection()
                }
                currentEntries == null -> CircularProgressIndicator(color = Color.White)
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
                    // SETTINGS is handled above, before the loading gate --
                    // unreachable here, kept only so `when` stays exhaustive.
                    HandheldSection.SETTINGS -> Unit
                }
            }
        }
        if (HandheldPrefs.showHints(context)) {
            ButtonHintFooter(
                canGoBack = canGoBack || detailEntry != null,
                showInfo = detailEntry == null,
                showSectionSwitch = detailEntry == null,
                showSystemSwitch = detailEntry == null && section == HandheldSection.GAMES && canGoBack,
            )
        }
    }
}

/**
 * Reads the mode-specific preferences set from :shell-default's real
 * settings screen (SettingsHandheldFragment / murine_prefs_handheld.xml).
 * No compile-time dependency on :shell-default from here -- it and
 * :shell-gamepad are separate library modules wired together only by :app
 * -- so this reads the same SharedPreferences file
 * ("com.android.launcher3.prefs", com.android.launcher3.LauncherFiles.
 * SHARED_PREFERENCES_KEY) by its literal name instead.
 */
private object HandheldPrefs {
    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_DEFAULT_SECTION = "pref_handheld_default_section"
    private const val KEY_SHOW_HINTS = "pref_handheld_show_hints"

    fun defaultSection(context: Context): HandheldSection {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_DEFAULT_SECTION, "games")) {
            "apps" -> HandheldSection.APPS
            else -> HandheldSection.GAMES
        }
    }

    fun showHints(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_HINTS, true)
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
        if (entry.artworkUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp)),
            ) {
                AsyncImage(
                    model = entry.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp)),
                )
                // Platform/kind label overlaid on the art, matching Daijishō's
                // own detail-screen layout (boxart with the platform name
                // overlaid at the bottom of the art) -- structure, not pixels.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(12.dp),
                ) {
                    Text(entry.kind.displayName(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(entry.title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
        if (entry.artworkUri == null) {
            Text(entry.kind.displayName(), color = Color.Gray, style = MaterialTheme.typography.titleMedium)
        }
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
private fun ButtonHintFooter(
    canGoBack: Boolean,
    showInfo: Boolean,
    showSectionSwitch: Boolean = false,
    showSystemSwitch: Boolean = false,
) {
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
        // L1/R1 switch top-level sections (Games/Apps/Settings) from
        // anywhere; Left/Right additionally jump between sibling systems
        // while browsing a per-engine game grid -- ES-DE's own documented
        // "General navigation" convention (left/right "navigate ... between
        // gamelists"), adopted here for the same reason it works well
        // there: skips a Back-then-reselect round trip.
        if (showSystemSwitch) ButtonHint("◄/►", "Switch system")
        if (showSectionSwitch) ButtonHint("L/R", "Switch section")
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
    LibraryEntryKind.CONSOLE_ROM,
)
private val APP_KINDS = setOf(
    LibraryEntryKind.NATIVE_ANDROID_APP,
    LibraryEntryKind.WINE_PROFILE,
    LibraryEntryKind.LINUX_CONTAINER_APP,
    LibraryEntryKind.REMOTE_STREAM,
)

@Composable
private fun SectionTabBar(current: HandheldSection, onSelect: (HandheldSection) -> Unit, currentTabFocus: FocusRequester) {
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
                modifier = (if (entrySection == current) Modifier.focusRequester(currentTabFocus) else Modifier)
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
 * One browsable group in Games' system list -- either a non-ROM engine
 * ([LibraryEntryKind] like RENPY) or a real console system (an NES/GBA/PSX
 * ROM folder, keyed by [LibraryEntry.systemId]). Splitting these out is
 * the real fix for CONSOLE_ROM entries previously all bucketing under one
 * flat "Consoles" card regardless of which actual system they were --
 * losing exactly the System → Game structure ES-DE (and every other real
 * emulation frontend) uses, which was the whole point of adopting that
 * model in the first place.
 */
private sealed interface GameGroup {
    val key: String
    val label: String

    data class Engine(val kind: LibraryEntryKind) : GameGroup {
        override val key get() = "engine:${kind.name}"
        override val label get() = kind.displayName()
    }

    data class System(val systemId: String) : GameGroup {
        override val key get() = "system:$systemId"
        override val label get() = ES_DE_CONSOLE_SYSTEMS.firstOrNull { it.id == systemId }?.displayName ?: systemId
    }
}

private fun LibraryEntry.gameGroup(): GameGroup =
    if (kind == LibraryEntryKind.CONSOLE_ROM) GameGroup.System(systemId ?: "unknown") else GameGroup.Engine(kind)

/**
 * System-first, then per-system game grid — ES-DE's System → Game
 * hierarchy, applied both to droidtop's own engine [LibraryEntryKind]s and
 * to real console systems (see [GameGroup]). `selectedGroup == null` shows
 * the system list; picking one drills into that system's games. Back
 * (D-pad-focused "Back" card, or the controller's B/Back key) returns to
 * the system list.
 */
@Composable
private fun GamesSection(
    entries: List<LibraryEntry>,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onDrillDownChanged: (Boolean) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
) {
    var selectedGroup by remember { mutableStateOf<GameGroup?>(null) }
    var recentOnly by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    // Present-and-non-empty groups -- engines first (in GAME_KINDS'
    // declaration order), then real console systems (alphabetical by
    // display name) -- hoisted so both the system-list view and the
    // per-system grid view share one ordering (needed for ES-DE-style
    // Left/Right sibling-system switching below).
    val byGroup = entries.groupBy { it.gameGroup() }
    val orderedEngineGroups = GAME_KINDS
        .filter { it != LibraryEntryKind.CONSOLE_ROM }
        .map { GameGroup.Engine(it) }
        .filter { byGroup.containsKey(it) }
    val orderedSystemGroups = byGroup.keys
        .filterIsInstance<GameGroup.System>()
        .sortedBy { it.label.lowercase() }
    val orderedGroups: List<GameGroup> = orderedEngineGroups + orderedSystemGroups

    LaunchedEffect(selectedGroup) { onDrillDownChanged(selectedGroup != null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                val group = selectedGroup
                when {
                    (event.key == Key.Back || event.key == Key.ButtonB) && group != null -> {
                        selectedGroup = null
                        true
                    }
                    // ES-DE's real, documented "General navigation" convention:
                    // Left/Right inside a gamelist jumps directly to the
                    // adjacent system's gamelist rather than requiring a
                    // Back-then-reselect round trip through the system list.
                    event.key == Key.DirectionLeft && group != null && orderedGroups.size > 1 -> {
                        val index = orderedGroups.indexOf(group)
                        selectedGroup = orderedGroups[(index - 1 + orderedGroups.size) % orderedGroups.size]
                        true
                    }
                    event.key == Key.DirectionRight && group != null && orderedGroups.size > 1 -> {
                        val index = orderedGroups.indexOf(group)
                        selectedGroup = orderedGroups[(index + 1) % orderedGroups.size]
                        true
                    }
                    else -> false
                }
            },
    ) {
        val group = selectedGroup
        if (group == null) {
            val continuePlaying = entries.filter { it.lastPlayedEpochMs != null }.sortedByDescending { it.lastPlayedEpochMs }
            val hasAnyGroupCard = orderedGroups.isNotEmpty()
            // firstFocus is only ever attached to a Modifier below when there's
            // at least one GroupCard to attach it to -- requesting focus
            // otherwise throws (FocusRequester not initialized), which is
            // exactly what happened with an empty/fresh games folder.
            LaunchedEffect(entries) { if (hasAnyGroupCard) firstFocus.requestFocus() }
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
                items(orderedGroups, key = { it.key }) { entryGroup ->
                    GroupCard(
                        label = entryGroup.label,
                        count = byGroup.getValue(entryGroup).size,
                        modifier = if (entryGroup == orderedGroups.firstOrNull()) {
                            Modifier.focusRequester(firstFocus)
                        } else {
                            Modifier
                        },
                        onSelect = { selectedGroup = entryGroup },
                    )
                }
                if (entries.isEmpty()) {
                    item(key = "empty") { Text("No games detected yet.", color = Color.White, modifier = Modifier.padding(horizontal = 48.dp)) }
                }
            }
        } else {
            val allGames = entries.filter { it.gameGroup() == group }
            val recentCount = allGames.count { it.lastPlayedEpochMs != null }
            val games = if (recentOnly) allGames.filter { it.lastPlayedEpochMs != null } else allGames
            // Same "don't request focus on an unattached FocusRequester" fix
            // as the system-list view above -- games can be empty here too
            // (the "recent" filter selected with zero recently-played entries).
            LaunchedEffect(group, recentOnly) { if (games.isNotEmpty()) firstFocus.requestFocus() }
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
private fun GroupCard(label: String, count: Int, modifier: Modifier = Modifier, onSelect: () -> Unit) {
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
        Text(label, color = Color.White, style = MaterialTheme.typography.titleLarge)
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
    // Same "don't request focus on an unattached FocusRequester" fix as
    // GamesSection -- firstFocus is only attached to a card once sections
    // is confirmed non-empty (see the early return right below).
    LaunchedEffect(entries) { if (sections.isNotEmpty()) firstFocus.requestFocus() }

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

/**
 * Opens droidtop's one real settings surface (:shell-default's
 * SettingsActivity, a Fragment/Preference-based screen) instead of building
 * a second, parallel settings UI here — see SettingsHandheldFragment for
 * the actual Handheld-specific preferences this section jumps straight to.
 * No compile-time dependency on :shell-default (see HandheldPrefs' own doc
 * comment for why), so this launches by component/fragment name instead of
 * a typed Intent.
 */
@Composable
private fun SettingsSection() {
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsLink(
            "Handheld settings",
            "Default section, button hints, console systems",
            modifier = Modifier.focusRequester(firstFocus),
            onClick = { openSettings(context, "app.murinelauncher.settings.SettingsHandheldFragment") },
        )
        SettingsLink(
            "All settings",
            "General, icons, home screen, and everything else",
            onClick = { openSettings(context, null) },
        )
    }
}

private fun openSettings(context: Context, fragment: String?) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(context.packageName, "com.android.launcher3.settings.SettingsActivity")
        if (fragment != null) putExtra(":settings:fragment", fragment)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

@Composable
private fun SettingsLink(title: String, summary: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
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
            .border(
                width = if (focused) 4.dp else 1.dp,
                color = if (focused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(12.dp),
            )
            .background(if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .padding(20.dp),
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(summary, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
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
    LibraryEntryKind.CONSOLE_ROM -> "Consoles"
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
    Box(
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
            ),
    ) {
        if (entry.artworkUri != null) {
            AsyncImage(
                model = entry.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Bottom scrim so the title stays legible over arbitrary artwork.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))),
                    )
                    .padding(12.dp),
            ) {
                Column {
                    Text(entry.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(entry.kind.name, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                Text(entry.title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(entry.kind.name, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
