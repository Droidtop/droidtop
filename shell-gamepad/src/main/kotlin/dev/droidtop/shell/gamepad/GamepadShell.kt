package dev.droidtop.shell.gamepad

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.EngineGameProvider
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.PC_SYSTEM_ID
import dev.droidtop.shell.gamepad.pc.PcGameDetail
import dev.droidtop.shell.gamepad.pc.PcSurface
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.PlatformsDatabase
import dev.droidtop.library.displayName
import dev.droidtop.library.itemName
import dev.droidtop.library.kindLine
import dev.droidtop.library.integrations.Integration
import dev.droidtop.library.integrations.IntegrationCapability
import dev.droidtop.library.integrations.IntegrationStore
import dev.droidtop.library.integrations.OpenWithTarget
import dev.droidtop.library.integrations.openWithChipLabel
import dev.droidtop.library.integrations.openWithTargetsFor
import dev.droidtop.library.theme.SystemThemeColors
import dev.droidtop.library.theme.EsDeCollectionKind
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.EsDeTransitionAnimation
import dev.droidtop.library.theme.primaryListElement
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.theme.EsDeListItem
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.EsDeSystemListView
import dev.droidtop.shell.gamepad.theme.EsDeThemedView
import dev.droidtop.shell.gamepad.theme.EsDeSystemElementLayer
import dev.droidtop.shell.gamepad.theme.EsDeSystemSlot
import dev.droidtop.shell.gamepad.theme.EsDeViewLayer
import dev.droidtop.library.theme.EsDeSystemSlide
import dev.droidtop.library.theme.strOrNull
import androidx.compose.animation.core.Animatable
import dev.droidtop.library.theme.EsDeViewTransition
import dev.droidtop.shell.gamepad.theme.ES_DE_SYSTEM_FADE_MS
import dev.droidtop.shell.gamepad.theme.esDeSystemFadeSpec
import dev.droidtop.shell.gamepad.theme.esDeTransitionContext
import dev.droidtop.shell.gamepad.theme.esDeTransitionKind
import dev.droidtop.shell.gamepad.theme.esDeViewTransition
import dev.droidtop.shell.gamepad.theme.ThemePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Full-screen, controller-first library shell — the Gaming-mode UI.
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
 * this) and dual-screen
 * presentation (§4's `DualScreenCoordinator` decides role assignment,
 * nothing here renders companion content on a second screen yet).
 */
@Composable
fun GamepadShell(
    library: Library,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit = {},
    /**
     * The scanned game list, published so a second-screen companion can
     * show ambient artwork without running its own scan (docs/SPEC.md
     * section 4d). Default no-op: nothing about this shell depends on
     * anyone listening.
     */
    onEntriesChanged: (List<LibraryEntry>) -> Unit = {},
    // Real deep-link params from :app's MainActivity, which itself reads
    // them from an Intent extra sent by :shell-default's
    // SettingsGamingFragment -- a different module with no compile
    // dependency on this one, hence a plain String name here rather than
    // GamingSection itself (internal, module-private). Lets the real,
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
    // One layout system for both orientations: every screen below reads
    // LocalShellWindow instead of assuming the console's landscape
    // geometry. There is no portrait COPY of any screen.
    val shellWindow = currentShellWindow()
    // Two independent states, not one -- see Library.scanKinds' own doc
    // comment: a single combined scan meant Apps stayed empty until the
    // (real, SD-card-scale) Games/ROM scan also finished, even though
    // NativeAppProvider itself completes almost instantly on its own.
    // Library owns one background job per section, so one section's slow
    // provider can never gate the other section's ready results, and
    // leaving/recreating this composition cannot cancel either scan.
    val gameScanState = remember(library) { library.backgroundScanState(GAME_KINDS) }
    val appScanState = remember(library) { library.backgroundScanState(APP_KINDS) }
    val processGameEntries by gameScanState.collectAsState()
    val processAppEntries by appScanState.collectAsState()
    var gameEntries by remember { mutableStateOf<List<LibraryEntry>?>(processGameEntries) }
    var appEntries by remember { mutableStateOf<List<LibraryEntry>?>(processAppEntries) }
    // Bumped by the real, user-facing "Rescan library" Settings action --
    // included in both LaunchedEffect keys below so bumping it restarts
    // both collections against Library.rescanKindsProgressive instead of
    // the plain (cache-trusting) scanKindsProgressive. Previously the
    // only way to force a fresh scan was clearing app data by hand over
    // adb; a real user has no such option.
    var rescanTrigger by remember { mutableStateOf(0) }
    // ONE answer to "where is the shell" (see ShellBackStack): the
    // section, the group drilled into inside Games, the open detail, and
    // what was focused in each. Held out here, outside the screens that
    // draw them, because a screen that owns its own place in the tree
    // loses it the moment something else is drawn instead -- which is
    // exactly what build 542's "B from a game detail does not return to
    // the PC grid" was.
    val nav = remember { ShellBackStack(GamingPrefs.defaultSection(context)) }
    val section = nav.section
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
            nav.openSection(GamingSection.SETTINGS)
            browseThemesRequest++
        }
        startSectionName?.let { name ->
            GamingSection.entries.firstOrNull { it.name == name }?.let { nav.openSection(it) }
        }
    }
    // Settings is a real in-shell section again -- cycling to it with L/R
    // or picking its tab NEVER leaves the Gaming context (per direction:
    // browsing sections must maintain context). It renders the SAME shared
    // settings catalog the unified Preference surface renders
    // (GamingSettingsCatalog, :runtime-common -- see docs/SPEC.md's
    // settings architecture), so nothing about the settings themselves is
    // shell-specific; only the chrome is. Explicitly activating a
    // navigation item inside it (Global settings, another shell's
    // settings, Console systems) still opens those real surfaces -- that's
    // a deliberate user choice, exactly the distinction this draws.
    val selectSection: (GamingSection) -> Unit = { target -> nav.openSection(target) }
    var canGoBack by remember { mutableStateOf(false) }
    // What the screen on top has of its OWN to put in the help row (see
    // [HelpRowClaim]), and WHICH screen said so. A screen key rather than
    // a bare flag, because the screens cross over during the Crossfade
    // below and the one that is leaving must not answer for the one
    // arriving: with a flag, leaving Settings for Games put the answer
    // back to "nothing of its own" AFTER the Games screen had already
    // said otherwise, and it stayed wrong until the section changed again
    // -- droidtop's bar and Slate's own row then drew at once, the
    // theme's sliced in half by the bar on top of it (rig, build 546,
    // landscape).
    var helpRowClaimant by remember { mutableStateOf<Pair<String, HelpRowClaim>?>(null) }
    // Where a themed screen wants the one help row (see [EsDeHelpRowSlot]),
    // carried with the key of the screen that said so for exactly the
    // reason the claim above is: two themed screens cross over during the
    // shell's own crossfade.
    var helpRowSlotReport by remember { mutableStateOf<Pair<String, EsDeHelpRowSlot>?>(null) }
    // Derived, not a second piece of state: the stack says WHICH entry is
    // open and the scan says what that entry is. An entry a rescan no
    // longer finds closes its own detail instead of showing a game that
    // is not there any more.
    val detailEntry = remember(nav.detailId, gameEntries, appEntries) {
        nav.detailId?.let { id ->
            gameEntries.orEmpty().firstOrNull { it.id == id } ?: appEntries.orEmpty().firstOrNull { it.id == id }
        }
    }
    val scope = rememberCoroutineScope()
    // Real user-visible launch-failure state -- see launchError's render
    // site. A failed launch must inform, never kill.
    var launchError by remember { mutableStateOf<String?>(null) }
    // The fixable half of a launch failure: which system had no
    // emulator, so the banner can offer to go get one.
    var missingEmulator by remember {
        mutableStateOf<dev.droidtop.library.consoles.NoEmulatorInstalled?>(null)
    }
    // The game being started, if any: drives the launch screen (real
    // ES-DE has one; without it the shell simply freezes mid-frame for
    // the several seconds a cold emulator start takes, which reads as a
    // hang rather than as work).
    var launching by remember { mutableStateOf<LibraryEntry?>(null) }
    // Kiosk/Kid: read once per composition of the shell, and re-read
    // when the Quick Menu changes it (UiModeRefresh).
    val uiMode by dev.droidtop.library.settings.UiModeRefresh.mode.collectAsState()
    LaunchedEffect(Unit) { dev.droidtop.library.settings.UiModeRefresh.load(context) }
    // The face-button layout the person answered for (onboarding's
    // Controller step, or the Settings row that re-opens it). Loaded once
    // here because GamepadKeyMap.actionFor is on every screen's key path
    // and has no Context of its own.
    LaunchedEffect(Unit) { GamepadKeyMap.load(context) }
    // Idle tracking for the screensaver: every key press the shell sees
    // bumps this, and the timer below restarts from it. A launch counts
    // as activity too (the shell is not idle, it is behind a game).
    var lastInputMs by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    var screensaverOn by remember { mutableStateOf(false) }
    val screensaverMode = remember { ScreensaverPrefs.mode(context) }
    LaunchedEffect(uiMode) {
        if (uiMode.hidesSettings && section == GamingSection.SETTINGS) {
            nav.openSection(GamingSection.GAMES)
        }
    }
    LaunchedEffect(lastInputMs, screensaverMode, launching) {
        if (screensaverMode == ScreensaverMode.OFF || launching != null) return@LaunchedEffect
        kotlinx.coroutines.delay(screensaverMode.idleSeconds * 1000L)
        screensaverOn = true
    }
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
        mutableStateOf<DisplayChoiceRequest?>(null)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        dev.droidtop.library.LaunchDisplay.chooser = { options, canRemember, onChosen ->
            displayChoice = DisplayChoiceRequest(options, canRemember, onChosen)
        }
        onDispose { dev.droidtop.library.LaunchDisplay.chooser = null }
    }
    displayChoice?.let { request ->
        LaunchDisplayChooserDialog(
            options = request.options,
            canRemember = request.canRemember,
            onPick = { option, rememberChoice ->
                displayChoice = null
                request.onChosen(option, rememberChoice)
            },
            onCancel = { displayChoice = null },
        )
    }

    if (quickMenuOpen) {
        QuickMenu(onDismiss = { quickMenuOpen = false })
    }

    val onLaunch: (LibraryEntry) -> Unit = { entry ->
        // Real ES-DE launch sound -- played unconditionally on any game
        // launch (ViewController.cpp:1064-1066 plays LAUNCHSOUND whether
        // or not a launch transition is configured), so it lives here in
        // the ONE launch handler rather than per key-handling site.
        EsDeNavigationSounds.play("launch")
        scope.launch {
            launchError = null
            launching = entry
            runCatching { library.launch(entry) }
                .onFailure {
                    android.util.Log.e("droidtop.GamepadShell", "Launching ${entry.title} failed", it)
                    launching = null
                    missingEmulator = it as? dev.droidtop.library.consoles.NoEmulatorInstalled
                    launchError = "Couldn't launch ${entry.title}: ${it.message}"
                }
            // Held briefly after the launch call returns: the call
            // returns as soon as the intent is dispatched, while the
            // emulator's own window takes a moment more to cover us.
            // Clearing immediately would flash the shell back for a
            // frame, which is exactly the stutter this screen exists to
            // remove. onStop clears it too, for the case where the game
            // arrives sooner.
            kotlinx.coroutines.delay(2500)
            launching = null
        }
    }
    val tabBarFocus = remember { FocusRequester() }
    // Where an unhandled B goes: the same dispatcher every BackHandler in
    // this shell registers on (see Modifier.ownPadButtons).
    val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

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
        // A changed root set invalidates what the providers cached about
        // the old one, so the first scan after onboarding adds a folder
        // is a real walk and not a replay of an empty cache. Collected
        // for as long as this shell is composed rather than checked once:
        // onboarding adds the folder while this composition is alive and
        // hands back through onResume, which recomposes nothing (see
        // GamesRoots.changes for the rig evidence).
        dev.droidtop.library.GamesRoots.changes(context).collect {
            val rootsChanged = dev.droidtop.library.GamesRoots.rootsChangedSinceLastScan(context)
            if (rootsChanged) {
                // A root the user took away takes its games with it --
                // the one case where the index drops instead of marking
                // missing (docs/SPEC.md 7g). Before the walk, so the
                // library never shows a removed root's games while the
                // new set is being read.
                library.keepOnlyRoots(
                    dev.droidtop.library.GamesRoots.current(context).map { root -> root.absolutePath }.toSet(),
                )
            }
            library.scanInBackground(
                GAME_KINDS,
                rescan = rescanTrigger != 0 || rootsChanged,
                // A walk already in flight is walking the old folders.
                restart = rootsChanged,
            )
            if (rootsChanged) dev.droidtop.library.GamesRoots.markScanned(context)
        }
    }
    LaunchedEffect(library, rescanTrigger) {
        library.scanInBackground(APP_KINDS, rescan = rescanTrigger != 0)
    }
    LaunchedEffect(processGameEntries) {
        processGameEntries?.let { entries ->
            val games = entries.filter { it.kind in GAME_KINDS }
            gameEntries = games
            onEntriesChanged(games)
        }
    }
    LaunchedEffect(processAppEntries) {
        processAppEntries?.let { entries -> appEntries = entries.filter { it.kind in APP_KINDS } }
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
    // lives in SettingsGamingFragment (see MainActivity's own
    // EXTRA_GAMING_RESCAN, read once above into rescanTrigger's initial
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
            // Real ES-DE favorite sound -- played on an actual toggle
            // (GamelistBase.cpp:273-286), which is why it's after the
            // null-check: a kind with no favorite concept makes no sound.
            EsDeNavigationSounds.play("favorite")
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
    androidx.activity.compose.BackHandler(enabled = detailEntry != null) { nav.back() }
    // Which screen is on top right now. One expression, read by the
    // Crossfade below as its target and by the help-row claim.
    val currentScreenKey = nav.detailId ?: "section:${section.name}"
    // A claim counts only while the screen that made it is the screen on
    // top; anything else -- a plain droidtop screen, a screen whose theme
    // declares no <helpsystem>, a stale claim from a screen that has left
    // -- leaves the row to the shell.
    val helpRowClaim = helpRowClaimant?.takeIf { it.first == currentScreenKey }?.second ?: HelpRowClaim.NONE
    // ES-DE's own default help place until the themed screen on top says
    // otherwise -- never a strip of droidtop's own below the canvas.
    val helpRowSlot = helpRowSlotReport?.takeIf { it.first == currentScreenKey }?.second
        ?: EsDeHelpRowSlot.esDeDefault(vertical = shellWindow.heightDp > shellWindow.widthDp)
    // ONE answer to "who draws the help row", read by every side of it:
    // the shell's own button bar is drawn exactly when this says SHELL,
    // a screen's own bar exactly when it says SCREEN, and the themed
    // renderer draws the theme's <helpsystem> exactly when it says THEME
    // -- see [esDeHelpRowOwner]. Real ES-DE has one help bar per window
    // (Window.cpp:126, :884), and independent conditions for it are how
    // droidtop ended up drawing two at once.
    val helpRowOwner = esDeHelpRowOwner(
        showHints = GamingPrefs.showHints(context),
        touchFirst = shellWindow.touchFirst,
        claim = helpRowClaim,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShellWindow provides shellWindow,
        LocalHelpRowOwner provides helpRowOwner,
        LocalHelpRowSlotReport provides { slot -> helpRowSlotReport = currentScreenKey to slot },
    ) {
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
            // Touch counts as activity too: only key events reset the
            // idle timer before this, so the screensaver could appear
            // while somebody was actively tapping. Initial pass, so it
            // observes without consuming anything.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        lastInputMs = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
            // Outermost, so it sees only what nothing below wanted: the
            // shell owns the pad, and Android's generic fallbacks never
            // act on it (Modifier.ownPadButtons).
            .ownPadButtons { backDispatcher?.onBackPressed() }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    lastInputMs = android.os.SystemClock.elapsedRealtime()
                    if (screensaverOn) {
                        // The press that wakes the shell belongs to the
                        // screensaver, not to whatever it was over.
                        screensaverOn = false
                        return@onKeyEvent true
                    }
                }
                // Quick Menu trigger: R2, the dedicated quick-device-
                // management button (per direction), named on screen by
                // the R2 pill in the top-right corner. KeyDown opens;
                // the same press's KeyUp lands in the menu dialog, which
                // swallows it, and a FRESH R2 press there toggles the
                // menu closed.
                if (GamepadKeyMap.actionFor(event.key) == GamepadAction.R2) {
                    if (event.type == KeyEventType.KeyDown && !quickMenuOpen) {
                        quickMenuOpen = true
                    }
                    return@onKeyEvent true
                }
                // HOLD Select stays as the fallback trigger for pads
                // whose triggers are analog-only and never emit an R2
                // KEY event at all -- a different failure domain, not a
                // second mechanism for its own sake. repeatCount >= 2
                // KeyDowns = the system's own key-repeat (~500ms), so
                // short-press Select keeps its existing meaning.
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
                val sections = sectionsFor(uiMode)
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
        SectionTabBar(
            current = section,
            onSelect = selectSection,
            currentTabFocus = tabBarFocus,
            onQuickMenu = { quickMenuOpen = true },
            sections = sectionsFor(uiMode),
        )
        // Launch-failure banner (see onLaunch's crash boundary): visible,
        // dismisses itself after a few seconds, never blocks input.
        launchError?.let { message ->
            LaunchedEffect(message) {
                // A failure offering a fix stays until it is dealt with;
                // one that is only information gets out of the way.
                if (missingEmulator == null) {
                    kotlinx.coroutines.delay(6000)
                    if (launchError == message) launchError = null
                }
            }
            missingEmulator?.let { problem ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 4.dp),
                ) {
                    ActionChip(
                        "Get an emulator",
                        highlighted = true,
                        onClick = {
                            // The players database's own package for this
                            // system, straight to the store: the fix, at
                            // the point of failure.
                            val pkg = dev.droidtop.library.consoles.KnownPlayers
                                .forSystem(context, problem.systemId)
                                .firstOrNull()?.player?.packageName
                            val uri = if (pkg != null) {
                                android.net.Uri.parse("market://details?id=$pkg")
                            } else {
                                android.net.Uri.parse("market://search?q=${problem.systemName} emulator")
                            }
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                            missingEmulator = null
                            launchError = null
                        },
                    )
                    ActionChip(
                        "Dismiss",
                        highlighted = false,
                        onClick = {
                            missingEmulator = null
                            launchError = null
                        },
                    )
                }
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
        // ONE definition of the shell's help row, placed in one of two
        // ways: over a themed screen, which laid a help row out itself,
        // and under everything else. Real ES-DE draws its one
        // HelpComponent ON the view at the theme's own <helpsystem>
        // position (Window.cpp:126, :884); droidtop's own bar takes that
        // same place rather than a strip of its own below a canvas
        // shortened to make room for it -- which is what left DEcaffe's
        // own help plate drawn empty above the bar in portrait, and gave
        // a theme a different canvas in portrait than in landscape (rig,
        // build 547).
        // A screen drawn over the group -- a game's detail, the group's
        // own options screen -- takes A and B and nothing else: Info,
        // Options, the system jump and the section switch all act on the
        // group UNDER it, and the bar promised every one of them over
        // "Stores and folders" while none dispatched there (rig, build
        // 550). The hint row promises only what dispatches (SPEC 7j).
        val overlayScreen = detailEntry != null || nav.optionsOpen
        val shellHelpRow: @Composable (Color) -> Unit = { background ->
            ButtonHintFooter(
                background = background,
                canGoBack = canGoBack || overlayScreen,
                showInfo = !overlayScreen,
                showSectionSwitch = !overlayScreen,
                showSystemSwitch = !overlayScreen && section == GamingSection.GAMES && canGoBack,
                showOptions = !overlayScreen && section == GamingSection.GAMES,
            )
        }
        Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            // ONE transition for every screen the shell itself draws.
            // Opening a game's detail out of a themed gamelist used to be
            // a cut straight into droidtop's own chrome, while moving
            // between the theme's own views faded (research/ui-polish item
            // 18). The theme's own inter-view transitions stay the
            // theme's: this is keyed on the section and the open detail,
            // not on the group, so a move between systems is still ES-DE's
            // own animation and not two animations at once.
            androidx.compose.animation.Crossfade(
                targetState = currentScreenKey,
                animationSpec = androidx.compose.animation.core.tween(SHELL_SCREEN_TRANSITION_MS),
                label = "shell screen",
                modifier = Modifier.fillMaxSize(),
            ) { screenKey ->
                // Read out of the KEY, not out of the live state: the copy
                // that is leaving has to keep drawing the screen it was, or
                // this is a dim rather than a transition.
                val entry = detailEntry?.takeIf { it.id == screenKey }
                val shownSection = GamingSection.entries.firstOrNull { "section:${it.name}" == screenKey } ?: section
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        // Starting a game owns the content area until the game's
                        // own window arrives. Checked FIRST so it covers the
                        // detail screen the launch was triggered from.
                        screensaverOn -> {
                            Screensaver(gameEntries.orEmpty()) {
                                screensaverOn = false
                                lastInputMs = android.os.SystemClock.elapsedRealtime()
                            }
                            androidx.activity.compose.BackHandler(enabled = true) { screensaverOn = false }
                        }
                        launching != null -> {
                            val starting = launching
                            if (starting != null) {
                                LaunchScreen(starting)
                                // A launch that never produces a window must
                                // never trap the shell behind this.
                                androidx.activity.compose.BackHandler(enabled = true) { launching = null }
                            }
                        }
                        // Real bug this fixes: the loading spinner used to gate this
                        // entire content area unconditionally, before `section` was
                        // ever checked -- Settings (which needs zero scan data) was
                        // stuck behind the same load state as Games/Apps, showing as
                        // "empty" even though it's a plain static list with nothing
                        // to wait for. detailEntry is also section-independent, so
                        // it stays checked before the loading gate too.
                        // A PC or engine game gets the PC surface's own detail
                        // screen (docs/SPEC.md 7i): runner availability, the
                        // per-game override and the actions that go with them are
                        // not a console ROM's concerns, and putting both on one
                        // screen is what made the old one grow two personalities.
                        entry != null && entry.isPcOrEngineGame -> PcGameDetail(
                            entry = entry,
                            library = library,
                            onLaunch = { onLaunch(entry); nav.back() },
                            onClose = { nav.back() },
                            // The game's other folders -- its versions and its
                            // segments (docs/SPEC.md 7m) -- are reachable from
                            // here, and picking one opens that folder's own
                            // detail, so Play starts what the user chose.
                            siblings = gameEntries.orEmpty(),
                            // Sideways, not deeper: another folder of the same
                            // game replaces this detail, so B from it still means
                            // "back to the grid I came from".
                            onOpenOther = { nav.openDetail(it.id) },
                        )
                        entry != null -> EntryDetailScreen(
                            entry = entry,
                            library = library,
                            onLaunch = { onLaunch(entry); nav.back() },
                            onClose = { nav.back() },
                        )
                        shownSection == GamingSection.SETTINGS -> {
                            // Back always does something in Settings: it pops a
                            // nested screen, or leaves Settings for the default
                            // section. Saying otherwise took the B hint out of
                            // the footer -- and that hint IS the touch route to B
                            // (design language: "the help/hint row is the touch
                            // route to pad buttons"), so on the rig a nested
                            // settings screen had no way out that a finger could
                            // reach at all.
                            canGoBack = true
                            SettingsCatalogView(
                                onBack = { nav.openSection(GamingPrefs.defaultSection(context)) },
                                onRescan = { rescanTrigger++ },
                                browseThemesToken = browseThemesRequest,
                            )
                        }
                        // Each section now gates on its own scan only (see
                        // gameEntries/appEntries' own comment) -- Games' spinner no
                        // longer has anything to do with whether Apps is ready, and
                        // vice versa.
                        shownSection == GamingSection.GAMES && gameEntries == null -> CircularProgressIndicator(color = Color.White)
                        shownSection == GamingSection.APPS && appEntries == null -> CircularProgressIndicator(color = Color.White)
                        else -> when (shownSection) {
                            GamingSection.GAMES -> GamesSection(
                                entries = gameEntries.orEmpty().let { all ->
                                    if (uiMode.kidGamesOnly) all.filter { it.kidGame } else all
                                },
                                library = library,
                                onLaunch = onLaunch,
                                nav = nav,
                                onShowDetail = { nav.rememberFocus(it.id); nav.openDetail(it.id) },
                                onDrillDownChanged = { canGoBack = it },
                                onFocusedEntryChanged = onFocusedEntryChanged,
                                // Scoped to the screen that says it: a
                                // claim from a copy the Crossfade is
                                // still drawing on its way out cannot
                                // answer for the one arriving.
                                onHelpRowClaim = { claim ->
                                    if (screenKey == currentScreenKey) {
                                        helpRowClaimant = screenKey to claim
                                    }
                                },
                                onToggleFavorite = onToggleFavorite,
                                onRequestRescan = { rescanTrigger++ },
                            )
                            GamingSection.APPS -> {
                                canGoBack = false
                                AppsSection(
                                    entries = appEntries.orEmpty(),
                                    onLaunch = onLaunch,
                                    onShowDetail = { nav.openDetail(it.id) },
                                    onFocusedEntryChanged = onFocusedEntryChanged,
                                    onToggleFavorite = onToggleFavorite,
                                )
                            }
                            // SETTINGS is handled above, before the loading gate --
                            // unreachable here, kept only so `when` stays exhaustive.
                            GamingSection.SETTINGS -> Unit
                        }
                    }
                }
            }
            // Over the themed canvas, at the place the theme itself laid
            // out for a help row, rather than under a canvas shortened to
            // make room for it. Two things were still wrong in portrait
            // (rig, build 548): the bar sat at the bottom EDGE rather than
            // at the theme's own help position, and it painted its own
            // opaque plate over the theme's art, which read as a separate
            // strip below a canvas that had in fact stopped there. Real
            // ES-DE's HelpComponent draws text on the view and no plate at
            // all, so neither does droidtop's bar when it is the one on a
            // themed view.
            if (helpRowOwner == HelpRowOwner.SHELL && helpRowClaim == HelpRowClaim.THEME) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // `pos` says where on the view the row goes and
                        // `origin` which point of the ROW that is, so the
                        // offset can only be worked out after the row has
                        // been measured -- the same order the themed
                        // renderer applies it in for the theme's own bar.
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints.copy(minHeight = 0))
                            val height = constraints.maxHeight
                            layout(constraints.maxWidth, height) {
                                val y = helpRowSlot.posY * height - helpRowSlot.originY * placeable.height
                                placeable.place(
                                    0,
                                    y.toInt().coerceIn(0, (height - placeable.height).coerceAtLeast(0)),
                                )
                            }
                        },
                ) { shellHelpRow(Color.Transparent) }
            }
        }
        // Drawn below the content, in the Column, for every screen that
        // has no place of its own for it. A themed screen DOES have one
        // -- the theme laid the help row out itself -- so there the same
        // bar is placed over that spot instead, above.
        if (helpRowOwner == HelpRowOwner.SHELL && helpRowClaim != HelpRowClaim.THEME) {
            shellHelpRow(TouchHintBarBackground)
        }
    }
    }
}

/**
 * Reads the mode-specific preferences set from :shell-default's real
 * settings screen (SettingsGamingFragment).
 * No compile-time dependency on :shell-default from here -- it and
 * :shell-gamepad are separate library modules wired together only by :app
 * -- so this reads the same shared SharedPreferences file
 * ([LAUNCHER_PREFS_FILE_NAME]) instead.
 */
private object GamingPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_DEFAULT_SECTION = "pref_gaming_default_section"
    private const val KEY_SHOW_HINTS = "pref_gaming_show_hints"
    private const val KEY_APPS_GRID_COLUMNS = "pref_gaming_apps_grid_columns"
    private const val DEFAULT_APPS_GRID_COLUMNS = 5
    // One shared range definition -- the settings catalog
    // (:runtime-common) is the single owner of this setting now, so the
    // formerly hand-synced copy of the XML seekbar's range is gone.
    const val MIN_APPS_GRID_COLUMNS = GamingSettingsCatalog.MIN_APPS_GRID_COLUMNS
    const val MAX_APPS_GRID_COLUMNS = GamingSettingsCatalog.MAX_APPS_GRID_COLUMNS

    fun defaultSection(context: Context): GamingSection {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_DEFAULT_SECTION, "games")) {
            "apps" -> GamingSection.APPS
            else -> GamingSection.GAMES
        }
    }

    fun showHints(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SHOW_HINTS, true)

    fun setDefaultSection(context: Context, section: GamingSection) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEFAULT_SECTION, if (section == GamingSection.APPS) "apps" else "games")
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
    // file as every other Gaming pref here, set via SettingsGamingFragment
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
    var viewingMedia by remember(entry) { mutableStateOf(false) }
    var pickingMatch by remember(entry) { mutableStateOf(false) }
    var scrapeStatus by remember(entry) { mutableStateOf<String?>(null) }
    var scrapeResult by remember(entry) { mutableStateOf<String?>(null) }
    // Everything scraped for this game, for the media viewer. Read off
    // the filesystem once per entry rather than per frame.
    val media = remember(entry) {
        val romFile = java.io.File(entry.id)
        val gamesRoot = dev.droidtop.library.GamesRoots.current(context)
            .firstOrNull { romFile.absolutePath.startsWith(it.absolutePath) }
        val systemId = entry.systemId
        if (gamesRoot != null && systemId != null) {
            dev.droidtop.library.EsDeArtwork.allMedia(gamesRoot, systemId, romFile.nameWithoutExtension)
        } else {
            emptyList()
        }
    }
    var editingCollections by remember { mutableStateOf(false) }

    // The user's own "open with" hooks (docs/SPEC.md section 12), paired
    // with the real files on this entry droidtop itself cannot open.
    // Resolved off the main thread: this reads filesDir and asks the
    // PackageManager whether each declared target app is installed.
    val openWithTargets = remember(entry) { openWithTargetsFor(entry) }
    var openWith by remember(entry) { mutableStateOf<List<Integration>>(emptyList()) }
    var integrationError by remember(entry) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry, openWithTargets) {
        openWith = if (openWithTargets.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) {
                IntegrationStore.available(context, IntegrationCapability.OPEN_WITH)
            }
        }
    }

    // Console ROMs and native apps only: a PC or engine game never
    // reaches this screen any more (see the PC branch at the call site),
    // so the launch-strategy picker, the PC scrape action and the PC
    // "choose match" branch that used to live here moved wholesale to
    // PcGameDetail rather than being duplicated across two screens.
    val isRomEntry = entry.kind == LibraryEntryKind.CONSOLE_ROM

    if (editingMetadata) {
        GameMetadataEditor(
            entry = entry,
            library = library,
            onDismiss = { editingMetadata = false },
        )
        return
    }
    if (pickingMatch) {
        ManualMatchPicker(
            entry = entry,
            onApplied = { scrapeResult = it },
            onDismiss = { pickingMatch = false },
        )
    }
    if (viewingMedia) {
        MediaViewer(title = entry.title, media = media, onClose = { viewingMedia = false })
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalShellWindow.current.edgePadding)
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
                    Text(entry.kind.itemName(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(entry.title, color = Color.White, style = MaterialTheme.typography.headlineMedium)
        if (entry.artworkUri == null) {
            Text(entry.kind.itemName(), color = Color.Gray, style = MaterialTheme.typography.titleMedium)
        }
        if (entry.playtimeSeconds > 0) {
            Text("Played ${entry.playtimeSeconds / 60} min", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
        }
        val detailScope = rememberCoroutineScope()
        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ActionChip("Launch", highlighted = true, modifier = Modifier.focusRequester(launchFocus), onClick = onLaunch)
            // Real ConsoleRomProvider-specific concept -- same honest
            // "not applicable" gating Library.toggleFavorite/
            // saveMetadata already use for a non-ROM entry.
            if (isRomEntry) {
                ActionChip("Choose match", highlighted = false, onClick = { pickingMatch = true })
            }
            if (media.size > 1) {
                ActionChip("View media (${media.size})", highlighted = false, onClick = { viewingMedia = true })
            }
            // One chip per (hook, openable file). Never a substitution and
            // never a silent pick: droidtop has no manual reader and no
            // full video player of its own, so these open a door rather
            // than taking over a behaviour, and if the user declared two
            // hooks they both appear and the user chooses -- droidtop does
            // not rank them.
            openWith.forEach { integration ->
                openWithTargets.forEach { target ->
                    ActionChip(
                        openWithChipLabel(integration, target, openWithTargets.size),
                        highlighted = false,
                        onClick = {
                            integrationError = runCatching {
                                IntegrationStore.run(
                                    context = context,
                                    integration = integration,
                                    systemId = entry.systemId,
                                    file = target.file,
                                )
                            }.exceptionOrNull()?.let { failure ->
                                "${integration.label} failed: ${failure.message ?: failure::class.java.simpleName}"
                            }
                        },
                    )
                }
            }
            if (isRomEntry) {
                ActionChip("Edit metadata", highlighted = false, onClick = { editingMetadata = true })
                ActionChip("Collections", highlighted = false, onClick = { editingCollections = true })
                ActionChip(
                    scrapeStatus?.let { "Scraping…" } ?: "Scrape",
                    highlighted = false,
                    onClick = {
                        if (scrapeStatus == null) {
                            scrapeStatus = "Scraping ${entry.title}…"
                            detailScope.launch {
                                val romFile = java.io.File(entry.id)
                                val folder = romFile.parentFile
                                val systemsById = dev.droidtop.library.consoles.ConsoleSystemsRepository
                                    .allSystems(context).associateBy { it.id }
                                val system = entry.systemId?.let { systemsById[it] }
                                scrapeResult = if (folder == null || system == null) {
                                    "Can't resolve this game's system folder."
                                } else {
                                    dev.droidtop.library.scraper.scrapeSystemArtwork(
                                        context,
                                        folder,
                                        system,
                                        onlyRom = romFile,
                                    )
                                }
                                scrapeStatus = null
                            }
                        }
                    },
                )
            }
            ActionChip("Back", highlighted = false, onClick = onClose)
        }
        (scrapeStatus ?: scrapeResult)?.let {
            Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        // An integration drives another app's own real Activity, so it can
        // fail for reasons droidtop cannot see coming (the template names a
        // component that app no longer exports). Shown, not swallowed.
        integrationError?.let {
            Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
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
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Same real touch-input fix as GameCard -- see its own comment.
            .clickable(onClick = onClick)
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
/**
 * The shell's persistent legend for what the face buttons do right now
 * -- and, since every hint names exactly one pad action, its touch
 * control surface too: tapping a hint dispatches that button press
 * (see [TouchHintBar]). One list, one meaning, two ways in; no screen
 * needs a second copy of what a press does.
 */
@Composable
private fun ButtonHintFooter(
    canGoBack: Boolean,
    showInfo: Boolean,
    showSectionSwitch: Boolean = false,
    showSystemSwitch: Boolean = false,
    showOptions: Boolean = false,
    background: Color = TouchHintBarBackground,
) {
    TouchHintBar(
        background = background,
        hints = buildList {
            add(GamepadAction.A to "Select")
            if (showInfo) add(GamepadAction.Y to "Info")
            if (canGoBack) add(GamepadAction.B to "Back")
            // Gamelist options (sort/scrape/import for where you are)
            // were on Select and named nowhere on screen: with no pad
            // attached they were unreachable, and with one they were
            // undiscoverable.
            if (showOptions) add(GamepadAction.SELECT to "Options")
            // ES-DE's own documented "General navigation": Left/Right
            // inside a gamelist jump to the adjacent system rather than
            // going back and reselecting. Named as two separate hints
            // rather than one compound arrow glyph, because each has to
            // be tappable on its own.
            if (showSystemSwitch) add(GamepadAction.LEFT to "Previous system")
            if (showSystemSwitch) add(GamepadAction.RIGHT to "Next system")
            // L1/R1 cycle the top-level sections from anywhere.
            if (showSectionSwitch) add(GamepadAction.R to "Switch section")
        },
    )
}

internal enum class GamingSection { GAMES, APPS, SETTINGS }

/**
 * How long one shell-drawn screen takes to become another. ES-DE's own
 * inter-view fade is 500 ms at its slowest and 160 ms at its fastest
 * (ViewController.cpp's transition durations); the shell's own screens
 * are not theme content, so they take the short end of that -- long
 * enough not to be a cut, short enough that a pad user pressing B twice
 * is never waiting on it.
 */
private const val SHELL_SCREEN_TRANSITION_MS = 160

/**
 * The sections a given UI mode allows. Kiosk and Kid hide Settings --
 * the point of both is handing the device to somebody without handing
 * over the device's configuration.
 */
internal fun sectionsFor(mode: dev.droidtop.library.settings.UiMode): List<GamingSection> =
    if (mode.hidesSettings) {
        listOf(GamingSection.GAMES, GamingSection.APPS)
    } else {
        GamingSection.entries
    }

// Apps are what is NOT a game. A Wine profile and a Linux-container game
// used to live here, which is why the PC surface -- the declared home of
// "every PC and engine game" (DECISIONS 2026-09-10 17:05) -- could never
// be handed a store or Wine title: the Games section never saw one. They
// are games; the PC card is where they belong.
private val APP_KINDS = setOf(
    LibraryEntryKind.NATIVE_ANDROID_APP,
    LibraryEntryKind.REMOTE_STREAM,
)

/**
 * Emulated/interpreted content — droidtop's equivalent of ES-DE's
 * "systems." Everything else (native/Wine/Linux/remote) is Apps, not a
 * system. THE COMPLEMENT on purpose: this used to be a hand-kept list
 * of four engine kinds, which silently dropped every OTHER engine
 * (KiriKiri, RM2000/2003, Buriko, CatSystem2, CMVS, Flash, Godot,
 * HTML, ...) from both sections — a new engine kind now lands in
 * Games automatically instead of nowhere.
 */
private val GAME_KINDS = LibraryEntryKind.entries.toSet() - APP_KINDS

@Composable
private fun SectionTabBar(
    current: GamingSection,
    onSelect: (GamingSection) -> Unit,
    currentTabFocus: FocusRequester,
    onQuickMenu: () -> Unit,
    sections: List<GamingSection> = GamingSection.entries,
) {
    val window = LocalShellWindow.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = window.edgePadding, vertical = if (window.compact) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tabs scroll and the Quick Menu control stays pinned beside
        // them. On a phone the four names do not fit across 411dp, and a
        // plain Row silently pushes the last one off the edge -- which on
        // the desktop-mode tab set is the tab a user cannot otherwise
        // reach without a pad. Same rule as the hint bar and the PC
        // filter chips: a row that can outgrow the width scrolls rather
        // than clipping.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(window.tabGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sections.forEach { entrySection ->
                val focused = entrySection == current
                Text(
                    text = entrySection.displayName(),
                    color = if (focused) Color.White else Color.Gray,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = (if (entrySection == current) Modifier.focusRequester(currentTabFocus) else Modifier)
                        .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
                        // Ahead of the focus targets, not after them: see
                        // [GameCard].
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyUp &&
                                GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                            ) {
                                onSelect(entrySection)
                                true
                            } else {
                                false
                            }
                        }
                        .focusable()
                        // Same real touch-input fix as GameCard -- see its own
                        // comment. This is the top-level Games/Apps/Settings
                        // tab bar, the very first thing a user taps.
                        .clickable(onClick = { onSelect(entrySection) }),
                )
            }
        }
        Spacer(Modifier.width(window.tabGap))
        // On-screen indicator for the Quick Menu button (per direction):
        // the bordered pill names the physical button, the label names
        // what it opens. Tapping it opens the menu too -- touch parity,
        // the same rule as the section tabs beside it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
                .clickable(onClick = onQuickMenu),
        ) {
            Text(
                "R2",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .border(1.dp, Color.Gray, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text("Quick Menu", color = Color.Gray, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun GamingSection.displayName(): String = when (this) {
    GamingSection.GAMES -> "Games"
    GamingSection.APPS -> "Apps"
    GamingSection.SETTINGS -> "Settings"
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

    /**
     * The PC category: ONE card for every PC and engine game, and the
     * only group the ES-DE theme does not draw past its own card (see
     * [dev.droidtop.shell.gamepad.pc.PcSurface], docs/SPEC.md 7i).
     *
     * There used to be a card per engine here (a "Ren'Py" card, an "RPG
     * Maker" card, ...), invented by droidtop and retired by the user
     * 2026-09-10: "Engine games were ALWAYS going to be under PC. PC is
     * a list of games, and each game is run according to its
     * configuration." This group is also the one owner of the `pc`
     * system id and theme folder, so a games-root folder literally named
     * `pc` full of ROM files can no longer produce a second, rival "PC"
     * card whose surface then has nothing in it.
     */
    object Pc : GameGroup {
        override val key get() = "system:$PC_SYSTEM_ID"
        override val label get() = PlatformsDatabase.displayNameOrNull(PC_SYSTEM_ID) ?: "PC"
        override val systemThemeFolder get() = PC_SYSTEM_ID
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

/**
 * Which of real ES-DE's two collection KINDS a group is, for the theme
 * properties that case a collection's own name
 * (`letterCaseAutoCollections` / `letterCaseCustomCollections`,
 * SystemView.cpp:835-849). The auto-collections are the three computed ones
 * -- ES-DE's own `isCollection() && !isCustomCollection()` -- and anything
 * the user built themselves is custom, which here is exactly the
 * [GameGroup.Collection] whose theme folder is the custom one.
 */
private fun GameGroup.esDeCollectionKind(): EsDeCollectionKind = when {
    this !is GameGroup.Collection -> EsDeCollectionKind.NONE
    themeFolder == AutoCollections.CUSTOM_THEME_FOLDER -> EsDeCollectionKind.CUSTOM
    else -> EsDeCollectionKind.AUTO
}

/**
 * One system's own contribution to the system view, for the element layer
 * that draws several of them at once while they slide past each other.
 *
 * A theme is parsed PER SYSTEM -- `${system.theme}` and the rest of the
 * `${system.*}` family are substituted with that system's own values (see
 * `ThemeAssets.loadActiveTheme`) -- which is why ES-DE keeps one parsed
 * element set per system (`mSystemElements`, SystemView.cpp:700-800) rather
 * than one for the view. The loads are cached, so asking for a neighbour's
 * theme mid-slide costs a lookup.
 *
 * Null when the theme has no system view at all, which is the same
 * condition the non-sliding path already checks before rendering anything.
 */
@Composable
private fun rememberEsDeSystemSlot(
    group: GameGroup?,
    entries: List<LibraryEntry>,
    countsOnly: Boolean,
): EsDeSystemSlot? {
    val context = LocalContext.current
    val themeFolder = (group as? GameGroup.Collection)?.themeFolder
    val theme = remember(group?.systemThemeFolder, themeFolder, group?.label, ThemePrefs.version) {
        ThemeAssets.loadActiveTheme(
            context,
            group?.systemThemeFolder,
            themeFolder,
            systemFullName = group?.label,
            collectionKind = group?.esDeCollectionKind() ?: EsDeCollectionKind.NONE,
        )
    }
    val view = theme?.views?.get("system") ?: return null
    return EsDeSystemSlot(
        view = view,
        entries = entries,
        systemContext = dev.droidtop.shell.gamepad.theme.EsDeSystemContext(
            name = group?.label,
            gameCount = entries.size,
            favoriteCount = entries.count { it.favorite },
            countsOnly = countsOnly,
        ),
    )
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

/**
 * Which carousel card an entry belongs to.
 *
 * Two groups only: a real console system (a ROM with a systemId the
 * platforms database knows) and [GameGroup.Pc], which is everything
 * else. A detected engine game carries no systemId at all -- that is why
 * it used to land in a per-engine bucket and could never reach the PC
 * surface (emulator rig, 2026-09-10) -- and a store or Wine title
 * carries systemId "pc", which the PC group now owns outright rather
 * than sharing with a console system of the same name.
 */
private fun LibraryEntry.gameGroup(): GameGroup {
    // Local val, not a direct smart-cast on systemId: it's a public property
    // declared in a different module (library-core), which Kotlin won't
    // smart-cast across module boundaries -- a real compile error caught by
    // CI, not a style choice.
    val id = systemId
    return when {
        isPcOrEngineGame -> GameGroup.Pc
        // ES-DE's `pc` system folder is droidtop's own category now
        // (DECISIONS 2026-09-10 16:36), so whatever a user dropped in
        // roms/pc joins the one PC list instead of claiming a card of
        // its own that the PC surface would then render empty.
        id == null || id == PC_SYSTEM_ID -> GameGroup.Pc
        else -> GameGroup.System(id)
    }
}

/** Which card an entry lands on, as a plain string -- the grouping rule above, reachable from a unit test. */
internal fun gameGroupKey(entry: LibraryEntry): String = entry.gameGroup().key

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
    nav: ShellBackStack,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onDrillDownChanged: (Boolean) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    onHelpRowClaim: (HelpRowClaim) -> Unit = {},
    onToggleFavorite: (LibraryEntry) -> Unit = {},
    onRequestRescan: () -> Unit = {},
) {
    var recentOnly by remember { mutableStateOf(false) }
    // The in-gamelist options overlay (sort, scrape, import) -- see
    // GamelistOptionsMenu. sortVersion invalidates the games ordering
    // below when the overlay cycles the stored sort; scrapeVersion does
    // the same for artwork after an in-place scrape.
    var gamelistOptionsOpen by remember { mutableStateOf(false) }
    var sortVersion by remember { mutableIntStateOf(0) }
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
    // Present-and-non-empty groups -- engines first (in GAME_KINDS'
    // declaration order), then real console systems (alphabetical by
    // display name) -- hoisted so both the system-list view and the
    // per-system grid view share one ordering (needed for ES-DE-style
    // Left/Right sibling-system switching below).
    val byGroup = entries.groupBy { it.gameGroup() }
    // Keyed on the platform database's load version as well as the
    // groups: labels resolve through its cache, which warms on a
    // background thread -- sorting before the warm lands used raw
    // system ids and froze "switch" at the end of the carousel
    // (observed live) instead of "Nintendo Switch" among the Nintendos.
    val platformsLoadVersion by dev.droidtop.library.consoles.PlatformsDatabase.loadVersion.collectAsState()
    // The PC card sorts among the console systems by its own label, the
    // same as every other card: it is a category of the library, not a
    // section of chrome.
    val orderedSystemGroups = remember(byGroup, platformsLoadVersion) {
        byGroup.keys
            .filterNot { it is GameGroup.Collection }
            .sortedBy { it.label.lowercase() }
    }
    // Carousel order, per direction (2026-08-31): "All games" leads
    // straight into the real systems -- Favorites/Last played/custom
    // collections were wedged between them, which meant scrolling past
    // chrome to reach content. They trail at the end instead.
    val leadingCollections = collectionGroups.filter { it.id == AutoCollections.ALL_GAMES_ID }
    val trailingCollections = collectionGroups.filterNot { it.id == AutoCollections.ALL_GAMES_ID }
    val orderedGroups: List<GameGroup> =
        leadingCollections + orderedSystemGroups + trailingCollections

    // WHERE the shell is, asked rather than owned (see ShellBackStack):
    // this screen is rebuilt from scratch every time something else is
    // drawn over it, so a group it remembered itself would be lost every
    // time a game detail opened.
    val selectedGroup = orderedGroups.firstOrNull { it.key == nav.groupKey }
    val selectGroup: (GameGroup?) -> Unit = { group -> nav.openGroup(group?.key) }
    // Real per-game navigation index for the drilled-into-a-system
    // "gamelist" screen, ONLY used when the active theme's own real
    // gamelist view has no <carousel>/<grid>/<textlist> of its own to
    // delegate D-pad focus-tracking to (DEcaffe's real gamelist view is
    // exactly this case -- see EsDeThemeView.primaryListElement's own
    // updated doc comment). Reset whenever the drilled-into system
    // changes, matching real ES-DE's own "selection resets per gamelist"
    // convention.
    val selectedGroupSystemId = selectedGroup?.systemThemeFolder
    val selectedGroupThemeFolder = (selectedGroup as? GameGroup.Collection)?.themeFolder
    val selectedGroupLabel = selectedGroup?.label
    val selectedGroupCollectionKind = selectedGroup?.esDeCollectionKind() ?: EsDeCollectionKind.NONE
    val gamelistTheme = remember(selectedGroup, selectedGroupSystemId, selectedGroupThemeFolder, ThemePrefs.version) {
        if (selectedGroup != null) {
            ThemeAssets.loadActiveTheme(
                context,
                selectedGroupSystemId,
                selectedGroupThemeFolder,
                systemFullName = selectedGroupLabel,
                collectionKind = selectedGroupCollectionKind,
            )
        } else {
            null
        }
    }
    // Real navigation-sound (re)binding -- <sound> elements are parsed
    // like any other themed element (declared under the special `all`
    // view, expanded into system+gamelist by the parser), so whichever
    // theme parse this screen just loaded carries the current sound set.
    // Keyed on the theme object itself: reparses for a different focused
    // system rebind to identical paths (cache hits, see
    // EsDeNavigationSounds.load's own doc comment).
    LaunchedEffect(gamelistTheme) { EsDeNavigationSounds.load(gamelistTheme) }
    val gamelistView = gamelistTheme?.views?.get("gamelist")
    val gamelistHasListWidget = remember(gamelistView) { gamelistView?.primaryListElement() != null }
    // Alphabetical -- real ES-DE's own default gamelist sort order, and a
    // real, stable Up/Down order for the headless (no list widget) case
    // below, unlike allGames' own natural Library order.
    val systemGamesForGroup = remember(selectedGroup, entries, collectionGroupMembers, sortVersion) {
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
            // The stored per-group sort (GamelistSortPrefs), NAME by
            // default which is real ES-DE's own gamelist default, and
            // the stored per-group filter (ALL by default).
            else -> {
                val filter = GamelistFilterPrefs.get(context, group.label)
                entries.filter { it.gameGroup() == group && filter.matches(it) }
                    .sortedWith(GamelistSortPrefs.comparator(GamelistSortPrefs.get(context, group.label)))
            }
        }
    }
    // Which game the gamelist is on. Restored from the stack, so coming
    // back from a game's detail lands on that game rather than at the top
    // of the list; 0 -- ES-DE's own "selection resets per gamelist" -- for
    // a group this session has not been in.
    var focusedGameIndex by remember(selectedGroup) {
        mutableStateOf(systemGamesForGroup.indexOfFirst { it.id == nav.focusHere }.coerceAtLeast(0))
    }
    if (gamelistOptionsOpen) {
        val group = selectedGroup
        run {
            GamelistOptionsMenu(
                // An empty key is the library scope: no group is open.
                groupKey = group?.label.orEmpty(),
                groupLabel = group?.label ?: "Library",
                systemId = when (group) {
                    is GameGroup.System -> group.systemId
                    // The PC group's downloaded_media folder is `pc`, the
                    // same one PcScrape.systemFolderFor writes into.
                    GameGroup.Pc -> PC_SYSTEM_ID
                    else -> null
                },
                onSortChanged = { sortVersion += 1 },
                // A finished scrape/import refreshes the REAL library
                // scan -- cached rows now live-resolve media, so the
                // refresh is what makes new art visible immediately.
                onScraped = {
                    sortVersion += 1
                    onRequestRescan()
                },
                onDismiss = { gamelistOptionsOpen = false },
                games = systemGamesForGroup,
                onJumpTo = { index ->
                    focusedGameIndex = index.coerceIn(0, (systemGamesForGroup.lastIndex).coerceAtLeast(0))
                },
            )
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
    // Real EsDeListItem per game, only built when actually needed (the
    // widget-driven render path) -- boxart as the item's "logo" image,
    // no item count (a real game has none, unlike a system group).
    // onSelect matches the system-list screen's own real convention (A
    // activates the focused item) -- launching directly on select, since
    // a themed gamelist view has no separate "drill in further" step the
    // way the system list's onSelect (open this system) does.
    // Keyed on the group too: whether a game shows its own system as a
    // suffix depends on whether the list being shown IS a collection.
    val inCollectionGamelist = selectedGroup is GameGroup.Collection
    val gamelistWidgetItems = remember(systemGamesForGroup, inCollectionGamelist) {
        systemGamesForGroup.map { entry ->
            EsDeListItem(
                key = entry.id,
                label = entry.title,
                logoPath = entry.artworkUri,
                onSelect = { onLaunch(entry) },
                // Lets a themed carousel/grid honour its own real
                // <imageType> for this game instead of always drawing the
                // one pre-resolved artwork. Coordinates only, no I/O here.
                mediaLocator = entry.mediaLocator,
                // Real ES-DE textlist indicators: a favorite game gets a
                // leading marker before its name in a gamelist.
                favorite = entry.favorite,
                // Real `systemNameSuffix`: inside a COLLECTION's gamelist
                // every game names the system it really comes from
                // (GamelistBase.cpp:789-806). Outside one this stays null,
                // which is ES-DE's own `isCollection` guard.
                // ES-DE appends the SHORT system name here
                // (getSourceFileData()->getSystem()->getName(), e.g.
                // "megadrive"), which is what systemId is.
                sourceSystemName = if (inCollectionGamelist) entry.systemId else null,
            )
        }
    }
    LaunchedEffect(selectedGroup, hasThemedGamelist, focusedGameIndex) {
        // Real regardless of whether the theme's gamelist has its own
        // list widget -- a widget's onFocusedIndexChanged (wired at the
        // render call site below) updates the exact same focusedGameIndex
        // state the headless case's own Up/Down handling uses.
        // The PC surface draws its own grid and reports its own focus
        // (see the PcSurface call below); this index addresses the themed
        // gamelist only, so it must not answer for a screen it does not
        // drive.
        if (hasThemedGamelist && selectedGroup !is GameGroup.Pc) {
            onFocusedEntryChanged(systemGamesForGroup.getOrNull(focusedGameIndex))
            // What to come back to. Recorded as the user moves, not only
            // when they open something, so B out of a detail and B out of
            // the gamelist agree about where they were.
            nav.rememberFocus(systemGamesForGroup.getOrNull(focusedGameIndex)?.id)
        }
    }
    LaunchedEffect(selectedGroup, hasThemedGamelist, nav.optionsOpen) {
        onDrillDownChanged(selectedGroup != null)
        if (selectedGroup != null) {
            // A THEMED view lays the help row out itself, at its own
            // <helpsystem> position or -- declaring none -- at ES-DE's own
            // default for that component, which exists whether a theme
            // styles it or not (HelpComponent.cpp:23-27). So the claim is
            // about the canvas, not about the element: it is what stops the
            // shell putting its bar in a strip BELOW a themed canvas and
            // shortening the canvas to fit (rig, build 548). The OLD
            // hand-built grid (the fallback for "no active theme" / "theme
            // has no real gamelist view at all") is not a themed canvas and
            // keeps the shell's bar in the column.
            onHelpRowClaim(
                when {
                    // The PC surface's row is droidtop's OWN, with this
                    // screen's own actions in it (A opens, it does not
                    // launch) and every hint tappable. Claiming it as a
                    // THEME row is what let a touch-first window add the
                    // shell's bar underneath it -- two rows on the PC grid
                    // in portrait, one in landscape (rig, build 547).
                    // The group's own options screen is a plain
                    // droidtop screen drawn over the group: it has no row
                    // of its own, so the shell's bar draws there -- and
                    // its B hint is the only touch route out.
                    nav.optionsOpen -> HelpRowClaim.NONE
                    selectedGroup is GameGroup.Pc -> HelpRowClaim.SCREEN
                    hasThemedGamelist -> HelpRowClaim.THEME
                    else -> HelpRowClaim.NONE
                },
            )
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
        // Real ES-DE back sound (GamelistBase.cpp:144/156 -- leaving a
        // gamelist for the system view plays BACKSOUND) -- this dispatcher
        // route and the onKeyEvent branch below are the same real drill-up,
        // just different hardware paths (see this handler's own comment).
        EsDeNavigationSounds.play("back")
        // One level at a time: a group's own options screen is above the
        // group, so back leaves THAT first.
        nav.back()
    }
    // Whether the press now in flight began while the group's options
    // screen was up. That screen's list answers B on the DOWN edge and
    // closes itself, so the UP edge of the very same press arrived here
    // with the options already closed and was read as a second B: one
    // press of the pad's B left "Stores and folders" AND the PC grid (rig,
    // build 549). The edge that opened a press decides who it belongs to.
    var pressBeganOverOptions by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) pressBeganOverOptions = nav.optionsOpen
                // The shoulders mean "switch system" at this level and
                // "switch section" above it; inside a screen opened from
                // the group they mean nothing, and letting them through
                // tore that screen down and changed the tab under it
                // (rig, build 549). Both edges, before anything below
                // sees them.
                val action = GamepadKeyMap.actionFor(event.key)
                nav.optionsOpen && (action == GamepadAction.L || action == GamepadAction.R)
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                // A screen opened FROM the group owns its own keys while
                // it is up: the group's options screen is a level above
                // this one, and its own list already handles B, its own
                // BackHandler handles the system back key, and neither
                // wants the shoulders switching the system underneath it.
                if (nav.optionsOpen) return@onKeyEvent false
                if (pressBeganOverOptions) {
                    pressBeganOverOptions = false
                    return@onKeyEvent false
                }
                val group = selectedGroup
                // The PC surface is droidtop's own screen with its own
                // focus: the themed-gamelist fallbacks below drive an
                // index into a list it does not show, so they stop at
                // its edge. Back and the shoulders still mean what they
                // mean everywhere else.
                val themed = hasThemedGamelist && group !is GameGroup.Pc
                val action = GamepadKeyMap.actionFor(event.key)
                when {
                    (action == GamepadAction.BACK || action == GamepadAction.B) && group != null -> {
                        // Same real BACKSOUND as the BackHandler route above.
                        EsDeNavigationSounds.play("back")
                        // One level at a time, the same answer the
                        // dispatcher route above uses. KEYCODE_BACK reaches
                        // the view tree as an ordinary key event BEFORE the
                        // back dispatcher, which is why this branch -- an
                        // ancestor of everything drawn inside the group --
                        // is what actually ran when the user pressed BACK
                        // inside "Stores and folders", and drilled all the
                        // way out to the carousel (rig, build 548). The
                        // guard at the top of this handler is what stops
                        // it answering for a screen above it now.
                        nav.back()
                        true
                    }
                    // ES-DE's real, documented "General navigation" convention:
                    // Left/Right inside a gamelist jumps directly to the
                    // adjacent system's gamelist rather than requiring a
                    // Back-then-reselect round trip through the system list.
                    // The SHOULDERS also quick-system-select while a
                    // gamelist is open -- consuming them here is what
                    // STOPS them from bubbling to the top-level section
                    // switcher, which used to yank a browsing user out
                    // to Apps/Settings mid-gamelist. Sections keep the
                    // shoulders at the top level, where that is what
                    // they mean; and a horizontal games carousel keeps
                    // Left/Right for its items while the shoulders still
                    // work, which is exactly the case the Left/Right
                    // branch below cannot serve.
                    (action == GamepadAction.L || action == GamepadAction.R) && group != null && orderedGroups.size > 1 -> {
                        val index = orderedGroups.indexOf(group)
                        val step = if (action == GamepadAction.L) -1 else 1
                        EsDeNavigationSounds.play("quicksysselect")
                        selectGroup(orderedGroups[(index + step + orderedGroups.size) % orderedGroups.size])
                        true
                    }
                    // Gamelist options (sort/scrape/import) right where
                    // the user is -- the ES-DE GuiGamelistOptions
                    // PATTERN in droidtop's own placement (short-press
                    // Select, which had no gamelist meaning; the Quick
                    // Menu stays on hold/R2).
                    // Select opens options for WHERE YOU ARE: a system's
                    // gamelist gets sort/scrape/import, the carousel gets
                    // the library-wide actions.
                    action == GamepadAction.SELECT -> {
                        gamelistOptionsOpen = true
                        true
                    }
                    action == GamepadAction.LEFT && group != null && orderedGroups.size > 1 -> {
                        // Real ES-DE quicksysselect sound -- this jump IS
                        // real ES-DE's quick system select (ViewController.
                        // cpp:718/728, the only two QUICKSYSSELECTSOUND
                        // call sites, both in its Left/Right system jump).
                        EsDeNavigationSounds.play("quicksysselect")
                        val index = orderedGroups.indexOf(group)
                        selectGroup(orderedGroups[(index - 1 + orderedGroups.size) % orderedGroups.size])
                        true
                    }
                    action == GamepadAction.RIGHT && group != null && orderedGroups.size > 1 -> {
                        EsDeNavigationSounds.play("quicksysselect")
                        val index = orderedGroups.indexOf(group)
                        selectGroup(orderedGroups[(index + 1) % orderedGroups.size])
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
                    action == GamepadAction.UP && group != null && themed && !gamelistHasListWidget && systemGamesForGroup.isNotEmpty() -> {
                        // Real ES-DE scroll sound -- per-game movement
                        // inside a gamelist plays SCROLLSOUND (GamelistBase.
                        // cpp:133/174/182 and every primary component when
                        // hosted in a gamelist, CarouselComponent.h:105-108).
                        EsDeNavigationSounds.play("scroll")
                        focusedGameIndex = (focusedGameIndex - 1 + systemGamesForGroup.size) % systemGamesForGroup.size
                        true
                    }
                    action == GamepadAction.DOWN && group != null && themed && !gamelistHasListWidget && systemGamesForGroup.isNotEmpty() -> {
                        EsDeNavigationSounds.play("scroll")
                        focusedGameIndex = (focusedGameIndex + 1) % systemGamesForGroup.size
                        true
                    }
                    action == GamepadAction.A && group != null && themed && !gamelistHasListWidget -> {
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onLaunch(it) } != null
                    }
                    // Y/Info applies regardless of widget presence -- a
                    // real, useful action either way, not specific to the
                    // headless case.
                    action == GamepadAction.Y && group != null && themed -> {
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onShowDetail(it) } != null
                    }
                    // X/favorite-toggle applies regardless of widget
                    // presence, same reasoning as Y/Info above.
                    action == GamepadAction.X && group != null && themed -> {
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onToggleFavorite(it) } != null
                    }
                    else -> false
                }
            },
    ) {
        // Real ES-DE view transitions (ViewController.cpp:664-1010,
        // chosen per transition kind by ThemeData::setThemeTransitions at
        // :1042-1120). droidtop cut between the system view and a gamelist
        // no matter what the theme asked for; decaffe's own default
        // profile fades both ways, and twelve of the fifteen themes
        // measured for this pass declare a profile of some kind.
        //
        // The animation depends on WHICH transition this is, so the
        // animated state is the selected group itself rather than a
        // boolean: the outgoing content has to keep rendering the group it
        // was showing while it leaves.
        val esDeTransitions = remember(ThemePrefs.version) { ThemeAssets.activeTransitions(context) }
        // Which of ES-DE's six transitions the move currently on screen
        // is, which is what both of the transition-time element
        // behaviours turn on. The pair of endpoints is plain bookkeeping,
        // not state: it is read in the same composition that changes it
        // and never needs to trigger one of its own.
        val groupHistory = remember { EsDeGroupHistory() }
        if (groupHistory.current != selectedGroup) {
            groupHistory.previous = groupHistory.current
            groupHistory.current = selectedGroup
        }
        val transitionKind = esDeTransitionKind(groupHistory.previous, groupHistory.current)
        val transitionAnimation = esDeTransitions[transitionKind] ?: EsDeTransitionAnimation.INSTANT
        val transitionTowardsGamelist = groupHistory.previous == null && groupHistory.current != null
        androidx.compose.animation.AnimatedContent(
            targetState = selectedGroup,
            // Both views are full-screen; without this the animated
            // container would wrap its content instead of owning the
            // screen a themed full-bleed background needs.
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val towardsGamelist = initialState == null && targetState != null
                val kind = esDeTransitionKind(initialState, targetState)
                esDeViewTransition(
                    esDeTransitions[kind] ?: EsDeTransitionAnimation.INSTANT,
                    towardsGamelist = towardsGamelist,
                )
            },
            label = "ES-DE view transition",
        ) { group ->
            // One context for every themed element below: what this copy
            // of the view is doing right now. See EsDeTransitionContext.
            val esDeTransition = esDeTransitionContext(
                kind = transitionKind,
                animation = transitionAnimation,
                towardsGamelist = transitionTowardsGamelist,
                outgoing = group != selectedGroup,
            )
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
                        Text("No games detected yet.", color = Color.White, modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding))
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
                        // ES-DE's system-to-system FADE (SystemView.cpp:
                        // 447-458): the element layer fades to black over
                        // the first fifth of the animation, holds, swaps
                        // the system underneath at the midpoint and fades
                        // back in -- the carousel itself keeps moving
                        // throughout, which is why the swap is a second,
                        // delayed index rather than the one the carousel
                        // reads. With any other animation the element
                        // layer follows the carousel immediately, as it
                        // always has.
                        val systemToSystem = esDeTransitions[EsDeViewTransition.SYSTEM_TO_SYSTEM]
                            ?: EsDeTransitionAnimation.INSTANT
                        var elementSystemIndex by remember { mutableStateOf(focusedSystemIndex) }
                        val systemFadeOpacity = remember { Animatable(0f) }
                        LaunchedEffect(focusedSystemIndex, systemToSystem) {
                            if (systemToSystem != EsDeTransitionAnimation.FADE ||
                                elementSystemIndex == focusedSystemIndex
                            ) {
                                elementSystemIndex = focusedSystemIndex
                                systemFadeOpacity.snapTo(0f)
                            } else {
                                launch {
                                    systemFadeOpacity.animateTo(
                                        0f,
                                        esDeSystemFadeSpec(systemFadeOpacity.value),
                                    )
                                }
                                delay(ES_DE_SYSTEM_FADE_MS / 2)
                                elementSystemIndex = focusedSystemIndex
                            }
                        }
                        val focusedGroup = orderedGroups.getOrNull(elementSystemIndex)
                        val focusedSystemId = focusedGroup?.systemThemeFolder
                        val focusedThemeFolder = (focusedGroup as? GameGroup.Collection)?.themeFolder
                        // Keyed by ThemePrefs.version too, not just
                        // focusedSystemId -- otherwise switching the active
                        // theme from Settings has no effect until some
                        // unrelated recomposition happens to also fire (see
                        // ThemePrefs.version's own doc comment).
                        val focusedGroupLabel = focusedGroup?.label
                        val theme = remember(focusedSystemId, focusedThemeFolder, ThemePrefs.version) {
                            ThemeAssets.loadActiveTheme(
                                context,
                                focusedSystemId,
                                focusedThemeFolder,
                                systemFullName = focusedGroupLabel,
                                collectionKind = focusedGroup?.esDeCollectionKind() ?: EsDeCollectionKind.NONE,
                            )
                        }
                        // Same real navigation-sound rebinding as the gamelist
                        // screen's own hook (see that LaunchedEffect's comment)
                        // -- this is the site that runs FIRST after app start /
                        // a live theme switch, so sounds bind before any
                        // gamelist is ever entered.
                        LaunchedEffect(theme) { EsDeNavigationSounds.load(theme) }
                        val listElement = remember(theme) { theme?.views?.get("system")?.primaryListElement() }
                        // remember(): building this list runs systemLogoPath/
                        // SystemThemeColors per group -- cache-hit lookups, but
                        // still N of them per recomposition, and the carousel
                        // recomposes every animation frame. Keyed on
                        // ThemePrefs.version so a live theme switch still
                        // rebuilds the logo paths.
                        val items = remember(orderedGroups, ThemePrefs.version) {
                            orderedGroups.map { entryGroup ->
                                EsDeListItem(
                                    key = entryGroup.key,
                                    label = entryGroup.label,
                                    logoPath = entryGroup.systemThemeFolder?.let { ThemeAssets.systemLogoPath(context, it) },
                                    // Real `letterCaseAutoCollections` /
                                    // `letterCaseCustomCollections`: ES-DE cases a
                                    // collection's own name by which KIND of
                                    // collection it is (SystemView.cpp:835-849).
                                    collectionKind = entryGroup.esDeCollectionKind(),
                                    // Real ES-DE select sound -- entering a
                                    // system from the system view plays
                                    // SELECTSOUND (SystemView.cpp:129).
                                    onSelect = {
                                        EsDeNavigationSounds.play("select")
                                        selectGroup(entryGroup)
                                    },
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
                        // title caption).
                        // A function of the GROUP, not of the focused one:
                        // while the element layer slides between systems it
                        // draws the neighbours too, each bound to its own
                        // system's games (SystemView.cpp's mSystemElements is
                        // parsed and fed per system).
                        //
                        // byGroup only partitions System and Pc groups --
                        // a Collection's members live in collectionGroupMembers
                        // (cross-cutting, see GameGroup.Collection's own doc
                        // comment). Reading byGroup for a collection returned
                        // an empty list, which showed up on-device as
                        // "0 games (0 favorites)" in the themed gamecount strip
                        // and an empty game-preview for every collection.
                        fun entriesOf(entryGroup: GameGroup?): List<LibraryEntry> = when (entryGroup) {
                            null -> emptyList()
                            is GameGroup.Collection -> collectionGroupMembers[entryGroup].orEmpty()
                            else -> byGroup[entryGroup].orEmpty()
                        }
                        // Real ES-DE special case (SystemView.cpp's own
                        // favoriteSystem/recentSystem flags): those two
                        // auto-collections show a bare game count.
                        fun countsOnly(entryGroup: GameGroup?): Boolean =
                            (entryGroup as? GameGroup.Collection)?.id
                                ?.let { it == AutoCollections.FAVORITES_ID || it == AutoCollections.LAST_PLAYED_ID } == true
                        val focusedSystemEntries = entriesOf(orderedGroups.getOrNull(elementSystemIndex))
                        // The hints this screen can keep (docs/SPEC.md 7j: a hint
                        // row promises only what dispatches). No Y: on the
                        // carousel nothing is focused that has info -- Y acts on
                        // a focused GAME, in a gamelist or on a card -- and the
                        // row said "Info" to a button that did nothing (UI pass
                        // 2026-09-24, screenshots 01/06). One shoulder stands for
                        // L/R here (see EsDeThemedHelpSystem's doc comment); it
                        // is R, the same button every other screen's hint row
                        // names for switching section, where this one said L.
                        val systemListHints = listOf(
                            GamepadAction.A to "Select",
                            GamepadAction.R to "Switch section",
                        )
                        val systemView = theme?.views?.get("system")
                        // Same rule as the gamelist's own claim above: a
                        // themed canvas lays the help row out itself,
                        // whether or not this theme styles <helpsystem>.
                        val themedSystemView = systemView != null
                        LaunchedEffect(themedSystemView) {
                            onHelpRowClaim(if (themedSystemView) HelpRowClaim.THEME else HelpRowClaim.NONE)
                        }
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
                            // Real ES-DE systembrowse sound: moving the
                            // system carousel plays SYSTEMBROWSESOUND
                            // (CarouselComponent.h:108-110 -- the primary
                            // component's own scroll plays systembrowse
                            // when NOT hosted in a gamelist, scroll when it
                            // is). Guarded on a real index CHANGE: this
                            // callback also fires for the initial focus
                            // attach, which is not a browse.
                            val onSystemFocused: (Int) -> Unit = {
                                if (it != focusedSystemIndex) EsDeNavigationSounds.play("systembrowse")
                                focusedSystemIndex = it
                            }
                            val systemContext = dev.droidtop.shell.gamepad.theme.EsDeSystemContext(
                                name = focusedGroupLabel,
                                gameCount = focusedSystemEntries.size,
                                favoriteCount = focusedSystemEntries.count { it.favorite },
                                countsOnly = countsOnly(focusedGroup),
                            )
                            // ES-DE's system-to-system SLIDE
                            // (SystemView.cpp:1565-1745): the element layer
                            // does not swap between systems, it travels. Each
                            // system's elements are drawn at their own
                            // distance from the carousel's continuous camera
                            // offset, so the neighbours slide in from the
                            // sides while the carousel scrolls; the carousel
                            // itself, and the window-level clock, status bar
                            // and help bar, stay put (:204, :255). That needs
                            // the view drawn in separate passes, which is the
                            // one shape this branch has that the others do
                            // not.
                            val slidingSystems = systemToSystem == EsDeTransitionAnimation.SLIDE &&
                                orderedGroups.size > 1
                            val camOffset = remember { mutableFloatStateOf(0f) }
                            val slideHorizontal = remember(listElement) {
                                EsDeSystemSlide.slidesHorizontally(listElement?.type, listElement.strOrNull("type"))
                            }
                            val systemSlot: @Composable (Int) -> EsDeSystemSlot? = { index ->
                                val slotGroup = orderedGroups.getOrNull(index)
                                rememberEsDeSystemSlot(
                                    group = slotGroup,
                                    entries = entriesOf(slotGroup),
                                    countsOnly = countsOnly(slotGroup),
                                )
                            }
                            if (slidingSystems) {
                                EsDeSystemElementLayer(
                                    layer = EsDeViewLayer.BELOW_PRIMARY,
                                    camOffset = camOffset,
                                    systemCount = orderedGroups.size,
                                    slideHorizontal = slideHorizontal,
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    slot = systemSlot,
                                )
                                EsDeThemedView(
                                    view = systemView,
                                    items = items,
                                    firstItemFocus = firstFocus,
                                    modifier = Modifier.fillMaxSize(),
                                    onFocusedIndexChanged = onSystemFocused,
                                    focusedSystemEntries = focusedSystemEntries,
                                    systemContext = systemContext,
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    layer = EsDeViewLayer.PRIMARY,
                                    onCamOffsetChanged = { camOffset.floatValue = it },
                                )
                                EsDeSystemElementLayer(
                                    layer = EsDeViewLayer.ABOVE_PRIMARY,
                                    camOffset = camOffset,
                                    systemCount = orderedGroups.size,
                                    slideHorizontal = slideHorizontal,
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    slot = systemSlot,
                                )
                                // Drawn last and never moved, which is where
                                // ES-DE draws them from: the focused system's
                                // own clock, status bar and help bar are handed
                                // to the Window (SystemView.cpp:255).
                                EsDeThemedView(
                                    view = systemView,
                                    items = emptyList(),
                                    firstItemFocus = null,
                                    modifier = Modifier.fillMaxSize(),
                                    focusedSystemEntries = focusedSystemEntries,
                                    hints = systemListHints,
                                    systemContext = systemContext,
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    layer = EsDeViewLayer.WINDOW,
                                )
                            } else {
                                EsDeThemedView(
                                    view = systemView,
                                    items = items,
                                    firstItemFocus = firstFocus,
                                    modifier = Modifier.fillMaxSize(),
                                    onFocusedIndexChanged = onSystemFocused,
                                    focusedSystemEntries = focusedSystemEntries,
                                    hints = systemListHints,
                                    systemContext = systemContext,
                                    // droidtop's own real equivalent of ES-DE's
                                    // Window::isBackgroundDimmed -- the options
                                    // menu is a Compose Dialog drawn over this
                                    // view with a scrim, so the theme's own
                                    // helpsystem *Dimmed variants apply while it
                                    // is open. See EsDeThemedHelpSystem.
                                    backgroundDimmed = gamelistOptionsOpen,
                                    transition = esDeTransition,
                                    systemFadeOpacity = systemFadeOpacity.value,
                                )
                            }
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
                                modifier = Modifier.fillMaxWidth().padding(horizontal = LocalShellWindow.current.edgePadding),
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
            } else if (group is GameGroup.Pc) {
                // The one category the theme does not draw past its own card
                // (docs/SPEC.md 7i). The system view, the pc art and the
                // transition into here all stay the theme's; everything
                // inside is droidtop's, because ES-DE's element schema has no
                // element type for a runner, a prefix or a store login.
                PcSurface(
                    // The group's own members, not a second predicate over the
                    // whole library: the card's game count and this grid were
                    // computed two different ways, which is how the card could
                    // say "1 game" over a surface that said "No PC games yet".
                    entries = systemGamesForGroup,
                    onOpen = onShowDetail,
                    // Same two facts as the themed gamelist above: which
                    // card to come back to, and which card the user is on.
                    focusEntryId = nav.focusHere,
                    onFocusedEntryChanged = { entry ->
                        nav.rememberFocus(entry?.id)
                        onFocusedEntryChanged(entry)
                    },
                    // A level of its own in the back stack, not state this
                    // screen owns: see PcSurface's own parameter comment.
                    optionsOpen = nav.optionsOpen,
                    onOpenOptions = { nav.openOptions() },
                    onCloseOptions = { nav.back() },
                )
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
                    // Same real SCROLLSOUND as the headless Up/Down branch
                    // above (a widget hosted in a gamelist scrolls with the
                    // scroll sound, CarouselComponent.h:105-108) -- guarded on
                    // a real index change, same reason as the system carousel.
                    onFocusedIndexChanged = {
                        if (it != focusedGameIndex) EsDeNavigationSounds.play("scroll")
                        focusedGameIndex = it
                    },
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
                    backgroundDimmed = gamelistOptionsOpen,
                    gamelist = true,
                    collectionGamelist = inCollectionGamelist,
                    transition = esDeTransition,
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
                        modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp),
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
                        // The hint bar's own room (MenuTokens.HintBarRoom).
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            bottom = MenuTokens.HintBarRoom,
                        ),
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
                        modifier = Modifier.fillMaxSize().padding(horizontal = LocalShellWindow.current.edgePadding)
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
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Same real touch-input fix as GameCard -- see its own comment.
            .clickable(onClick = onClick)
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
        // The hint bar's own room (MenuTokens.HintBarRoom).
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = MenuTokens.HintBarRoom),
    ) {
        items(sections, key = { it.title }) { homeSection ->
            // Native Android apps get their own dense, icon-first grid
            // (columns configurable via SettingsGamingFragment's
            // "Apps grid columns" -- see GamingPrefs.appsGridColumns),
            // separate from the artwork-carousel HomeSectionRow every other
            // kind still uses: apps have square launcher icons, not
            // portrait artwork, so the same 220x260 GameCard layout wastes
            // most of a real handheld's screen on empty card background.
            if (homeSection.entries.firstOrNull()?.kind == LibraryEntryKind.NATIVE_ANDROID_APP) {
                AppIconGrid(
                    homeSection,
                    columns = GamingPrefs.appsGridColumns(context),
                    firstTileFocus = if (!firstAssigned) firstFocus else null,
                    onLaunch = onLaunch,
                    onShowDetail = onShowDetail,
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
 * [GamingPrefs.appsGridColumns] so density is user-configurable
 * independent of :shell-default's own app-drawer grid width.
 */
@Composable
private fun AppIconGrid(
    section: HomeSection,
    columns: Int,
    firstTileFocus: FocusRequester?,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
) {
    Column {
        Text(
            section.title,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp),
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
                // One tile's whole anatomy, measured rather than guessed:
                // 8dp of focus-ring padding, the 64dp icon, 6dp, the name
                // and the kind line under it, and 8dp again. The grid has
                // no scroll of its own, so a height that is short by a
                // line clips the line rather than scrolling to it.
                .height(APP_TILE_HEIGHT * rowCount.coerceAtLeast(1))
                .padding(horizontal = LocalShellWindow.current.edgePadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            gridItemsIndexed(section.entries, key = { _, entry -> entry.id }) { index, entry ->
                AppIconTile(
                    entry = entry,
                    modifier = if (index == 0 && firstTileFocus != null) Modifier.focusRequester(firstTileFocus) else Modifier,
                    onLaunch = { onLaunch(entry) },
                    onShowDetail = { onShowDetail(entry) },
                    onFocused = { onFocusedEntryChanged(entry) },
                )
            }
        }
    }
}

private val APP_TILE_HEIGHT = 136.dp

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppIconTile(
    entry: LibraryEntry,
    modifier: Modifier = Modifier,
    onLaunch: () -> Unit,
    onShowDetail: () -> Unit = {},
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // Ahead of the focus targets, not after them: see [GameCard].
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (GamepadKeyMap.actionFor(event.key)) {
                    GamepadAction.A -> {
                        onLaunch()
                        true
                    }
                    // The row over this grid promises "Y  Info", and the
                    // long-press beside it already opens the app's own
                    // detail -- only the button route to that same screen
                    // was missing, so the row named an action nothing
                    // dispatched (rig, build 548; docs/SPEC.md 7j: a hint
                    // row promises only what dispatches). One screen, one
                    // action, both routes.
                    GamepadAction.Y -> {
                        onShowDetail()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            // Same real touch-input fix as GameCard -- see its own
            // comment -- and the same long-press-is-Y convention.
            .combinedClickable(onClick = onLaunch, onLongClick = onShowDetail)
            // The shell's ONE selection idiom, the same one [GameCard]
            // and the menus draw: the accent ring over a brightened
            // surface. This tile kept a third one -- a thin white
            // rectangle over an unchanged card -- after the cards were
            // fixed (rig, build 546), which is two answers to "what does
            // selected look like" on two grids of the same shell.
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) MenuTokens.Accent else Color(0x1FFFFFFF),
                shape = RoundedCornerShape(16.dp),
            )
            .background(
                if (focused) MenuTokens.SurfaceSelected else Color.Transparent,
                RoundedCornerShape(16.dp),
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
        // The tile's own anatomy, the same one every card in this shell
        // has: the name, then the one line that says what the thing IS.
        // The second line was dropped outright when the labels were
        // fixed (rig, build 546), which left a grid of names with no
        // statement of kind -- and this grid does hold more than one
        // (`REMOTE_STREAM` is an Apps kind too).
        //
        // It says what the TILE is, never the heading it sits under: the
        // app's own declared category when it has one, and what one entry
        // of its kind is called when it does not. It read "Apps" on every
        // tile -- the name of the tab, two lines below a heading that
        // already said it (rig, build 547).
        Text(
            entry.title,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            entry.kindLine(),
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Real, focused theme-browser screen -- the one real piece of Gaming's
 * former in-house Settings tab that can't just become a flat Android
 * Preference entry in :shell-default's SettingsGamingFragment (unlike
 * Library/Display/Theme/Sync theme index, all moved there -- see
 * docs/SPEC.md's own settings-architecture note): browsing/downloading a
 * NEW theme needs ThemeBrowserScreen's own rich, scrollable list of
 * remote entries with real screenshot previews, not reachable from a
 * different Gradle module. Reachable ONLY via a real deep-link (the
 * "Browse themes" preference in SettingsGamingFragment, through
 * MainActivity's EXTRA_GAMING_START_SECTION) -- selecting Settings from
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
            modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalShellWindow.current.edgePadding),
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    val window = LocalShellWindow.current
    Box(
        modifier = modifier
            // A 220dp card is a third of a phone's width and two
            // thirds of a console row; the card follows the window
            // rather than the console it was drawn for.
            .size(
                width = window.gridItemMinWidth,
                height = window.gridItemMinWidth * 260f / 220f,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // A screen's own key handling goes AHEAD of the focus
            // targets in the chain, never behind them. Compose
            // dispatches a key event to the key-input modifiers that
            // sit between the ACTIVE focus target and the root
            // (FocusOwnerImpl.dispatchKeyEvent; lastLocalKeyInputNode
            // stops at the next FocusTarget in the same chain), and
            // `clickable` brings a focus target of its own -- so a
            // handler written after it is never reached. What hid
            // that for two years is Android's own key-character-map
            // fallback: an unhandled BUTTON_A is re-sent as
            // DPAD_CENTER (Generic.kcm), which `clickable` treats as
            // a click, so A looked like it worked while X, Y and
            // every hint-bar tap -- a direct dispatchKeyEvent, which
            // gets no fallback -- did nothing (rig, build 548).
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
            .focusable()
            // Real bug fix, reported directly: touch input didn't work
            // anywhere in Gaming mode -- .clickable() was never actually
            // applied here (an older comment claimed it was, but it wasn't;
            // .focusable() alone doesn't respond to taps, only to real
            // focus + the onKeyEvent below). This also gives DPAD_CENTER/
            // Enter clickable's own default key handling on a focused node
            // "for free" — a controller's face button (A / cross) still
            // reports as a distinct keycode on most Android gamepad
            // mappings, so it's still handled explicitly below rather than
            // relying on clickable() to cover it. Y opens the detail screen
            // (§7).
            // Long-press is the touch equivalent of Y: the same
            // "tell me more / act on this one" the pad reaches with a
            // second button, on a surface that only has one gesture.
            // Android's own list convention, and the one Daijisho and
            // the platform launchers already train.
            .combinedClickable(onClick = onLaunch, onLongClick = onShowDetail)
            // ONE selection idiom across the shell: the menus' own
            // accent border over a brightened surface (MenuTokens), not a
            // third one. The rig counted three at once -- this card's 1px
            // white rectangle, the menus' brightened card, and the
            // theme's own highlight.
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) MenuTokens.Accent else Color(0x1FFFFFFF),
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (focused) MenuTokens.SurfaceSelected else MenuTokens.Surface,
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
                    // Constrained to the tile: a long name (and an app
                    // named after its own class is the longest of all)
                    // used to run past the card and nearly collide with
                    // its neighbour's.
                    Text(
                        entry.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.kind.displayName(),
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                Text(
                    entry.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.kind.displayName(),
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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


/** One pending "launch on which screen?" question, as handed to [LaunchDisplayChooserDialog]. */
private data class DisplayChoiceRequest(
    val options: List<dev.droidtop.library.LaunchDisplayOption>,
    val canRemember: Boolean,
    val onChosen: (dev.droidtop.library.LaunchDisplayOption, Boolean) -> Unit,
)

/**
 * The two endpoints of the view change currently on screen, so the
 * transition kind can be named the way ES-DE names it
 * (ThemeData.cpp:46-52). Deliberately not Compose state: it is written
 * and read within one composition, and making it observable would only
 * cause a second one.
 */
private class EsDeGroupHistory {
    var previous: GameGroup? = null
    var current: GameGroup? = null
}
