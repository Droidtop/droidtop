package dev.droidtop.shell.gamepad

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.settings.HandheldSettingsCatalog
import dev.droidtop.library.EngineGameProvider
import dev.droidtop.library.GameLaunchStrategy
import dev.droidtop.library.LaunchStrategyOverridePrefs
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.PlatformsDatabase
import dev.droidtop.library.displayName
import dev.droidtop.library.displayName as launchStrategyDisplayName
import dev.droidtop.library.theme.SystemThemeColors
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.primaryListElement
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.EsDeListItem
import dev.droidtop.shell.gamepad.theme.EsDeSystemListView
import dev.droidtop.shell.gamepad.theme.EsDeThemedView
import dev.droidtop.shell.gamepad.theme.ThemePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
fun GamepadShell(
    library: Library,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit = {},
    // Real deep-link params from :app's MainActivity, which itself reads
    // them from an Intent extra sent by :shell-default's
    // SettingsHandheldFragment -- a different module with no compile
    // dependency on this one, hence a plain String name here rather than
    // HandheldSection itself (internal, module-private). Lets the real,
    // unified Preference-based settings screen reach this Compose-only
    // shell's own actions (jump to a section, trigger a rescan) it has no
    // other way to invoke. deepLinkToken is real, not decorative: this
    // Activity is singleTask, so a repeat deep-link (e.g. "Rescan library"
    // pressed twice) almost always arrives via onNewIntent while this
    // composition is already running -- startSectionName/triggerRescan
    // alone wouldn't change value the second time, so nothing would
    // recompose/react without a token that bumps on every real deep-link
    // regardless of whether the values themselves repeat.
    deepLinkToken: Int = 0,
    startSectionName: String? = null,
    triggerRescan: Boolean = false,
    triggerBrowseThemes: Boolean = false,
) {
    val context = LocalContext.current
    // Two independent states, not one -- see Library.scanKinds' own doc
    // comment: a single combined scan meant Apps stayed empty until the
    // (real, SD-card-scale) Games/ROM scan also finished, even though
    // NativeAppProvider itself completes almost instantly on its own.
    // Each LaunchedEffect below runs as its own coroutine, so one
    // section's slow provider can never gate the other section's ready
    // results from ever rendering.
    var gameEntries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var appEntries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    // Bumped by the real, user-facing "Rescan library" Settings action --
    // included in both LaunchedEffect keys below so bumping it restarts
    // both collections against Library.rescanKindsProgressive instead of
    // the plain (cache-trusting) scanKindsProgressive. Previously the
    // only way to force a fresh scan was clearing app data by hand over
    // adb; a real user has no such option.
    var rescanTrigger by remember { mutableStateOf(0) }
    var section by remember { mutableStateOf(HandheldPrefs.defaultSection(context)) }
    // Bumps whenever a real "Browse themes" deep-link arrives (see
    // deepLinkToken's own doc comment) -- SettingsCatalogView opens its
    // inline ThemeBrowserScreen off this token.
    var browseThemesRequest by remember { mutableStateOf(0) }
    // Reacts to every real deep-link (see deepLinkToken's own doc comment
    // above), not just the first composition -- a plain `remember` initial
    // value would only ever apply once per GamepadShell instance, silently
    // doing nothing for every deep-link after the first while this
    // Activity's singleTask instance stays alive.
    LaunchedEffect(deepLinkToken) {
        if (triggerRescan) rescanTrigger++
        if (triggerBrowseThemes) {
            section = HandheldSection.SETTINGS
            browseThemesRequest++
        }
        startSectionName?.let { name ->
            HandheldSection.entries.firstOrNull { it.name == name }?.let { section = it }
        }
    }
    // Settings is a real in-shell section again -- cycling to it with L/R
    // or picking its tab NEVER leaves the handheld context (per direction:
    // browsing sections must maintain context). It renders the SAME shared
    // settings catalog the unified Preference surface renders
    // (HandheldSettingsCatalog, :runtime-common -- see docs/SPEC.md's
    // settings architecture), so nothing about the settings themselves is
    // shell-specific; only the chrome is. Explicitly activating a
    // navigation item inside it (Global settings, another shell's
    // settings, Console systems) still opens those real surfaces -- that's
    // a deliberate user choice, exactly the distinction this draws.
    val selectSection: (HandheldSection) -> Unit = { target -> section = target }
    var canGoBack by remember { mutableStateOf(false) }
    // True only while GamesSection's own themed system-view render (the
    // real, loaded theme's <helpsystem> element, see EsDeThemedHelpSystem)
    // is actually drawing button hints itself -- lets the hardcoded
    // ButtonHintFooter below step aside instead of drawing a second,
    // redundant, differently-styled hint bar on top of the theme's own.
    var themeHandlesHints by remember { mutableStateOf(false) }
    var detailEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    val scope = rememberCoroutineScope()
    // Real user-visible launch-failure state -- see launchError's render
    // site. A failed launch must inform, never kill.
    var launchError by remember { mutableStateOf<String?>(null) }
    // Never-crash boundary: a launch failure (bad emulator preset, missing
    // app, malformed template) must NEVER kill the shell -- confirmed
    // live: the first real on-device game launch threw from a preset's
    // bad boolean extra and took the whole app down to its crash-recovery
    // screen. The error surfaces to the user instead.
    // Per-launch display chooser (docs/SPEC.md §4, "ask every time"
    // default): LaunchDisplay.start defers to this whenever askOptions has
    // more than one candidate; state renders LaunchDisplayChooserDialog
    // below. Installed only while this composition is live.
    // Quick Menu (hold SELECT) -- see QuickMenu.kt for the paradigm.
    var quickMenuOpen by remember { mutableStateOf(false) }
    // Swallows the key-UP of the hold that opened the menu, so the
    // shell's ordinary short-press Select action doesn't ALSO fire.
    var swallowSelectUp by remember { mutableStateOf(false) }
    // Counts SELECT KeyDowns between KeyUps: the system's own key-repeat
    // redelivers KeyDown while held, so a second KeyDown IS the ~500ms
    // hold threshold -- portable across Compose flavors, no
    // nativeKeyEvent access (which the JetBrains artifacts droidtop
    // builds against do not expose; a real CI failure, not a guess).
    var selectDownCount by remember { mutableStateOf(0) }
    var displayChoice by remember {
        mutableStateOf<Pair<List<dev.droidtop.library.LaunchDisplayOption>, (Int?) -> Unit>?>(null)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        dev.droidtop.library.LaunchDisplay.chooser = { options, onChosen ->
            displayChoice = options to onChosen
        }
        onDispose { dev.droidtop.library.LaunchDisplay.chooser = null }
    }
    displayChoice?.let { (options, onChosen) ->
        LaunchDisplayChooserDialog(
            options = options,
            onPick = { displayId ->
                displayChoice = null
                onChosen(displayId)
            },
            onCancel = { displayChoice = null },
        )
    }

    if (quickMenuOpen) {
        QuickMenu(onDismiss = { quickMenuOpen = false })
    }

    val onLaunch: (LibraryEntry) -> Unit = { entry ->
        scope.launch {
            launchError = null
            runCatching { library.launch(entry) }
                .onFailure {
                    android.util.Log.e("droidtop.GamepadShell", "Launching ${entry.title} failed", it)
                    launchError = "Couldn't launch ${entry.title}: ${it.message}"
                }
        }
    }
    val tabBarFocus = remember { FocusRequester() }

    // The extra .filter is real, not redundant: Library.scanKinds(Progressive)
    // matches at the provider level (does this provider produce any
    // requested kind), not per-entry -- a provider spanning kinds on both
    // sides of the Games/Apps split (EngineGameProvider currently covers
    // RENPY/RPG_MAKER_*/KIRIKIRI, and GAME_KINDS is a subset missing
    // KIRIKIRI) could otherwise leak an entry into the wrong section.
    //
    // scanKindsProgressive, not scanKinds: real, reported UX request --
    // this screen used to show nothing but its spinner until the entire
    // scan (every root, every system folder) finished, even though most
    // folders individually are fast. Each collected emission is already a
    // growing snapshot (see Library.scanKindsProgressive's own doc
    // comment), so just assigning it directly here is enough to make the
    // screen fill in gradually as real results arrive, without this file
    // needing to know anything about how the underlying scan is chunked.
    LaunchedEffect(library, rescanTrigger) {
        val flow = if (rescanTrigger == 0) library.scanKindsProgressive(GAME_KINDS) else library.rescanKindsProgressive(GAME_KINDS)
        flow.collect { gameEntries = it.filter { entry -> entry.kind in GAME_KINDS } }
    }
    LaunchedEffect(library, rescanTrigger) {
        val flow = if (rescanTrigger == 0) library.scanKindsProgressive(APP_KINDS) else library.rescanKindsProgressive(APP_KINDS)
        flow.collect { appEntries = it.filter { entry -> entry.kind in APP_KINDS } }
    }
    // Real bug this fixes, reported directly: nulling gameEntries/appEntries
    // here made the entire already-known library disappear the instant
    // "Rescan library" was pressed, showing the loading spinner for
    // however long the fresh walk took -- a rescan should never make a
    // user's existing library vanish. Bumping rescanTrigger alone is
    // enough: it restarts the LaunchedEffects above against
    // rescanKindsProgressive, whose own first emission is the real,
    // already-known cached data (see ConsoleRomProvider.rescanProgressive's
    // own doc comment), not an empty list. "Rescan library" itself now
    // lives in SettingsHandheldFragment (see MainActivity's own
    // EXTRA_HANDHELD_RESCAN, read once above into rescanTrigger's initial
    // value) -- nothing left in this composition needs to bump it again.
    // Real, immediate in-memory update -- Library.toggleFavorite already
    // persists the real new state (see its own doc comment); updating the
    // held gameEntries copy directly here means the badge/UI reflects it
    // right away, without waiting on a full rescan round-trip. null means
    // this entry's kind has no real favorite concept (see
    // Library.toggleFavorite's own doc comment) -- a no-op, not an error.
    val onToggleFavorite: (LibraryEntry) -> Unit = { entry ->
        scope.launch {
            val newFavorite = library.toggleFavorite(entry) ?: return@launch
            gameEntries = gameEntries?.map { if (it.id == entry.id) it.copy(favorite = newFavorite) else it }
        }
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

    // Outermost back swallow: droidtop is the HOME surface -- system back
    // (which this hardware's B button doubles as) at the shell's top level
    // must never finish the Activity and drop the user into whatever app
    // happened to be behind the launcher (confirmed live, per report).
    // Composed FIRST, so every deeper BackHandler (detail close, drill-up)
    // registers later on the dispatcher and takes precedence while active.
    androidx.activity.compose.BackHandler(enabled = true) {
        // Deliberate no-op: top-of-shell back goes nowhere, same as any
        // Android home screen.
    }
    // Real dispatcher-route for closing the detail screen with B/back --
    // same reason as the drill-up BackHandler in GamesSection.
    androidx.activity.compose.BackHandler(enabled = detailEntry != null) { detailEntry = null }
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
                // Quick Menu trigger: HOLD Select. repeatCount >= 1 is
                // the system's own key-repeat threshold (~500ms) -- a
                // timing-free long-press, and short-press Select keeps
                // its existing meaning because only the repeat opens it.
                if (GamepadKeyMap.actionFor(event.key) == GamepadAction.SELECT) {
                    if (event.type == KeyEventType.KeyDown) {
                        selectDownCount += 1
                        if (selectDownCount >= 2) {
                            // Second KeyDown = the system's key-repeat
                            // fired = a real hold.
                            if (!quickMenuOpen) {
                                quickMenuOpen = true
                                swallowSelectUp = true
                            }
                            return@onKeyEvent true
                        }
                    }
                    if (event.type == KeyEventType.KeyUp) {
                        selectDownCount = 0
                        if (swallowSelectUp) {
                            swallowSelectUp = false
                            return@onKeyEvent true
                        }
                    }
                }
                if (event.type != KeyEventType.KeyUp || detailEntry != null) return@onKeyEvent false
                val sections = HandheldSection.entries
                val currentIndex = sections.indexOf(section)
                when (GamepadKeyMap.actionFor(event.key)) {
                    GamepadAction.L -> {
                        selectSection(sections[(currentIndex - 1 + sections.size) % sections.size])
                        true
                    }
                    GamepadAction.R -> {
                        selectSection(sections[(currentIndex + 1) % sections.size])
                        true
                    }
                    else -> false
                }
            },
    ) {
        SectionTabBar(current = section, onSelect = selectSection, currentTabFocus = tabBarFocus)
        // Launch-failure banner (see onLaunch's crash boundary): visible,
        // dismisses itself after a few seconds, never blocks input.
        launchError?.let { message ->
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(6000)
                if (launchError == message) launchError = null
            }
            Text(
                message,
                color = Color(0xFFFFB4AB),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC330E0B))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
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
                    library = library,
                    onLaunch = { onLaunch(entry); detailEntry = null },
                    onClose = { detailEntry = null },
                )
                section == HandheldSection.SETTINGS -> {
                    canGoBack = false
                    themeHandlesHints = false
                    SettingsCatalogView(
                        onBack = { section = HandheldPrefs.defaultSection(context) },
                        onRescan = { rescanTrigger++ },
                        browseThemesToken = browseThemesRequest,
                    )
                }
                // Each section now gates on its own scan only (see
                // gameEntries/appEntries' own comment) -- Games' spinner no
                // longer has anything to do with whether Apps is ready, and
                // vice versa.
                section == HandheldSection.GAMES && gameEntries == null -> CircularProgressIndicator(color = Color.White)
                section == HandheldSection.APPS && appEntries == null -> CircularProgressIndicator(color = Color.White)
                else -> when (section) {
                    HandheldSection.GAMES -> GamesSection(
                        entries = gameEntries.orEmpty(),
                        library = library,
                        onLaunch = onLaunch,
                        onShowDetail = { detailEntry = it },
                        onDrillDownChanged = { canGoBack = it },
                        onFocusedEntryChanged = onFocusedEntryChanged,
                        onThemeHandlesHints = { themeHandlesHints = it },
                        onToggleFavorite = onToggleFavorite,
                    )
                    HandheldSection.APPS -> {
                        canGoBack = false
                        themeHandlesHints = false
                        AppsSection(
                            entries = appEntries.orEmpty(),
                            onLaunch = onLaunch,
                            onShowDetail = { detailEntry = it },
                            onFocusedEntryChanged = onFocusedEntryChanged,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                    // SETTINGS is handled above, before the loading gate --
                    // unreachable here, kept only so `when` stays exhaustive.
                    HandheldSection.SETTINGS -> Unit
                }
            }
        }
        if (HandheldPrefs.showHints(context) && !themeHandlesHints) {
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
    private const val KEY_APPS_GRID_COLUMNS = "pref_handheld_apps_grid_columns"
    private const val DEFAULT_APPS_GRID_COLUMNS = 5
    // One shared range definition -- the settings catalog
    // (:runtime-common) is the single owner of this setting now, so the
    // formerly hand-synced copy of the XML seekbar's range is gone.
    const val MIN_APPS_GRID_COLUMNS = HandheldSettingsCatalog.MIN_APPS_GRID_COLUMNS
    const val MAX_APPS_GRID_COLUMNS = HandheldSettingsCatalog.MAX_APPS_GRID_COLUMNS

    fun defaultSection(context: Context): HandheldSection {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_DEFAULT_SECTION, "games")) {
            "apps" -> HandheldSection.APPS
            else -> HandheldSection.GAMES
        }
    }

    fun showHints(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_HINTS, true)

    fun setDefaultSection(context: Context, section: HandheldSection) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEFAULT_SECTION, if (section == HandheldSection.APPS) "apps" else "games")
            .apply()
    }

    fun setShowHints(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_HINTS, show).apply()
    }

    fun setAppsGridColumns(context: Context, columns: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_APPS_GRID_COLUMNS, columns).apply()
    }

    // Deliberately separate from :shell-default's own drawer grid-width
    // override (SettingsDrawerFragment's GRID_SIZE_WIDTH_DRAWER_OVERRIDE) --
    // that one sizes Standard's app drawer, an entirely different view with
    // its own icon size/screen-real-estate needs. Same SharedPreferences
    // file as every other Handheld pref here, set via SettingsHandheldFragment
    // (:shell-default) through the shared CustomSeekBarPreference widget,
    // which self-persists as an int.
    fun appsGridColumns(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_APPS_GRID_COLUMNS, DEFAULT_APPS_GRID_COLUMNS)
}

/**
 * Full-screen detail view for one entry — one horizontal row of primary
 * actions (Launch, leading/highlighted) instead of launch being a card's
 * only behavior. Reached via a card's Y/Info action, closed via B/Back.
 */
@Composable
private fun EntryDetailScreen(entry: LibraryEntry, library: Library, onLaunch: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val launchFocus = remember { FocusRequester() }
    LaunchedEffect(entry) { launchFocus.requestFocus() }

    var editingMetadata by remember { mutableStateOf(false) }
    var editingCollections by remember { mutableStateOf(false) }

    // Which launch backends this particular game can actually use right
    // now -- enginehost's native runtime, Wine, a Linux container. Loaded
    // off the main thread because resolving them re-reads the game folder
    // (see Library.availableLaunchStrategies). Empty for anything that
    // isn't an engine game, which is what hides the chip below.
    var strategies by remember(entry) { mutableStateOf<List<GameLaunchStrategy>>(emptyList()) }
    var chosenStrategy by remember(entry) { mutableStateOf(LaunchStrategyOverridePrefs.get(context, entry.id)) }
    LaunchedEffect(entry) { strategies = library.availableLaunchStrategies(entry) }
    val isRomEntry = entry.kind == LibraryEntryKind.CONSOLE_ROM

    if (editingMetadata) {
        GameMetadataEditor(
            entry = entry,
            library = library,
            onDismiss = { editingMetadata = false },
        )
        return
    }
    if (editingCollections) {
        CollectionMembershipEditor(
            entry = entry,
            library = library,
            onDismiss = { editingCollections = false },
        )
        return
    }

    // Real choice, not a silent default -- enginehost/Kirikiroid2/Wine/a
    // Linux container are all genuinely available strategies for an
    // engine-detected game depending on what's installed and what the
    // folder actually ships (see GameLaunchStrategyResolver); this picker
    // is what makes that a real, user-visible option instead of something
    // only settable by hand-editing LaunchStrategyOverridePrefs. Bumped to
    // force re-reading the override after a pick.
    var overrideVersion by remember { mutableStateOf(0) }
    var pickingStrategy by remember { mutableStateOf(false) }
    val engineProvider = remember(context) { EngineGameProvider(context) }
    val isEngineGame = remember(entry.kind) { entry.kind in engineProvider.kinds }
    val availableStrategies = remember(entry, isEngineGame) {
        if (isEngineGame) engineProvider.availableStrategies(entry) else emptyList()
    }
    val currentStrategy = remember(entry, availableStrategies, overrideVersion) {
        val overrideName = LaunchStrategyOverridePrefs.get(context, entry.id)
        availableStrategies.firstOrNull { it.name == overrideName } ?: availableStrategies.firstOrNull()
    }

    if (pickingStrategy) {
        LaunchStrategyPicker(
            strategies = availableStrategies,
            current = currentStrategy,
            onPick = { strategy ->
                LaunchStrategyOverridePrefs.set(context, entry.id, strategy)
                overrideVersion++
                pickingStrategy = false
            },
            onDismiss = { pickingStrategy = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp)
            .onKeyEvent { event ->
                val action = GamepadKeyMap.actionFor(event.key)
                if (event.type == KeyEventType.KeyUp && (action == GamepadAction.BACK || action == GamepadAction.B)) {
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
            // Only shown when there is a real choice to make. Cycles
            // rather than opening a picker: there are at most a handful of
            // backends, and cycling matches how every other choice in this
            // shell is adjusted (see SettingsCatalogView's left/right).
            if (strategies.size > 1) {
                val active = strategies.firstOrNull { it.name == chosenStrategy } ?: strategies.first()
                ActionChip(
                    "Runs with: ${active.displayName()}",
                    highlighted = false,
                    onClick = {
                        val next = strategies[(strategies.indexOf(active) + 1) % strategies.size]
                        LaunchStrategyOverridePrefs.set(context, entry.id, next)
                        chosenStrategy = next.name
                    },
                )
            }
            // Real ConsoleRomProvider-specific concept -- same honest
            // "not applicable" gating Library.toggleFavorite/
            // saveMetadata already use for a non-ROM entry.
            if (isRomEntry) {
                ActionChip("Edit metadata", highlighted = false, onClick = { editingMetadata = true })
                ActionChip("Collections", highlighted = false, onClick = { editingCollections = true })
            }
            ActionChip("Back", highlighted = false, onClick = onClose)
        }
        // Only shown when there's an actual choice to make -- a single
        // available strategy (or none) has nothing for a picker to offer.
        if (isEngineGame && availableStrategies.size > 1 && currentStrategy != null) {
            Text("Launch via", color = Color.Gray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            ActionChip(currentStrategy.launchStrategyDisplayName(), highlighted = false, onClick = { pickingStrategy = true })
        }
    }
}

/** Real per-entry choice among [GameLaunchStrategy]s -- same shape as ConsoleSystemsActivity's PlayerPicker for ROMs, just local to shell-gamepad since that one lives in :app. */
@Composable
private fun LaunchStrategyPicker(
    strategies: List<GameLaunchStrategy>,
    current: GameLaunchStrategy?,
    onPick: (GameLaunchStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp)
            .onKeyEvent { event ->
                val action = GamepadKeyMap.actionFor(event.key)
                if (event.type == KeyEventType.KeyUp && (action == GamepadAction.BACK || action == GamepadAction.B)) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Launch via", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        strategies.forEach { strategy ->
            var focused by remember(strategy) { mutableStateOf(false) }
            Text(
                strategy.launchStrategyDisplayName() + if (strategy == current) " (current)" else "",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clickable { onPick(strategy) }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp &&
                            GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                        ) {
                            onPick(strategy)
                            true
                        } else {
                            false
                        }
                    }
                    .background(if (focused) Color(0xFF2A2A2A) else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        }
    }
}

@Composable
internal fun ActionChip(label: String, highlighted: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (highlighted) Color.Black else Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Same real touch-input fix as GameCard -- see its own comment.
            .clickable(onClick = onClick)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    GamepadKeyMap.actionFor(event.key) == GamepadAction.A
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
                    // Same real touch-input fix as GameCard -- see its own
                    // comment. This is the top-level Games/Apps/Settings
                    // tab bar, the very first thing a user taps.
                    .clickable(onClick = { onSelect(entrySection) })
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp &&
                            GamepadKeyMap.actionFor(event.key) == GamepadAction.A
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
/**
 * Requests focus once the [androidx.compose.ui.focus.FocusRequester]'s
 * node actually exists. A themed view composes its list widget through
 * several nested layout passes, so the focusable node attaches a LATER
 * frame than a LaunchedEffect's first dispatch -- a single requestFocus
 * reliably threw "FocusRequester is not initialized" (caught, so no
 * crash, but the screen then had NO focus at all and every D-pad key
 * went nowhere -- confirmed live on-device via logcat). Retries across
 * frames until the node exists; gives up quietly after ~1s of frames
 * rather than looping forever on a theme whose view genuinely never
 * attaches one (ES-DWEE's widgetless system view is the real case).
 */
internal suspend fun requestFocusWhenAttached(
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    tag: String,
) {
    var attached = false
    repeat(60) {
        if (!attached) {
            attached = runCatching { focusRequester.requestFocus() }.isSuccess
            if (!attached) withFrameNanos {}
        }
    }
    if (!attached) {
        android.util.Log.w("droidtop.GamepadShell", "$tag focus never attached after 60 frames")
    }
}

private sealed interface GameGroup {
    val key: String
    val label: String

    /**
     * The ES-DE `${system.theme}` folder this group themes as -- real
     * ES-DE's own es_systems.xml `<theme>` mechanism (`SystemData::
     * mThemeFolder`), where a system's theme folder is a separate,
     * declared value rather than always its id. Droidtop's engine
     * buckets (Ren'Py, RPG Maker, ...) and Linux-container games have
     * no ES-DE platform identity of their own, so they all theme as
     * "pc" -- the one metacategory every real theme already ships art
     * for -- instead of each needing per-engine theme patches (WINE
     * profiles already carry systemId = "pc" directly, same category).
     */
    val systemThemeFolder: String?

    data class Engine(val kind: LibraryEntryKind) : GameGroup {
        override val key get() = "engine:${kind.name}"
        override val label get() = kind.displayName()
        override val systemThemeFolder get() = "pc"
    }

    data class System(val systemId: String) : GameGroup {
        override val key get() = "system:$systemId"
        override val label get() = PlatformsDatabase.displayNameOrNull(systemId) ?: systemId
        override val systemThemeFolder get() = systemId
    }

    /**
     * Real ES-DE collection -- droidtop's own equivalent of real
     * `CollectionSystemType` (confirmed against
     * `CollectionSystemsManager.h`/`.cpp`'s own real declaration table,
     * a real local clone kept at /root/es-de-reference). Unlike
     * [Engine]/[System], membership here is cross-cutting, not a strict
     * partition of [LibraryEntry.gameGroup] -- a game can be in "All
     * games" AND "Favorites" AND its own real system group at once, so
     * collection membership is computed separately (see
     * `collectionGroupMembers` in [GamesSection]) rather than through
     * [LibraryEntry.gameGroup]. [themeFolder] is real ES-DE's own
     * documented per-collection-type theme subfolder name
     * (`auto-allgames`/`auto-lastplayed`/`auto-favorites`/
     * `custom-collections` -- every custom collection shares the one
     * generic `custom-collections` folder, same as real ES-DE) -- when
     * the active theme declares that subfolder, its own `theme.xml`
     * loads instead of the theme's root one (see `ThemeAssets.
     * loadActiveTheme`'s own `collectionThemeFolder` parameter).
     */
    data class Collection(val id: String, override val label: String, val themeFolder: String) : GameGroup {
        override val key get() = "collection:$id"
        // Same value real ES-DE uses for a collection's `${system.theme}`
        // (SystemData.cpp:1976-2031: mThemeFolder = the collection's own
        // folder name) -- lets a theme's per-system carousel art
        // (`staticImage` with `${system.theme}` in it) resolve real
        // bundled collection art (auto-allgames.png etc.) too.
        override val systemThemeFolder get() = themeFolder
    }
}

/** Real ES-DE auto-collection ids/theme-folder names, confirmed against `CollectionSystemsManager.cpp`'s own real declaration table -- not guessed. */
private object AutoCollections {
    const val ALL_GAMES_ID = "all"
    const val FAVORITES_ID = "favorites"
    const val LAST_PLAYED_ID = "recent"
    const val CUSTOM_THEME_FOLDER = "custom-collections"
    // Real ES-DE LAST_PLAYED_MAX.
    const val LAST_PLAYED_LIMIT = 50
}

/**
 * Real cross-composable-tree refresh signal -- same real shape
 * [dev.droidtop.library.theme.ThemePrefs]'s own `version` property
 * already uses. Needed because [CollectionMembershipEditor] (create/
 * toggle a collection) lives outside [GamesSection]'s own composable
 * subtree (it's rendered from a sibling `detailEntry` branch in
 * `GamepadShell` itself), so a plain `remember` inside `GamesSection`
 * has no way to know a collection changed elsewhere.
 */
internal object CollectionsRefresh {
    var version by mutableIntStateOf(0)
        private set

    fun bump() {
        version++
    }
}

// systemId-based grouping isn't CONSOLE_ROM-specific: PcGameProvider tags
// its real WINE_PROFILE entries with systemId = "pc" (ES-DE's own system
// id) specifically so they render through this same System -> Game
// carousel/theming, not a generic "Windows" engine bucket -- any future
// kind that sets systemId gets the same real theming for free.
private fun LibraryEntry.gameGroup(): GameGroup {
    // Local val, not a direct smart-cast on systemId: it's a public property
    // declared in a different module (library-core), which Kotlin won't
    // smart-cast across module boundaries -- a real compile error caught by
    // CI, not a style choice.
    val id = systemId
    return if (id != null) GameGroup.System(id) else GameGroup.Engine(kind)
}

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
    library: Library,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onDrillDownChanged: (Boolean) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    onThemeHandlesHints: (Boolean) -> Unit = {},
    onToggleFavorite: (LibraryEntry) -> Unit = {},
) {
    var selectedGroup by remember { mutableStateOf<GameGroup?>(null) }
    var recentOnly by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val context = LocalContext.current

    // Real custom collections + membership, loaded once and refreshed
    // whenever collectionsVersion bumps (mirrors ThemePrefs.version's own
    // "force a real refresh, not just recomposition-by-luck" pattern) --
    // CollectionMembershipEditor calls CollectionsRefresh.bump() after
    // any real create/toggle.
    var customCollections by remember { mutableStateOf<List<dev.droidtop.library.consoles.CollectionEntity>>(emptyList()) }
    var customCollectionMembership by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    LaunchedEffect(CollectionsRefresh.version) {
        customCollections = library.getCollections()
        customCollectionMembership = library.getCollectionMembership()
    }
    val entriesById = remember(entries) { entries.associateBy { it.id } }
    // Real ES-DE auto-collections -- computed on the fly, never stored
    // (see GameGroup.Collection's own doc comment). Only shown when
    // non-empty, same "present-and-non-empty groups" convention the
    // real system/engine groups below already use.
    val autoCollectionGroups = remember(entries) {
        buildList {
            if (entries.isNotEmpty()) add(GameGroup.Collection(AutoCollections.ALL_GAMES_ID, "All games", "auto-allgames"))
            if (entries.any { it.favorite }) add(GameGroup.Collection(AutoCollections.FAVORITES_ID, "Favorites", "auto-favorites"))
            if (entries.any { it.lastPlayedEpochMs != null }) add(GameGroup.Collection(AutoCollections.LAST_PLAYED_ID, "Last played", "auto-lastplayed"))
        }
    }
    val customCollectionGroups = remember(customCollections) {
        customCollections.map { GameGroup.Collection(it.id, it.name, AutoCollections.CUSTOM_THEME_FOLDER) }
    }
    val collectionGroups = autoCollectionGroups + customCollectionGroups
    // Real cross-cutting membership -- a game can be in several
    // collections at once, unlike the strict system/engine partition
    // below (see GameGroup.Collection's own doc comment).
    val collectionGroupMembers = remember(collectionGroups, entries, customCollectionMembership) {
        collectionGroups.associateWith { group ->
            when (group.id) {
                AutoCollections.ALL_GAMES_ID -> entries
                AutoCollections.FAVORITES_ID -> entries.filter { it.favorite }
                AutoCollections.LAST_PLAYED_ID -> entries
                    .filter { it.lastPlayedEpochMs != null }
                    .sortedByDescending { it.lastPlayedEpochMs }
                    .take(AutoCollections.LAST_PLAYED_LIMIT)
                else -> customCollectionMembership[group.id].orEmpty().mapNotNull { entriesById[it] }
            }
        }
    }
    // Real per-game navigation index for the drilled-into-a-system
    // "gamelist" screen, ONLY used when the active theme's own real
    // gamelist view has no <carousel>/<grid>/<textlist> of its own to
    // delegate D-pad focus-tracking to (DEcaffe's real gamelist view is
    // exactly this case -- see EsDeThemeView.primaryListElement's own
    // updated doc comment). Reset whenever the drilled-into system
    // changes, matching real ES-DE's own "selection resets per gamelist"
    // convention.
    var focusedGameIndex by remember(selectedGroup) { mutableStateOf(0) }
    val selectedGroupSystemId = selectedGroup?.systemThemeFolder
    val selectedGroupThemeFolder = (selectedGroup as? GameGroup.Collection)?.themeFolder
    val selectedGroupLabel = selectedGroup?.label
    val gamelistTheme = remember(selectedGroup, selectedGroupSystemId, selectedGroupThemeFolder, ThemePrefs.version) {
        if (selectedGroup != null) {
            ThemeAssets.loadActiveTheme(context, selectedGroupSystemId, selectedGroupThemeFolder, systemFullName = selectedGroupLabel)
        } else {
            null
        }
    }
    val gamelistView = gamelistTheme?.views?.get("gamelist")
    val gamelistHasListWidget = remember(gamelistView) { gamelistView?.primaryListElement() != null }
    // Alphabetical -- real ES-DE's own default gamelist sort order, and a
    // real, stable Up/Down order for the headless (no list widget) case
    // below, unlike allGames' own natural Library order.
    val systemGamesForGroup = remember(selectedGroup, entries, collectionGroupMembers) {
        val group = selectedGroup
        when (group) {
            null -> emptyList()
            // Real ES-DE Last Played stays in real recency order -- the
            // one real collection where alphabetizing would defeat its
            // own purpose. Every other group (including the other two
            // real auto-collections) keeps the same alphabetical order
            // real ES-DE gamelists default to.
            is GameGroup.Collection -> if (group.id == AutoCollections.LAST_PLAYED_ID) {
                collectionGroupMembers[group].orEmpty()
            } else {
                collectionGroupMembers[group].orEmpty().sortedBy { it.title.lowercase() }
            }
            else -> entries.filter { it.gameGroup() == group }.sortedBy { it.title.lowercase() }
        }
    }
    // Real, unified themed-gamelist condition -- ONE real render path
    // below handles ANY theme with a real "gamelist" view, whether or not
    // it declares its own <carousel>/<grid>/<textlist>
    // (gamelistHasListWidget is an internal detail EsDeThemedView/
    // EsDeSystemListView already decide for themselves, exactly like the
    // system-list screen's own carousel/grid/textlist dispatch -- NOT a
    // droidtop-level "which theme is this" branch. Two real themes
    // (DEcaffe: no widget: Art Book Next: real <textlist>/<grid>) already
    // confirm both real shapes render correctly through this one path;
    // an arbitrary third-party theme should too, since nothing here is
    // keyed off theme identity, only off what the theme itself declares.
    // The OLD hand-built grid is only the real fallback for "no active
    // theme" or "the loaded theme genuinely has no gamelist view at all"
    // (invalid for a real ES-DE theme, but a real crash-guard, not a
    // normal case).
    val hasThemedGamelist = gamelistView != null
    val gamelistHasHelpSystem = remember(gamelistView) {
        gamelistView?.elements?.values?.any { it.type == "helpsystem" } == true
    }
    // Real EsDeListItem per game, only built when actually needed (the
    // widget-driven render path) -- boxart as the item's "logo" image,
    // no item count (a real game has none, unlike a system group).
    // onSelect matches the system-list screen's own real convention (A
    // activates the focused item) -- launching directly on select, since
    // a themed gamelist view has no separate "drill in further" step the
    // way the system list's onSelect (open this system) does.
    val gamelistWidgetItems = remember(systemGamesForGroup) {
        systemGamesForGroup.map { entry ->
            EsDeListItem(
                key = entry.id,
                label = entry.title,
                count = null,
                logoPath = entry.artworkUri,
                accentColor = null,
                onSelect = { onLaunch(entry) },
            )
        }
    }
    // Present-and-non-empty groups -- engines first (in GAME_KINDS'
    // declaration order), then real console systems (alphabetical by
    // display name) -- hoisted so both the system-list view and the
    // per-system grid view share one ordering (needed for ES-DE-style
    // Left/Right sibling-system switching below).
    val byGroup = entries.groupBy { it.gameGroup() }
    val orderedEngineGroups = GAME_KINDS
        // Both real systemId-bearing kinds -- CONSOLE_ROM always, WINE_PROFILE
        // via PcGameProvider's "pc" tagging -- route through GameGroup.System
        // above, not this generic engine bucket.
        .filter { it != LibraryEntryKind.CONSOLE_ROM && it != LibraryEntryKind.WINE_PROFILE }
        .map { GameGroup.Engine(it) }
        .filter { byGroup.containsKey(it) }
    val orderedSystemGroups = byGroup.keys
        .filterIsInstance<GameGroup.System>()
        .sortedBy { it.label.lowercase() }
    // Real collections (auto + custom) lead the carousel -- quick access
    // to Favorites/Last played/etc. without scrolling past every real
    // console system, same real spirit as real ES-DE's own collections
    // (a droidtop-side ordering choice; real ES-DE's own default
    // placement isn't itself part of the theme-engine spec this follows).
    val orderedGroups: List<GameGroup> = collectionGroups + orderedEngineGroups + orderedSystemGroups
    // Real per-group item counts -- byGroup covers the strict
    // system/engine partition, collectionGroupMembers covers the
    // cross-cutting collections (see GameGroup.Collection's own doc
    // comment for why these can't share one plain groupBy).
    val groupItemCounts: Map<GameGroup, Int> = remember(byGroup, collectionGroupMembers) {
        byGroup.mapValues { it.value.size } + collectionGroupMembers.mapValues { it.value.size }
    }

    LaunchedEffect(selectedGroup, hasThemedGamelist, focusedGameIndex) {
        // Real regardless of whether the theme's gamelist has its own
        // list widget -- a widget's onFocusedIndexChanged (wired at the
        // render call site below) updates the exact same focusedGameIndex
        // state the headless case's own Up/Down handling uses.
        if (hasThemedGamelist) onFocusedEntryChanged(systemGamesForGroup.getOrNull(focusedGameIndex))
    }
    LaunchedEffect(selectedGroup, hasThemedGamelist) {
        onDrillDownChanged(selectedGroup != null)
        if (selectedGroup != null) {
            // Real, conditional -- the themed gamelist render draws its
            // own real <helpsystem> only when the theme actually declares
            // one there, same as the system-list screen. The OLD
            // hand-built grid (the fallback for "no active theme" / "theme
            // has no real gamelist view at all") has no theme-drawn hints
            // of its own, so ButtonHintFooter keeps drawing that case.
            onThemeHandlesHints(hasThemedGamelist && gamelistHasHelpSystem)
        }
    }

    // Real system-back route for the drill-up: on this hardware B doubles
    // as KEYCODE_BACK, which Android delivers through the back DISPATCHER
    // (Activity back), not as a key event any composable sees -- so the
    // onKeyEvent branch below never fired for it and the Activity finished
    // instead, kicking the user out of droidtop entirely (confirmed live,
    // per report). BackHandler registers on the real dispatcher; composed
    // deeper than GamepadShell's own root swallow, so it wins while a
    // group is open.
    androidx.activity.compose.BackHandler(enabled = selectedGroup != null) {
        selectedGroup = null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                val group = selectedGroup
                val action = GamepadKeyMap.actionFor(event.key)
                when {
                    (action == GamepadAction.BACK || action == GamepadAction.B) && group != null -> {
                        selectedGroup = null
                        true
                    }
                    // ES-DE's real, documented "General navigation" convention:
                    // Left/Right inside a gamelist jumps directly to the
                    // adjacent system's gamelist rather than requiring a
                    // Back-then-reselect round trip through the system list.
                    action == GamepadAction.LEFT && group != null && orderedGroups.size > 1 -> {
                        val index = orderedGroups.indexOf(group)
                        selectedGroup = orderedGroups[(index - 1 + orderedGroups.size) % orderedGroups.size]
                        true
                    }
                    action == GamepadAction.RIGHT && group != null && orderedGroups.size > 1 -> {
                        val index = orderedGroups.indexOf(group)
                        selectedGroup = orderedGroups[(index + 1) % orderedGroups.size]
                        true
                    }
                    // Real, headless per-game navigation -- only when the
                    // active theme's gamelist view has no on-screen list
                    // widget of its own to own D-pad focus (a widget owns
                    // Up/Down/A itself via real Compose focus movement +
                    // EsDeListItem.onSelect, exactly like the system-list
                    // screen). Real ES-DE's own Left/Right-switches-
                    // sibling-system convention above already owns
                    // Left/Right regardless, so there's no conflict either
                    // way.
                    action == GamepadAction.UP && group != null && hasThemedGamelist && !gamelistHasListWidget && systemGamesForGroup.isNotEmpty() -> {
                        focusedGameIndex = (focusedGameIndex - 1 + systemGamesForGroup.size) % systemGamesForGroup.size
                        true
                    }
                    action == GamepadAction.DOWN && group != null && hasThemedGamelist && !gamelistHasListWidget && systemGamesForGroup.isNotEmpty() -> {
                        focusedGameIndex = (focusedGameIndex + 1) % systemGamesForGroup.size
                        true
                    }
                    action == GamepadAction.A && group != null && hasThemedGamelist && !gamelistHasListWidget -> {
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onLaunch(it) } != null
                    }
                    // Y/Info applies regardless of widget presence -- a
                    // real, useful action either way, not specific to the
                    // headless case.
                    action == GamepadAction.Y && group != null && hasThemedGamelist -> {
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onShowDetail(it) } != null
                    }
                    // X/favorite-toggle applies regardless of widget
                    // presence, same reasoning as Y/Info above.
                    action == GamepadAction.X && group != null && hasThemedGamelist -> {
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onToggleFavorite(it) } != null
                    }
                    else -> false
                }
            },
    ) {
        val group = selectedGroup
        if (group == null) {
            val continuePlaying = entries.filter { it.lastPlayedEpochMs != null }.sortedByDescending { it.lastPlayedEpochMs }
            // NOTE: the focus request for this screen now lives INSIDE the
            // render branch below, where whether firstFocus will actually
            // ATTACH to anything is knowable -- see its own doc comment.
            // Requesting up here (the old shape) crashed the whole app
            // ("FocusRequester is not initialized") for any real theme
            // whose system view declares no carousel/grid/textlist for the
            // requester to attach to -- confirmed live with a real
            // downloaded community theme (ES-DWEE), and the same crash
            // signature was already in the device's older crash logs.
            if (entries.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                    Text("No games detected yet.", color = Color.White, modifier = Modifier.padding(horizontal = 48.dp))
                }
            } else {
                // Box, not Column: EsDeThemedView needs to genuinely fill
                // the whole screen (a real full-bleed background image is
                // one of its own themed elements) -- a Column sibling would
                // have measured it against the Column's remaining-height
                // constraint and clipped/overlapped continuePlaying instead,
                // the same class of sizing bug fillMaxSize itself just
                // fixed at the EsDeThemedView call site below. continuePlaying
                // renders on top, anchored to the top -- the reference
                // theme has no equivalent concept, so this is droidtop's
                // own addition layered over the theme rather than part of it.
                Box(modifier = Modifier.fillMaxSize()) {
                    val context = LocalContext.current
                    // Real per-system metadata (systemName/systemManufacturer/
                    // systemReleaseYear/...) needs a theme parsed with the
                    // CURRENTLY FOCUSED system's own ${system.theme}
                    // substituted -- see ThemeAssets.loadActiveTheme's own
                    // doc comment for why this can't be one static parse.
                    // focusedSystemIndex is fed by EsDeThemedView's own
                    // onFocusedIndexChanged, driven by whichever carousel
                    // item actually has focus right now.
                    var focusedSystemIndex by remember { mutableStateOf(0) }
                    val focusedGroup = orderedGroups.getOrNull(focusedSystemIndex)
                    val focusedSystemId = focusedGroup?.systemThemeFolder
                    val focusedThemeFolder = (focusedGroup as? GameGroup.Collection)?.themeFolder
                    // Keyed by ThemePrefs.version too, not just
                    // focusedSystemId -- otherwise switching the active
                    // theme from Settings has no effect until some
                    // unrelated recomposition happens to also fire (see
                    // ThemePrefs.version's own doc comment).
                    val focusedGroupLabel = focusedGroup?.label
                    val theme = remember(focusedSystemId, focusedThemeFolder, ThemePrefs.version) {
                        ThemeAssets.loadActiveTheme(context, focusedSystemId, focusedThemeFolder, systemFullName = focusedGroupLabel)
                    }
                    val listElement = remember(theme) { theme?.views?.get("system")?.primaryListElement() }
                    // remember(): building this list runs systemLogoPath/
                    // SystemThemeColors per group -- cache-hit lookups, but
                    // still N of them per recomposition, and the carousel
                    // recomposes every animation frame. Keyed on
                    // ThemePrefs.version so a live theme switch still
                    // rebuilds the logo paths.
                    val items = remember(orderedGroups, groupItemCounts, ThemePrefs.version) {
                        orderedGroups.map { entryGroup ->
                            EsDeListItem(
                                key = entryGroup.key,
                                label = entryGroup.label,
                                count = groupItemCounts[entryGroup] ?: 0,
                                logoPath = entryGroup.systemThemeFolder?.let { ThemeAssets.systemLogoPath(context, it) },
                                accentColor = entryGroup.systemThemeFolder
                                    ?.let { SystemThemeColors.forSystem(context, it) }
                                    ?.let { Color(it) },
                                onSelect = { selectedGroup = entryGroup },
                            )
                        }
                    }
                    // Real fix: the theme now drives this whole screen's
                    // layout, not just a decorative background behind
                    // droidtop's own hardcoded system-list Column. The
                    // carousel/grid/textlist is positioned at the theme's
                    // own real pos/size (see EsDeThemedView's own doc
                    // comment) alongside every other themed element
                    // (background art, info text, help icons), composited
                    // together by real z-index -- fillMaxSize (not the
                    // fillMaxWidth this used to be) is what actually lets a
                    // full-bleed background image cover the real screen
                    // instead of just whatever height droidtop's own
                    // content happened to wrap to. No separate "Systems"
                    // label anymore -- the theme's own carousel title
                    // treatment is the real visual identity for "what's
                    // selected," matching the reference theme.
                    // The real games backing whichever system the carousel
                    // currently has focus on -- feeds EsDeThemedView's own
                    // gameselector-driven elements (screen2's game-preview
                    // poster, the game1..game9 mosaic, the metadata-bound
                    // title caption). Empty for a non-System group (an
                    // engine bucket like "Ren'Py") since there's no single
                    // real games-folder game list for those; those groups
                    // legitimately just show no game preview.
                    // byGroup only partitions real System/Engine groups --
                    // a Collection's members live in collectionGroupMembers
                    // (cross-cutting, see GameGroup.Collection's own doc
                    // comment). Reading byGroup for a collection returned
                    // an empty list, which showed up on-device as
                    // "0 games (0 favorites)" in the themed gamecount strip
                    // and an empty game-preview for every collection.
                    val focusedSystemEntries = when (val focused = orderedGroups.getOrNull(focusedSystemIndex)) {
                        null -> emptyList()
                        is GameGroup.Collection -> collectionGroupMembers[focused].orEmpty()
                        else -> byGroup[focused].orEmpty()
                    }
                    // Real hints for THIS exact screen state, matching what
                    // ButtonHintFooter would compute for it (canGoBack=false,
                    // showInfo=true, showSectionSwitch=true, showSystemSwitch=
                    // false -- there's no drilled-into system yet to switch
                    // siblings of). L/R and the old compound "L/R" glyph
                    // collapse to a single representative L icon here -- a
                    // real, deliberate simplification (see
                    // EsDeThemedHelpSystem's own doc comment), not a hack.
                    val systemListHints = listOf(
                        GamepadAction.A to "Select",
                        GamepadAction.Y to "Info",
                        GamepadAction.L to "Switch section",
                    )
                    val hasThemeHelpSystem = remember(theme) {
                        theme?.views?.get("system")?.elements?.values?.any { it.type == "helpsystem" } == true
                    }
                    LaunchedEffect(hasThemeHelpSystem) { onThemeHandlesHints(hasThemeHelpSystem) }
                    val systemView = theme?.views?.get("system")
                    // Real crash boundary (confirmed live with a real
                    // downloaded community theme, ES-DWEE): firstFocus only
                    // ATTACHES when a real list widget composes with at
                    // least one item -- the themed path only does that when
                    // the theme's own system view actually declares a
                    // carousel/grid/textlist; the fallback path always
                    // does. Requesting focus on an unattached
                    // FocusRequester is a hard IllegalStateException that
                    // killed the whole app. Belt AND braces: gate on the
                    // real attachment condition, and never let a focus
                    // request crash droidtop over a theme's own structure
                    // regardless -- a theme must never be able to kill the
                    // app.
                    val willAttachFocus = items.isNotEmpty() &&
                        (systemView == null || systemView.primaryListElement() != null)
                    LaunchedEffect(willAttachFocus, systemView) {
                        if (willAttachFocus) {
                            requestFocusWhenAttached(firstFocus, "System list")
                        }
                    }
                    if (systemView != null) {
                        EsDeThemedView(
                            view = systemView,
                            items = items,
                            firstItemFocus = firstFocus,
                            modifier = Modifier.fillMaxSize(),
                            onFocusedIndexChanged = { focusedSystemIndex = it },
                            focusedSystemEntries = focusedSystemEntries,
                            hints = systemListHints,
                            systemContext = dev.droidtop.shell.gamepad.theme.EsDeSystemContext(
                                name = focusedGroupLabel,
                                gameCount = focusedSystemEntries.size,
                                favoriteCount = focusedSystemEntries.count { it.favorite },
                                // Real ES-DE special case (SystemView.cpp's own
                                // favoriteSystem/recentSystem flags): those two
                                // auto-collections show a bare game count.
                                countsOnly = (focusedGroup as? GameGroup.Collection)?.id
                                    ?.let { it == AutoCollections.FAVORITES_ID || it == AutoCollections.LAST_PLAYED_ID } == true,
                            ),
                        )
                        // NO droidtop chrome over a themed screen: the
                        // "Continue Playing" overlay sat directly on top of
                        // decaffe's own real metadata sidebar (and collided
                        // on every other real theme tested) -- a themed view
                        // owns its whole surface, same as real ES-DE. The
                        // row stays on the unthemed fallback below, which IS
                        // droidtop's own surface. (docs/SPEC.md §7f's
                        // "needs real per-theme-aware safe-zone placement"
                        // note is resolved by this simpler decision:
                        // themed screens get no overlay at all.)
                    } else {
                        EsDeSystemListView(
                            element = listElement,
                            items = items,
                            firstItemFocus = firstFocus,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
                        )
                        if (continuePlaying.isNotEmpty()) {
                            HomeSectionRow(
                                HomeSection("Continue Playing", continuePlaying),
                                firstCardFocus = null,
                                onLaunch = onLaunch,
                                onShowDetail = onShowDetail,
                                onFocusedEntryChanged = onFocusedEntryChanged,
                                modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp),
                                onToggleFavorite = onToggleFavorite,
                            )
                        }
                    }
                }
            }
        } else if (hasThemedGamelist && gamelistView != null) {
            // Real, unified theme-driven gamelist render -- ONE call into
            // the same generic EsDeThemedView/EsDeSystemListView
            // machinery the system-list screen already uses. Whether
            // THIS theme's gamelist declares a real <carousel>/<grid>/
            // <textlist> or none at all (DEcaffe: none; Art Book Next: a
            // real <textlist>/<grid>) is decided internally
            // (EsDeThemeView.primaryListElement) -- not a droidtop-level
            // "which theme is this" branch, so an arbitrary third-party
            // theme gets the same real treatment as either of these two.
            // A widget owns its own D-pad focus movement (real Compose
            // focus + EsDeListItem.onSelect, firstItemFocus attaches to
            // its first item); with no widget, this composable's own
            // headless Up/Down handling above drives focusedGameIndex
            // instead -- either way, onFocusedIndexChanged and
            // focusedGameIndex both point at the exact same state, so
            // every other element (metadata/rating/datetime/video) always
            // binds to whichever game is actually current.
            LaunchedEffect(group, gamelistHasListWidget, gamelistWidgetItems) {
                // Same never-crash boundary AND same frame-retry as the
                // system-list screen's own focus request above (see
                // requestFocusWhenAttached).
                if (gamelistHasListWidget && gamelistWidgetItems.isNotEmpty()) {
                    requestFocusWhenAttached(firstFocus, "Gamelist")
                }
            }
            EsDeThemedView(
                view = gamelistView,
                items = gamelistWidgetItems,
                firstItemFocus = if (gamelistHasListWidget) firstFocus else null,
                modifier = Modifier.fillMaxSize(),
                onFocusedIndexChanged = { focusedGameIndex = it },
                focusedSystemEntries = systemGamesForGroup,
                focusedGameIndex = focusedGameIndex,
                hints = listOf(
                    GamepadAction.A to "Launch",
                    GamepadAction.Y to "Info",
                    GamepadAction.X to "Favorite",
                    GamepadAction.B to "Back",
                ),
                systemContext = dev.droidtop.shell.gamepad.theme.EsDeSystemContext(
                    name = selectedGroupLabel,
                    gameCount = systemGamesForGroup.size,
                    favoriteCount = systemGamesForGroup.count { it.favorite },
                    countsOnly = (group as? GameGroup.Collection)?.id
                        ?.let { it == AutoCollections.FAVORITES_ID || it == AutoCollections.LAST_PLAYED_ID } == true,
                ),
            )
        } else {
            val allGames = entries.filter { it.gameGroup() == group }
            val recentCount = allGames.count { it.lastPlayedEpochMs != null }
            val games = if (recentOnly) allGames.filter { it.lastPlayedEpochMs != null } else allGames
            // Same "don't request focus on an unattached FocusRequester" fix
            // as the system-list view above -- games can be empty here too
            // (the "recent" filter selected with zero recently-played entries).
            LaunchedEffect(group, recentOnly) { if (games.isNotEmpty()) requestFocusWhenAttached(firstFocus, "Game grid") }
            // Same real per-system accent as GroupCard's own border, applied
            // as a subtle top-down vignette behind the whole grid -- carries
            // the "dynamic per-system," not just per-card, through into the
            // actual game-browsing view rather than stopping at the system
            // list.
            val drillDownAccent = group.systemThemeFolder
                ?.let { SystemThemeColors.forSystem(LocalContext.current, it) }
                ?.let { Color(it) }
            Column(
                modifier = Modifier.fillMaxSize().let {
                    if (drillDownAccent != null) {
                        it.background(Brush.verticalGradient(listOf(drillDownAccent.copy(alpha = 0.16f), Color.Transparent)))
                    } else {
                        it
                    }
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    FilterChip("${allGames.size} items", selected = !recentOnly, onClick = { recentOnly = false })
                    if (recentCount > 0) {
                        FilterChip("$recentCount recent", selected = recentOnly, onClick = { recentOnly = true })
                    }
                }
                val focusManager = LocalFocusManager.current
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    // Real bug fix, reported directly: arrow keys couldn't
                    // actually move focus between games at all -- GameCard
                    // only ever handles A/Center/Enter/Y, never Up/Down/
                    // Left/Right, and Compose has no automatic arrow-key
                    // focus movement in a grid by default. Without this,
                    // every directional keypress bubbled straight past this
                    // grid to the outer Box's sibling-system switcher (see
                    // GamesSection's own onKeyEvent above), which
                    // unconditionally treated Left/Right as "switch system"
                    // on *every* press, not just at a real grid edge.
                    // FocusManager.moveFocus's own real return value (true =
                    // moved, false = no further focusable target that
                    // direction) is exactly what onKeyEvent needs: false
                    // lets Left/Right correctly bubble up to that outer
                    // handler only once focus genuinely can't move further
                    // right/left within the grid, matching ES-DE's real
                    // "switch system at the edge" convention instead of
                    // hijacking every keypress.
                    modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                            when (GamepadKeyMap.actionFor(event.key)) {
                                GamepadAction.UP -> focusManager.moveFocus(FocusDirection.Up)
                                GamepadAction.DOWN -> focusManager.moveFocus(FocusDirection.Down)
                                GamepadAction.LEFT -> focusManager.moveFocus(FocusDirection.Left)
                                GamepadAction.RIGHT -> focusManager.moveFocus(FocusDirection.Right)
                                else -> false
                            }
                        },
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
                            onToggleFavorite = { onToggleFavorite(entry) },
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
            // Same real touch-input fix as GameCard -- see its own comment.
            .clickable(onClick = onClick)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    GamepadKeyMap.actionFor(event.key) == GamepadAction.A
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


/** Flat, kind-sectioned browser — no drill-down, unlike Games: apps aren't organized into "systems." */
@Composable
private fun AppsSection(
    entries: List<LibraryEntry>,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    onToggleFavorite: (LibraryEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val sections = buildAppSections(entries)
    val firstFocus = remember { FocusRequester() }
    // Same "don't request focus on an unattached FocusRequester" fix as
    // GamesSection -- firstFocus is only attached to a card once sections
    // is confirmed non-empty (see the early return right below).
    LaunchedEffect(entries) { if (sections.isNotEmpty()) requestFocusWhenAttached(firstFocus, "Sections") }

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
            // Native Android apps get their own dense, icon-first grid
            // (columns configurable via SettingsHandheldFragment's
            // "Apps grid columns" -- see HandheldPrefs.appsGridColumns),
            // separate from the artwork-carousel HomeSectionRow every other
            // kind still uses: apps have square launcher icons, not
            // portrait artwork, so the same 220x260 GameCard layout wastes
            // most of a real handheld's screen on empty card background.
            if (homeSection.entries.firstOrNull()?.kind == LibraryEntryKind.NATIVE_ANDROID_APP) {
                AppIconGrid(
                    homeSection,
                    columns = HandheldPrefs.appsGridColumns(context),
                    firstTileFocus = if (!firstAssigned) firstFocus else null,
                    onLaunch = onLaunch,
                    onFocusedEntryChanged = onFocusedEntryChanged,
                )
            } else {
                HomeSectionRow(
                    homeSection,
                    firstCardFocus = if (!firstAssigned) firstFocus else null,
                    onLaunch = onLaunch,
                    onShowDetail = onShowDetail,
                    onFocusedEntryChanged = onFocusedEntryChanged,
                    onToggleFavorite = onToggleFavorite,
                )
            }
            firstAssigned = true
        }
    }
}

/**
 * Dense, non-scrolling-per-row icon grid for the Apps tab — Android app
 * drawer style (icon + label, no artwork card), unlike [HomeSectionRow]'s
 * portrait-artwork carousel. [columns] comes from
 * [HandheldPrefs.appsGridColumns] so density is user-configurable
 * independent of :shell-default's own app-drawer grid width.
 */
@Composable
private fun AppIconGrid(
    section: HomeSection,
    columns: Int,
    firstTileFocus: FocusRequester?,
    onLaunch: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
) {
    Column {
        Text(
            section.title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
        )
        // Sized to fit every row with no internal scrolling of its own --
        // this grid lives inside AppsSection's outer LazyColumn (one item
        // per kind-section), and a LazyVerticalGrid can't nest inside
        // another scrollable without a fixed height. Row count is exact
        // (no clamping), so every app is always reachable, just via the
        // outer LazyColumn's scroll rather than this grid's own.
        val rowCount = (section.entries.size + columns - 1) / columns.coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtLeast(1)),
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp * rowCount.coerceAtLeast(1))
                .padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItemsIndexed(section.entries, key = { _, entry -> entry.id }) { index, entry ->
                AppIconTile(
                    entry = entry,
                    modifier = if (index == 0 && firstTileFocus != null) Modifier.focusRequester(firstTileFocus) else Modifier,
                    onLaunch = { onLaunch(entry) },
                    onFocused = { onFocusedEntryChanged(entry) },
                )
            }
        }
    }
}

@Composable
private fun AppIconTile(entry: LibraryEntry, modifier: Modifier = Modifier, onLaunch: () -> Unit, onFocused: () -> Unit = {}) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            // Same real touch-input fix as GameCard -- see its own comment.
            .clickable(onClick = onLaunch)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (GamepadKeyMap.actionFor(event.key)) {
                    GamepadAction.A -> {
                        onLaunch()
                        true
                    }
                    else -> false
                }
            }
            .border(
                width = if (focused) 3.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp)),
        ) {
            if (entry.artworkUri != null) {
                AsyncImage(
                    model = entry.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                )
            }
        }
        Text(
            entry.title,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * Real, focused theme-browser screen -- the one real piece of Handheld's
 * former in-house Settings tab that can't just become a flat Android
 * Preference entry in :shell-default's SettingsHandheldFragment (unlike
 * Library/Display/Theme/Sync theme index, all moved there -- see
 * docs/SPEC.md's own settings-architecture note): browsing/downloading a
 * NEW theme needs ThemeBrowserScreen's own rich, scrollable list of
 * remote entries with real screenshot previews, not reachable from a
 * different Gradle module. Reachable ONLY via a real deep-link (the
 * "Browse themes" preference in SettingsHandheldFragment, through
 * MainActivity's EXTRA_HANDHELD_START_SECTION) -- selecting Settings from
 * the tab bar itself now goes straight to that real, unified Preference
 * screen instead (see GamepadShell's own selectSection).
 */
internal data class HomeSection(val title: String, val entries: List<LibraryEntry>)

/**
 * One section per display name actually present among [entries], in
 * [LibraryEntryKind] declaration order. Entries within each section are
 * sorted alphabetically by title -- without this, [NativeAppProvider]'s
 * scan order (raw [android.content.pm.LauncherApps.getActivityList] order,
 * effectively install/registration order) leaked straight through to the
 * Apps tab and looked completely random; every other kind had the same
 * latent gap (only [GameGroup.System]'s system-list ordering, a separate
 * code path, was ever sorted), so this sorts generally rather than just
 * patching Apps.
 */
internal fun buildAppSections(entries: List<LibraryEntry>): List<HomeSection> {
    val byDisplayName = entries.groupBy { it.kind.displayName() }
    val order = LibraryEntryKind.entries.map { it.displayName() }.distinct()
    return order.mapNotNull { name ->
        byDisplayName[name]?.let { HomeSection(name, it.sortedBy { entry -> entry.title.lowercase() }) }
    }
}

// LibraryEntryKind.displayName() moved to library-core (shared with the
// second-screen companion panel) -- see its doc comment there.

@Composable
private fun HomeSectionRow(
    section: HomeSection,
    firstCardFocus: FocusRequester?,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (LibraryEntry) -> Unit = {},
) {
    Column(modifier = modifier) {
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
                    onToggleFavorite = { onToggleFavorite(entry) },
                )
            }
        }
    }
}

@Composable
private fun GameCard(
    entry: LibraryEntry,
    modifier: Modifier = Modifier,
    onLaunch: () -> Unit,
    onShowDetail: () -> Unit,
    onFocused: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(width = 220.dp, height = 260.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            // Real bug fix, reported directly: touch input didn't work
            // anywhere in Handheld mode -- .clickable() was never actually
            // applied here (an older comment claimed it was, but it wasn't;
            // .focusable() alone doesn't respond to taps, only to real
            // focus + the onKeyEvent below). This also gives DPAD_CENTER/
            // Enter clickable's own default key handling on a focused node
            // "for free" — a controller's face button (A / cross) still
            // reports as a distinct keycode on most Android gamepad
            // mappings, so it's still handled explicitly below rather than
            // relying on clickable() to cover it. Y opens the detail screen
            // (§7).
            .clickable(onClick = onLaunch)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (GamepadKeyMap.actionFor(event.key)) {
                    GamepadAction.A -> {
                        onLaunch()
                        true
                    }
                    GamepadAction.Y -> {
                        onShowDetail()
                        true
                    }
                    // Real, previously-dead action -- LibraryEntry.favorite
                    // existed and even rendered as a real theme badge, but
                    // nothing anywhere ever actually set it true (confirmed
                    // by grep before wiring this). X was already mapped to
                    // a real GamepadAction but unused in this whole shell.
                    GamepadAction.X -> {
                        onToggleFavorite()
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
        if (entry.favorite) {
            Text(
                "★",
                color = Color(0xFFFFD700),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
    }
}
