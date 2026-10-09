package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.display.secondScreenScroll
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.settings.CompanionPrefs
import dev.droidtop.library.settings.CompanionSettings
import dev.droidtop.library.settings.ControlAccess
import dev.droidtop.library.settings.ControlPanel
import dev.droidtop.library.settings.ControlSurface
import dev.droidtop.library.settings.SocialBadge
import dev.droidtop.library.settings.UiMode
import dev.droidtop.library.settings.UiModeRefresh
import dev.droidtop.library.social.SocialHub
import dev.droidtop.runtime.keyboard.AddonKeyboard
import dev.droidtop.shell.gamepad.KeyboardTargets
import dev.droidtop.shell.gamepad.LABEL_LINE
import dev.droidtop.shell.gamepad.LABEL_SIZE
import dev.droidtop.shell.gamepad.QuickGlyph
import dev.droidtop.shell.gamepad.QuickGlyphIcon
import dev.droidtop.shell.gamepad.RailColors
import dev.droidtop.shell.gamepad.RailTab
import dev.droidtop.shell.gamepad.TabRail
import dev.droidtop.shell.gamepad.labelExtent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One companion tab (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414): Home, Game (while a game runs),
 * System, Apps, Performance, Input, Social, Plugins, or one plugin's panel. [id] is what the Companion group of
 * the catalog stores ([CompanionPrefs]); [panel] is the name [ControlAccess] decides on.
 */
internal data class CompanionTab(val id: String, val label: String, val glyph: QuickGlyph) {
    val panel: ControlPanel? get() = CompanionPrefs.panelOf(id)

    companion object {
        private fun of(panel: ControlPanel, label: String, glyph: QuickGlyph) = CompanionTab(CompanionPrefs.id(panel), label, glyph)

        val HOME = of(ControlPanel.HOME, "Home", QuickGlyph.HOME)
        val GAME = of(ControlPanel.GAME, "Game", QuickGlyph.GAMEPAD)
        val SYSTEM = of(ControlPanel.SYSTEM, "System", QuickGlyph.SETTINGS)
        val APPS = of(ControlPanel.APPS, "Apps", QuickGlyph.APPS)
        val PERFORMANCE = of(ControlPanel.PERFORMANCE, "Performance", QuickGlyph.GAUGE)
        val INPUT = of(ControlPanel.INPUT, "Input", QuickGlyph.KEYBOARD)
        val SOCIAL = of(ControlPanel.SOCIAL, "Social", QuickGlyph.PEOPLE)
        val PLUGINS = of(ControlPanel.PLUGINS, "Plugins", QuickGlyph.GENERIC)

        /** The tabs a person can choose, in the order More lists them. Game is not one: it comes while a game runs. */
        val CHOOSABLE = listOf(HOME, SYSTEM, APPS, PERFORMANCE, INPUT, SOCIAL)

        fun plugin(pluginId: String, label: String) = CompanionTab(CompanionPrefs.PLUGIN_PREFIX + pluginId, label, QuickGlyph.GENERIC)

        /** The More entry's own key and label (it is not a tab). */
        const val MORE_ID = "more"
        const val MORE_LABEL = "More"
    }
}

/** The bar: the tabs drawn on it, in order, and what More holds (no More when that is empty). */
internal data class CompanionBar(val tabs: List<CompanionTab>, val more: List<CompanionTab>) {
    val all: List<CompanionTab> get() = tabs + more
}

/** Runners that ask for Input while they run: a stream, and a PC game played with mouse and keyboard. */
private val INPUT_RUNNERS = setOf(LibraryEntryKind.REMOTE_STREAM, LibraryEntryKind.WINE_PROFILE, LibraryEntryKind.LINUX_CONTAINER_APP)

internal fun runnerWantsInput(kind: LibraryEntryKind?): Boolean = kind in INPUT_RUNNERS

/** The running tabs: Game while a game runs, and Input while a stream or PC game runs. */
internal fun runningTabs(gameRunning: Boolean, wantsInput: Boolean): List<CompanionTab> = buildList {
    if (gameRunning) add(CompanionTab.GAME)
    if (gameRunning && wantsInput) add(CompanionTab.INPUT)
}

/**
 * Which tabs go on the bar and which under More: the one place the overflow rule lives (docs/SPEC.md "The
 * companion's tabs"). Pure; the bar measures the labels and passes their sizes in.
 * - Up to four [chosen] tab ids, then the [running] tabs not already chosen, then More, always last.
 * - Only what [mode] allows ([ControlAccess]); where the mode hides the Companion group (Kid, Kiosk), the bar is
 *   the mode's whole tab set, since nobody there can choose.
 * - Chosen tabs never move for a running tab. Plugins sits under More unless chosen; one plugin's panel
 *   ([plugins]) can itself be a chosen tab.
 * - When the tabs and More need more than [extent] (in [labelSizes]' unit, More's own size [moreSize]), chosen
 *   tabs move into More from the right end, first in its list, and come back when there is room. An [extent] of
 *   zero or less means not measured yet: nothing moves.
 */
internal fun slots(
    chosen: List<String>,
    mode: UiMode,
    running: List<CompanionTab>,
    extent: Float,
    labelSizes: Map<String, Float>,
    moreSize: Float = 0f,
    plugins: List<CompanionTab> = emptyList(),
): CompanionBar {
    val allowed = ControlAccess.panels(mode, ControlSurface.COMPANION)
    val pluginTabs = if (ControlPanel.PLUGINS in allowed) plugins else emptyList()
    val offered = CompanionTab.CHOOSABLE.filter { it.panel in allowed } +
        (if (pluginTabs.isNotEmpty()) listOf(CompanionTab.PLUGINS) else emptyList())
    val canChoose = ControlAccess.shows(mode, "", ControlAccess.GROUP_COMPANION)
    val choices = offered + pluginTabs
    val bar = (if (canChoose) chosen.map(CompanionPrefs::currentId) else offered.map { it.id })
        .mapNotNull { id -> choices.firstOrNull { it.id == id } }
        .distinct()
        .take(if (canChoose) CompanionPrefs.MAX_CHOSEN else Int.MAX_VALUE)
        .toMutableList()
    val runningTabs = running.filter { it.panel in allowed && it !in bar }
    val rest = offered.filter { it !in bar && it !in runningTabs }
    val moved = mutableListOf<CompanionTab>()
    fun size(tab: CompanionTab) = labelSizes[tab.id] ?: 0f
    fun needed() = (bar + runningTabs).sumOf { size(it).toDouble() }.toFloat() +
        if (moved.isNotEmpty() || rest.isNotEmpty()) moreSize else 0f
    if (extent > 0f) {
        while (needed() > extent && bar.isNotEmpty()) moved.add(0, bar.removeAt(bar.lastIndex))
    }
    return CompanionBar(bar + runningTabs, moved + rest)
}

/** The count More shows: every badge of the tabs under it, so nothing waiting is hidden. */
internal fun moreBadge(bar: CompanionBar, badges: Map<String, Int>): Int = bar.more.sumOf { badges[it.id] ?: 0 }

/** The tab the companion opens on in [modeName]: the setting, else the mode's default; a tab the mode does not offer is Home. */
internal fun openingTab(settings: CompanionSettings, modeName: String, bar: CompanionBar): String {
    val wanted = when (val value = settings.opening(modeName)) {
        CompanionPrefs.OPEN_DEFAULT -> CompanionPrefs.defaultOpening(modeName)
        CompanionPrefs.OPEN_LAST -> settings.last[modeName] ?: CompanionPrefs.defaultOpening(modeName)
        else -> value
    }
    return if (bar.all.any { it.id == wanted }) wanted else CompanionTab.HOME.id
}

/**
 * The tab to turn to when a game starts while Home shows ("When a game starts"): the runner's tab by default
 * (Input for a runner that asks for it, else Game), nothing, or a named tab. Null: stay.
 */
internal fun tabForGameStart(setting: String, running: List<CompanionTab>, bar: CompanionBar): String? {
    val id = when (setting) {
        CompanionPrefs.GAME_START_NONE -> return null
        CompanionPrefs.GAME_START_RUNNER -> if (CompanionTab.INPUT in running) CompanionTab.INPUT.id else CompanionTab.GAME.id
        else -> setting
    }
    return id.takeIf { wanted -> bar.all.any { it.id == wanted } }
}

/**
 * The one entry every companion host draws: the selected tab over a bar (a screen taller than wide) or beside a
 * rail down the left (wider than tall), decided from the window's own bounds. [home] is that host's own Home
 * content (the activity's carries the widget add/remove controls, the registry's does not). Only the selected
 * tab is composed, so a tab that is not showing runs nothing and polls nothing. Touch only: nothing here is a
 * focus target and the pad never drives it.
 */
@Composable
internal fun CompanionTabs(mode: SecondaryDisplayContent.Mode, home: @Composable () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val modeName = mode.name
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            CompanionPrefs.load(context.applicationContext)
            UiModeRefresh.load(context.applicationContext)
        }
    }
    val settings by CompanionPrefs.settings.collectAsState()
    val uiMode by UiModeRefresh.mode.collectAsState()
    val running by LaunchDisplay.running.collectAsState()
    val entries by CompanionState.libraryEntries.collectAsState()
    val runningId = running?.context?.gameId
    val runningKind = remember(runningId, entries) { runningId?.let { id -> entries.firstOrNull { it.id == id }?.kind } }
    val runningTabs = runningTabs(runningId != null, runnerWantsInput(runningKind))

    // The Social tab's unread count over every provider, read when something changes, never polled.
    val social = ControlPanel.SOCIAL in ControlAccess.panels(uiMode, ControlSurface.COMPANION)
    val unread by produceState(initialValue = SocialBadge.unread, social) {
        if (social) SocialHub.changes().collect { value = SocialHub.unread() }
    }
    val badges = mapOf(CompanionTab.SOCIAL.id to if (social) unread else 0)

    var selectedId by remember(mode) { mutableStateOf<String?>(null) }
    var moreOpen by remember { mutableStateOf(false) }
    // The tab "When a game starts" turned to, so quitting the game goes back to Home only from there.
    var autoTab by remember { mutableStateOf<String?>(null) }
    // A conversation Home's Social section opened; the Social tab starts there.
    var socialStart by remember { mutableStateOf<OpenConversation?>(null) }
    // The tab to go back to when the input controller was opened by the Keys button.
    var before by remember { mutableStateOf<String?>(null) }

    val keysButton by AddonKeyboard.companionKeysButton.collectAsState()
    // While started, this companion is where droidtop's keyboard for a field on the other screen opens (SPEC 4c).
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, view) {
        val token = Any()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> KeyboardTargets.companionShown(token) { view.display?.displayId }
                Lifecycle.Event.ON_STOP -> KeyboardTargets.companionShown(token, null)
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            KeyboardTargets.companionShown(token, null)
        }
    }
    // A field on the other screen wants keys: the input controller is what shows while the request stands, and
    // the selected tab is never touched, so Hide, Back and a field losing focus all land on the tab the person was
    // on however the request ended (Droidtop/tracker#369). A tab pressed meanwhile ends the request first.
    val requested by KeyboardTargets.companion.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val portrait = maxHeight >= maxWidth
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val labelStyle = TextStyle(fontSize = LABEL_SIZE, lineHeight = LABEL_LINE)
        // Each entry's length along the bar or rail at the current font: a label wraps to two lines at most, so
        // across a bar it needs its longest word, and down a rail its height at the rail's width.
        fun extentOf(label: String): Float = with(density) {
            if (portrait) {
                val word = label.split(' ').maxOf { measurer.measure(it, labelStyle).size.width }.toDp()
                (maxOf(labelExtent(word), 48.dp) + 4.dp).toPx()
            } else {
                val inner = (RAIL_WIDTH - 16.dp).roundToPx().coerceAtLeast(1)
                val text = measurer.measure(label, labelStyle, maxLines = 2, constraints = Constraints(maxWidth = inner))
                    .size.height.toDp()
                (maxOf(22.dp + 2.dp + text + 12.dp, 48.dp) + 4.dp).toPx()
            }
        }
        val labels = remember(portrait, density) {
            (CompanionTab.CHOOSABLE + CompanionTab.GAME + CompanionTab.PLUGINS).associate { it.id to extentOf(it.label) }
        }
        val moreSize = remember(portrait, density) { extentOf(CompanionTab.MORE_LABEL) }
        val extent = with(density) { (if (portrait) maxWidth - 8.dp else maxHeight - 8.dp).toPx() }
        val bar = slots(settings.chosen(modeName), uiMode, runningTabs, extent, labels, moreSize)

        val selected = (selectedId ?: openingTab(settings, modeName, bar)).let { id ->
            if (bar.all.any { it.id == id }) id else CompanionTab.HOME.id
        }
        fun select(id: String) {
            if (requested != null) KeyboardTargets.hideCompanion()
            socialStart = null
            moreOpen = false
            autoTab = null
            selectedId = id
        }
        val nav = CompanionNav(
            openTab = { tab -> select(tab.id) },
            openConversation = { conversation -> select(CompanionTab.SOCIAL.id); socialStart = conversation },
        )
        // Remembered for "The last one used", off the main thread.
        LaunchedEffect(selected) {
            withContext(Dispatchers.IO) { CompanionPrefs.setLast(context.applicationContext, modeName, selected) }
        }
        // A game started while Home showed: turn to the runner's tab and say so; quitting goes back to Home.
        var lastRunning by remember { mutableStateOf(runningId) }
        LaunchedEffect(runningId) {
            val was = lastRunning
            lastRunning = runningId
            if (was == null && runningId != null && selected == CompanionTab.HOME.id) {
                tabForGameStart(settings.onGameStart, runningTabs, bar)?.let { id ->
                    selectedId = id
                    autoTab = id
                    bar.all.firstOrNull { it.id == id }?.let { view.announceForAccessibility("${it.label} tab") }
                }
            } else if (was != null && runningId == null) {
                if (selected == autoTab || selected == CompanionTab.GAME.id) {
                    selectedId = CompanionTab.HOME.id
                    view.announceForAccessibility("${CompanionTab.HOME.label} tab")
                }
                autoTab = null
            }
        }

        val shown = if (requested != null) CompanionTab.INPUT.id else selected
        val colors = MaterialTheme.colorScheme
        val railColors = RailColors(
            selected = colors.surfaceVariant,
            ink = colors.onSurface,
            muted = colors.onSurfaceVariant,
            accent = colors.primary,
            onAccent = colors.onPrimary,
        )
        val inMore = bar.more.any { it.id == shown }
        val railTabs = bar.tabs.map { RailTab(it.id, it.label, it.glyph, badge = badges[it.id] ?: 0) } +
            if (bar.more.isEmpty()) {
                emptyList()
            } else {
                listOf(RailTab(CompanionTab.MORE_ID, CompanionTab.MORE_LABEL, QuickGlyph.MORE, badge = moreBadge(bar, badges)))
            }
        val railSelected = if (moreOpen || inMore) CompanionTab.MORE_ID else shown
        val onRail: (RailTab) -> Unit = { tab ->
            if (tab.key == CompanionTab.MORE_ID) moreOpen = !moreOpen else select(tab.key as String)
        }

        val content: @Composable ColumnScope.() -> Unit = {
            // The keyboard for a field on the other screen can be hidden from here; the Keys shortcut opens the
            // input controller where Input is not on the bar (tracker#314).
            if (requested != null || (keysButton && bar.tabs.none { it.id == CompanionTab.INPUT.id })) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    if (requested != null) {
                        CompanionPill("Hide keyboard", selected = true) { KeyboardTargets.hideCompanion() }
                    } else {
                        CompanionPill("Keys", selected = selected == CompanionTab.INPUT.id) {
                            if (selected == CompanionTab.INPUT.id) {
                                select(before ?: CompanionTab.HOME.id)
                                before = null
                            } else {
                                val from = selected
                                select(CompanionTab.INPUT.id)
                                before = from
                            }
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (moreOpen) {
                    CompanionMoreList(bar.more, shown, badges) { select(it.id) }
                } else {
                    when (shown) {
                        CompanionTab.HOME.id -> CompositionLocalProvider(LocalCompanionNav provides nav) { home() }
                        CompanionTab.GAME.id -> CompositionLocalProvider(LocalCompanionNav provides nav) { CompanionGameTab() }
                        CompanionTab.SOCIAL.id -> CompanionSocialTab(socialStart)
                        CompanionTab.APPS.id -> CompanionTasksTab()
                        CompanionTab.PERFORMANCE.id -> CompanionPerformanceTab()
                        CompanionTab.SYSTEM.id -> CompanionSystemTab()
                        CompanionTab.INPUT.id -> SecondScreenInputSurface(mode)
                        else -> Unit
                    }
                }
            }
            if (!settings.barTipSeen) {
                CompanionTip(
                    if (portrait) {
                        "The bar below switches what this screen shows. Game appears on it while a game runs."
                    } else {
                        "The rail on the left switches what this screen shows. Game appears on it while a game runs."
                    },
                ) { CompanionPrefs.setBarTipSeen(context.applicationContext) }
            }
        }

        if (portrait) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                TabRail(railTabs, railSelected, vertical = false, labelled = true, colors = railColors, onSelect = onRail)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                TabRail(
                    railTabs,
                    railSelected,
                    vertical = true,
                    labelled = true,
                    colors = railColors,
                    onSelect = onRail,
                    modifier = Modifier.width(RAIL_WIDTH),
                )
                Column(Modifier.weight(1f).fillMaxHeight(), content = content)
            }
        }
    }
}

/** The landscape rail's width: room for a two-line label beside the tab content. */
private val RAIL_WIDTH = 88.dp

/** More: the tabs that are not on the bar, as large rows with their counts. A tap opens one. */
@Composable
private fun CompanionMoreList(tabs: List<CompanionTab>, shown: String, badges: Map<String, Int>, onOpen: (CompanionTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().secondScreenScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            CompanionTab.MORE_LABEL,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground,
            modifier = Modifier.semantics { heading() },
        )
        tabs.forEach { tab ->
            val badge = badges[tab.id] ?: 0
            CompanionTile(
                onClick = { onOpen(tab) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = if (badge > 0) "${tab.label}, $badge new" else tab.label
                        role = Role.Tab
                        onClick { onOpen(tab); true }
                    },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .background(if (tab.id == shown) colors.surfaceVariant else colors.surface)
                        .padding(horizontal = 16.dp),
                ) {
                    QuickGlyphIcon(tab.glyph, tint = colors.onSurface, modifier = Modifier.size(24.dp))
                    Text(tab.label, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.weight(1f))
                    if (badge > 0) Text("$badge new", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            }
        }
    }
}

/** A first-run tip: what something is, until the person dismisses it. Read by TalkBack like any text. */
@Composable
internal fun CompanionTip(text: String, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.primaryContainer)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer, modifier = Modifier.weight(1f))
        CompanionPill("Got it", onClick = onDismiss)
    }
}

/** The task manager's own row (reused from the first slice, not rebuilt) in a scrolling page, with a line for nothing running. */
@Composable
private fun CompanionTasksTab() {
    Column(modifier = Modifier.fillMaxSize().secondScreenScroll(rememberScrollState()).padding(16.dp)) {
        CompanionTasks()
    }
}

/**
 * The tabs' shared building blocks, so Performance and System look and lay out as one family: a pill (the
 * timeout choices, the small actions), a titled card, a muted note line, and a page that is one column in
 * portrait and two on a wide screen, scrolling either way.
 */
@Composable
internal fun CompanionPill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompanionTile(onClick = onClick, shape = RoundedCornerShape(50)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .background(if (selected) colors.primary else colors.surfaceVariant)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimary else colors.onSurface)
        }
    }
}

@Composable
internal fun CompanionNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun CompanionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
internal fun CompanionPanels(panels: List<@Composable () -> Unit>) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 720.dp) 2 else 1
        Row(
            modifier = Modifier.fillMaxWidth().secondScreenScroll(rememberScrollState()).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(columns) { column ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    panels.forEachIndexed { index, panel -> if (index % columns == column) panel() }
                }
            }
        }
    }
}
