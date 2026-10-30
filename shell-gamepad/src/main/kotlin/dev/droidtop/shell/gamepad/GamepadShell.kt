package dev.droidtop.shell.gamepad

import androidx.compose.ui.res.stringResource
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.util.Log
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.shell.gamepad.input.PadCadence
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.droidtop.runtime.tasks.text
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.EngineGameProvider
import dev.droidtop.library.Library
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.library.AppCategoryRules
import dev.droidtop.library.AppGameMarks
import dev.droidtop.library.AppUsageAccess
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.PcSource
import dev.droidtop.shell.gamepad.query.APPS_SCOPE_ID
import dev.droidtop.shell.gamepad.query.LibraryFacet
import dev.droidtop.shell.gamepad.query.LibraryFilterSheet
import dev.droidtop.shell.gamepad.query.LibraryQuery
import dev.droidtop.shell.gamepad.query.retroQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySortSheet
import dev.droidtop.shell.gamepad.query.PersistQuery
import dev.droidtop.shell.gamepad.query.LibrarySearchDialog
import dev.droidtop.shell.gamepad.query.appsActiveView
import dev.droidtop.shell.gamepad.query.appsEmptyMessage
import dev.droidtop.shell.gamepad.query.appsLandingQuery
import dev.droidtop.shell.gamepad.query.appsStripLabel
import dev.droidtop.shell.gamepad.query.appsStripViews
import dev.droidtop.shell.gamepad.query.appsViewCounts
import dev.droidtop.shell.gamepad.query.appsViewQuery
import dev.droidtop.shell.gamepad.query.pillText
import dev.droidtop.shell.gamepad.query.SheetAction
import dev.droidtop.shell.gamepad.query.appsQueryScope
import dev.droidtop.shell.gamepad.query.rememberSavedViews
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.needsInstall
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.CapsuleStatusBadge
import dev.droidtop.shell.gamepad.pc.onPcGamesTab
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
import dev.droidtop.library.scanFollowingGamesRoots
import dev.droidtop.library.theme.EsDeTransitionAnimation
import dev.droidtop.library.theme.boolOrNull
import dev.droidtop.library.theme.primaryListElement
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.ControllerLayouts
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.FocusedHintRow
import dev.droidtop.shell.gamepad.input.FocusedHints
import dev.droidtop.shell.gamepad.input.LocalFocusedHints
import dev.droidtop.shell.gamepad.input.LocalShellMenus
import dev.droidtop.shell.gamepad.input.ShellMenuHints
import dev.droidtop.shell.gamepad.input.ShellMenuPills
import dev.droidtop.shell.gamepad.input.ShellMenus
import dev.droidtop.shell.gamepad.input.declaresHints
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.input.rememberHintList
import dev.droidtop.shell.gamepad.theme.EsDeListItem
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound
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
import dev.droidtop.ui.shared.Motion
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * this). Second-screen content is not this shell's either: `:app`
 * places the companion and decides which panel is the main one (§4c).
 */
@Composable
fun GamepadShell(
    library: Library,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit = {},
    onEntriesChanged: (List<LibraryEntry>) -> Unit = {},
    onHomeActivityChanged: (List<LibraryEntry>) -> Unit = {},
    deepLinkToken: Int = 0,
    startSectionName: String? = null,
    openQuickMenu: Boolean = false,
    triggerRescan: Boolean = false,
    triggerBrowseThemes: Boolean = false,
) {
    // The whole Gaming shell, its overlays and its dialogs draw in the
    // active ES-DE theme (docs/SPEC.md "Gaming theming").
    GamingTheme {
        // The shell window's one sliding focus ring (FocusGlide.kt); each
        // panel, menu and page that is a window of its own hosts its own.
        FocusGlideHost(Modifier.fillMaxSize()) {
          // Focus is always somewhere in the shell, so the first D-pad press moves it (Droidtop/tracker#820).
          PadFocusHolder(Modifier.fillMaxSize()) {
            GamepadShellBody(
                library = library,
                onFocusedEntryChanged = onFocusedEntryChanged,
                onEntriesChanged = onEntriesChanged,
                onHomeActivityChanged = onHomeActivityChanged,
                deepLinkToken = deepLinkToken,
                startSectionName = startSectionName,
                openQuickMenu = openQuickMenu,
                triggerRescan = triggerRescan,
                triggerBrowseThemes = triggerBrowseThemes,
            )
          }
        }
    }
}

@Composable
internal fun GamepadShellBody(
    library: Library,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit = {},
    /**
     * The scanned game list, published so a second-screen companion can
     * show ambient artwork without running its own scan (docs/SPEC.md
     * section 4d). Default no-op: nothing about this shell depends on
     * anyone listening.
     */
    onEntriesChanged: (List<LibraryEntry>) -> Unit = {},
    /**
     * What Home's Continue playing and Recently added are built from (the folded PC games, the Retro
     * library and the launcher apps that are games), published for the companion's Home so its rails match
     * the shell's (Droidtop/tracker#328).
     */
    onHomeActivityChanged: (List<LibraryEntry>) -> Unit = {},
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
    openQuickMenu: Boolean = false,
    triggerRescan: Boolean = false,
    triggerBrowseThemes: Boolean = false,
) {
    val context = LocalContext.current
    // Where droidtop's danger and confirm dialogs and its corner flashes are drawn (docs/SPEC.md 7k, "Notices").
    NoticeDialogHost()
    NoticeFlashHost()
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
    // Collected only while the shell is started: a shell left in the back
    // stack is not observing the library, and the slow pass stops for it
    // (docs/SPEC.md 2c).
    val processGameEntries by gameScanState.collectAsStateWithLifecycle()
    val processAppEntries by appScanState.collectAsStateWithLifecycle()
    var gameEntries by remember { mutableStateOf<List<LibraryEntry>?>(processGameEntries) }
    var appEntries by remember { mutableStateOf<List<LibraryEntry>?>(processAppEntries) }
    // The games every surface of this shell draws, with Switch updates
    // and DLC folded into their base game's row (docs/SPEC.md 7m,
    // "Switch content"): the gamelist draws one card per game, the
    // detail of the base game is the row that knows what its parts
    // are, and no surface can disagree with another because there is
    // ONE fold, not one per screen. Off the main thread like the PC
    // fold inside GamesSection, because classification reads each
    // container's file table; null until the first fold is done, so
    // Games draws its spinner rather than updates standing briefly as
    // games of their own.
    val foldedGameEntries by produceState<List<LibraryEntry>?>(initialValue = null, gameEntries) {
        val games = gameEntries ?: return@produceState
        value = withContext(Dispatchers.Default) {
            dev.droidtop.library.SwitchGameGrouping.fold(games)
        }
    }
    // Bumped by the real, user-facing "Rescan library" Settings action --
    // included in both LaunchedEffect keys below so bumping it restarts
    // both collections against Library.rescanKindsProgressive instead of
    // the plain (cache-trusting) scanKindsProgressive. It is the user's
    // way to force a fresh scan without clearing app data.
    var rescanTrigger by remember { mutableStateOf(0) }
    // ONE answer to "where is the shell" (see ShellBackStack): the
    // section, the group drilled into inside Games, the open detail, and
    // what was focused in each. Held out here, outside the screens that
    // draw them, because a screen that owns its own place in the tree
    // loses it the moment something else is drawn instead (build 542:
    // "B from a game detail does not return to the PC grid"). Saveable rather than plain remember so the
    // Activity recreate the Text size setting triggers
    // (AccessibilityPrefs, Droidtop/tracker#87) restores this whole
    // place instead of dropping the user on the default section.
    val nav = rememberSaveable(saver = sessionOnly(ShellBackStack.Saver)) { ShellBackStack(GamingPrefs.defaultSection(context)) }
    val section = nav.section
    // Where the PC Games tab is (its view, its cursor, an open page), held
    // here for the same reason the back stack is: the tab is rebuilt
    // whenever another tab is shown (docs/SPEC.md 7i).
    val pcGames = rememberSaveable(saver = sessionOnly(dev.droidtop.shell.gamepad.pc.PcGamesState.Saver)) {
        dev.droidtop.shell.gamepad.pc.PcGamesState().apply { view = dev.droidtop.shell.gamepad.pc.PcView.OVERVIEW }
    }
    // Home's own place (its strip, its cursor), apart from PC Games' (Droidtop/tracker#611).
    val homeGames = rememberSaveable(saver = sessionOnly(dev.droidtop.shell.gamepad.pc.PcGamesState.Saver)) {
        dev.droidtop.shell.gamepad.pc.PcGamesState()
    }
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
    // What the focused element says the one footer should promise (see
    // [FocusedHints]); the footer falls back to the screen's own list.
    val focusedHints = remember { FocusedHints() }
    // What the screen on top has of its OWN to put in the help row (see
    // [HelpRowClaim]), and WHICH screen said so. A screen key rather than
    // a bare flag, because the screens cross over during the Crossfade
    // below and the one that is leaving must not answer for the one
    // arriving. A flag would let leaving Settings for Games reset the
    // answer to "nothing of its own" after the Games screen has claimed
    // the row, and droidtop's bar and the theme's own row would then draw
    // at once (rig, build 546, landscape).
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
    val detailEntry = remember(nav.detailId, gameEntries, appEntries, foldedGameEntries) {
        nav.detailId?.let { id ->
            // The folded row first: for a Switch base game it is the row
            // that knows the game's update and DLC; its id is the base
            // file's own, so the raw lookup behind it still finds the
            // same game when the fold has not run or said nothing.
            foldedGameEntries.orEmpty().firstOrNull { it.id == id }
                ?: gameEntries.orEmpty().firstOrNull { it.id == id }
                ?: appEntries.orEmpty().firstOrNull { it.id == id }
        }
    }
    // What A does on the open detail's focused element, reported by the
    // detail itself; null is the generic "Select". Reset per detail.
    var detailPrimaryLabel by remember(nav.detailId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // What the last launch left to say or fix ([LaunchProblems]).
    val launchProblems = remember { LaunchProblems() }
    // The game being started, if any: drives the launch screen (real
    // ES-DE has one; without it the shell simply freezes mid-frame for
    // the several seconds a cold emulator start takes, which reads as a
    // hang rather than as work).
    var launching by remember { mutableStateOf<LibraryEntry?>(null) }
    // Kiosk/Kid: read once per composition of the shell, and re-read
    // when the Quick Menu changes it (UiModeRefresh).
    val uiMode by dev.droidtop.library.settings.UiModeRefresh.mode.collectAsState()
    LaunchedEffect(Unit) { dev.droidtop.library.settings.UiModeRefresh.load(context) }
    // Idle tracking for the screensaver: every key press the shell sees
    // bumps this, and the timer below restarts from it. A launch counts
    // as activity too (the shell is not idle, it is behind a game).
    // A flow, not Compose state: only the timer observes it, so a key
    // press does not recompose the whole shell body. See SPEC
    // "Performance on the console".
    val lastInputMs = remember {
        kotlinx.coroutines.flow.MutableStateFlow(android.os.SystemClock.elapsedRealtime())
    }
    var screensaverOn by remember { mutableStateOf(false) }
    // Gaming's Animations switch and Android's animator scale (docs/SPEC.md
    // "Gaming motion and focus"), observed for as long as the shell is drawn.
    MotionSync()
    dev.droidtop.shell.gamepad.theme.SoundSync()
    // Observed, not read once: the row that sets it is in this shell's
    // own Settings section (see ScreensaverPrefs.changes).
    val screensaverMode by remember { dev.droidtop.library.settings.ScreensaverPrefs.changes(context) }
        .collectAsState(initial = dev.droidtop.library.settings.ScreensaverPrefs.mode(context))
    LaunchedEffect(uiMode) {
        if (uiMode.hidesSettings && section.managesDevice) {
            nav.openSection(GamingSection.GAMES)
        }
    }
    LaunchedEffect(screensaverMode, launching) {
        if (screensaverMode == dev.droidtop.library.settings.ScreensaverMode.OFF || launching != null) return@LaunchedEffect
        lastInputMs.collectLatest {
            kotlinx.coroutines.delay(screensaverMode.idleSeconds * 1000L)
            screensaverOn = true
        }
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
    // Which of the shell's own overlays is open ([ShellOverlays]).
    val overlays = remember { ShellOverlays() }
    LaunchedEffect(overlays.quickMenuOpen) { if (!overlays.quickMenuOpen) overlays.quickMenuStart = null }
    // A quick menu left open when this shell lost the foreground (the
    // Switch-mode dialog's own "Android"/"Desktop" rows, or Home) must not
    // still be showing when a fresh, real deep-link brings this same
    // singleTask composition back (rig, dq-modefix-01 steps 3-4).
    // MainActivity/GamepadShell is not destroyed by a mode round trip,
    // so without this reset the same composition resumes with the old
    // Quick Menu stacked on top of Gaming.
    // The left menu (Start; docs/SPEC.md 7j "Gaming controls") closes for
    // the same reason.
    // Coming back from a running game (Back from droidtop's own game screen, HOME or a wake while one runs) sets
    // openQuickMenu: with the game still running that opens its sheet, Resume first (Droidtop/tracker#740: what can
    // be done with the game is L2's, not R2's); with none, the Quick Menu as before.
    LaunchedEffect(deepLinkToken) {
        val gameRunning = dev.droidtop.library.LaunchDisplay.running.value != null
        overlays.runningGameOpen = openQuickMenu && gameRunning
        overlays.quickMenuOpen = openQuickMenu && !gameRunning
        overlays.leftMenuOpen = false
        Log.i("droidtop.ReturnToGame", "deep link $deepLinkToken applied: quickMenu=$openQuickMenu gameRunning=$gameRunning")
    }
    // The face-button layout of the pad in use (SPEC 7b, "Console and controller detection"),
    // followed live because a handheld's own toggle can flip it while this shell stays in front.
    // The check is an in-memory read and is repeated on every destination change, every menu and
    // dialog opening or closing, resume, and window-focus regain; the glyphs and the key map both
    // read ControllerLayouts.layout, so they change together and nothing else recomposes.
    LaunchedEffect(Unit) { ControllerLayouts.attach(context) }
    LaunchedEffect(nav.place.key, overlays.quickMenuOpen, overlays.leftMenuOpen, focusedHints.layerOpen) {
        ControllerLayouts.recheck(context)
    }
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        androidx.compose.runtime.snapshotFlow { windowInfo.isWindowFocused }.collect { focused ->
            if (focused) ControllerLayouts.recheck(context)
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) ControllerLayouts.recheck(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // "Press the button labelled A": asked for from the Quick Menu, or lightly when the pad's
    // earlier answer no longer holds and nothing else says what it is.
    if (ControllerLayouts.captureRequested || ControllerLayouts.needsRecapture) {
        PadCapturePrompt(light = !ControllerLayouts.captureRequested) {
            ControllerLayouts.endCaptureRequest()
        }
    }
    // Which page, if any, has claimed L1/R1 for a tab strip of its own.
    val shoulderStrips = remember { ShoulderStripRegistry() }
    // The two menus, for a window of its own that answers Start and R2 itself.
    // The two side menus are exclusive, as Steam's are: opening one closes the other (docs/SPEC.md 7j).
    val shellMenus = remember {
        ShellMenus(
            openLeft = {
                overlays.quickMenuOpen = false
                overlays.leftMenuOpen = true
            },
            openQuick = {
                overlays.leftMenuOpen = false
                overlays.quickMenuOpen = true
            },
        )
    }
    var displayChoice by remember {
        mutableStateOf<DisplayChoiceRequest?>(null)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        dev.droidtop.library.LaunchDisplay.chooser = { options, canRemember, onChosen ->
            displayChoice = DisplayChoiceRequest(options, canRemember, onChosen)
        }
        // The dispatch itself runs after the audio hand-off, so a failure
        // there arrives here rather than through library.launch.
        dev.droidtop.library.LaunchDisplay.onLaunchFailed = {
            launching = null
            launchProblems.error = LaunchFailureMessage.userMessage(null, it)
        }
        onDispose {
            dev.droidtop.library.LaunchDisplay.chooser = null
            dev.droidtop.library.LaunchDisplay.onLaunchFailed = null
        }
    }
    // Nothing may play beneath a modal layer (docs/SPEC.md "Launch audio hand-off", tracker#160): while any
    // layer on the hint bar's list is open (the screen question, sheets, menus, dialogs) the preview video is
    // paused and muted and the theme's navigation sounds are muted; it all comes back when the last layer
    // closes, whether it was answered or cancelled. A hand-off supersedes it (another app has the audio).
    LaunchedEffect(focusedHints) {
        kotlinx.coroutines.flow.combine(
            androidx.compose.runtime.snapshotFlow { focusedHints.layerOpen },
            dev.droidtop.runtime.AudioHandOff.handedOff,
            dev.droidtop.runtime.AudioHandOff::silenced,
        ).collectLatest { silenced ->
            dev.droidtop.runtime.AudioHandOff.setQuiet("modal layer open", silenced)
        }
    }
    displayChoice?.let { request ->
        LaunchDisplayChooserDialog(
            options = request.options,
            canRemember = request.canRemember,
            onPick = { option, rememberChoice ->
                displayChoice = null
                request.onChosen(option, rememberChoice)
            },
            onPickEveryGame = { option ->
                displayChoice = null
                // The built-in panel is the default display; any other screen offered is the second one.
                val target = if (option.displayId == android.view.Display.DEFAULT_DISPLAY) "BUILT_IN" else "SECOND"
                dev.droidtop.library.settings.GamingSettingsCatalog.setGameLaunchTarget(context, target)
                request.onChosen(option, false)
            },
            onCancel = {
                displayChoice = null
                dev.droidtop.runtime.AudioHandOff.mark("screen question cancelled")
            },
        )
    }

    // The Windows system-files offer's pending answer
    // (Droidtop/tracker#140): non-null while the offer is on screen, and
    // calling it IS the answer -- it resumes the consent ask
    // PcRunnerOptions.runAction is suspended on. The same
    // registered-hook shape as LaunchDisplay.chooser above, for the same
    // reason: library-core cannot reach a dialog, so the shell leaves a
    // hook here and renders the question itself. Installed only while
    // this composition is live; a process with no shell keeps the gate
    // open, because its callers (Settings' setup row, the Steam
    // sign-in's button) are themselves the explicit ask.
    var windowsSetupAnswer by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        dev.droidtop.library.PcRunnerOptions.windowsSetupConsent = {
            kotlinx.coroutines.suspendCancellableCoroutine { ask ->
                windowsSetupAnswer = { download ->
                    windowsSetupAnswer = null
                    // The isActive guard is LauncherSearch's rule: the
                    // answer fires once, and a second activation (or an
                    // answer racing the composition going away) must not
                    // resume a continuation that is no longer suspended.
                    if (ask.isActive) ask.resume(download)
                }
            }
        }
        onDispose { dev.droidtop.library.PcRunnerOptions.windowsSetupConsent = null }
    }
    windowsSetupAnswer?.let { answer ->
        WindowsSetupOfferDialog(
            onDownload = { answer(true) },
            onNotNow = { answer(false) },
        )
    }

    // The one-time "fetch box art?" question (docs/SPEC.md 7h). The walk that
    // finished may have been in an earlier process, so the stored question is
    // restored once, off the main thread.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { dev.droidtop.library.scraper.ScrapeOffer.restore(context) }
    }
    if (dev.droidtop.library.scraper.ScrapeOffer.pending.collectAsState().value) {
        ScrapeOfferDialog(
            onFetch = { dev.droidtop.library.scraper.ScrapeOffer.respond(context, fetch = true) },
            onNotNow = { dev.droidtop.library.scraper.ScrapeOffer.respond(context, fetch = false) },
        )
    }

    // "Your theme is ready" (Droidtop/tracker#532): a theme that finished downloading while nobody was choosing one is
    // offered here instead of replacing the look in use. An unanswered offer from an earlier process is restored.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { dev.droidtop.library.theme.ThemeOffer.restore(context) }
    }
    dev.droidtop.library.theme.ThemeOffer.pending.collectAsState().value?.let { offer ->
        ThemeReadyDialog(
            name = offer.name,
            onUse = { dev.droidtop.library.theme.ThemeOffer.respond(context, use = true) },
            onLater = { dev.droidtop.library.theme.ThemeOffer.respond(context, use = false) },
        )
    }

    // The actual dispatch: unchanged for every entry, PC and engine games
    // included -- once something has decided this game IS ready, it
    // launches exactly the same way a console ROM does.
    val dispatchLaunch: (LibraryEntry) -> Unit = { entry ->
        // Real ES-DE launch sound -- played unconditionally on any game
        // launch (ViewController.cpp:1064-1066 plays LAUNCHSOUND whether
        // or not a launch transition is configured), so it lives here in
        // the ONE launch handler rather than per key-handling site.
        //
        dev.droidtop.runtime.AudioHandOff.mark(
            "A pressed on a game: launch begins; open: ${dev.droidtop.runtime.AudioHandOff.openStreams()}",
        )
        EsDeNavigationSounds.play(UiSound.LAUNCH)
        scope.launch {
            launchProblems.error = null
            launching = entry
            // The audio hand-off happens where the app is actually
            // dispatched (LaunchDisplay.startOn), after the display
            // chooser, not here (tracker#160).
            runCatching { library.launch(entry) }
                .onFailure {
                    android.util.Log.e("droidtop.GamepadShell", "Launching ${entry.title} failed", it)
                    launching = null
                    launchProblems.missingEmulator = it as? dev.droidtop.library.consoles.NoEmulatorInstalled
                    launchProblems.unknownProgram = it as? dev.droidtop.library.ProgramNotIdentified
                    launchProblems.missingCore = it as? dev.droidtop.library.consoles.RetroArchCores.Missing
                    launchProblems.error = LaunchFailureMessage.userMessage(entry.title, it)
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
    // What A actually decides (docs/SPEC.md 7i, redecided 2026-09-26): a
    // PC or engine game resolves its runner first and either launches,
    // exactly like a console ROM, or runs the one setup action that would
    // make it ready -- the same decision PcGameMenu's own "Play"/"Set up"
    // row makes, now made once here so the gamelist's A and that row
    // never disagree. A console ROM or app has no runner to resolve, so
    // it dispatches straight through, unchanged.
    val onLaunch: (LibraryEntry) -> Unit = { entry ->
        if (entry.isPcOrEngineGame) {
            scope.launch {
                launchProblems.error = null
                launchProblems.progress = null
                dev.droidtop.library.PcRunnerOptions.resolveAndPlay(
                    context = context,
                    entry = entry,
                    onLaunch = { dispatchLaunch(entry) },
                    // Progress, not failure: the setup's own live lines,
                    // which stay until the attempt ends. A successful
                    // Windows setup ends with nothing more to say -- the
                    // next A press launches, exactly as the offer said.
                    onStatus = { message -> launchProblems.progress = message },
                    onFailure = { message ->
                        launchProblems.progress = null
                        launchProblems.error = message
                    },
                    // An installer's row installed (or said why not): a flash, not a failure.
                    onDone = { message -> dev.droidtop.library.settings.Notices.flash(message, context = context) },
                )
                launchProblems.progress = null
            }
        } else {
            dispatchLaunch(entry)
        }
    }

    // A job row's game page shown over the row that asked (docs/SPEC.md 7g, "Facets in place", Droidtop/tracker#639),
    // inside Gaming, so Back returns to the row. The page is the PC page; a game that has none is left to the caller.
    var inPlacePage by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        dev.droidtop.library.GameSurfaces.showPage = { id ->
            val pc = gameEntries?.firstOrNull { it.id == id }?.isPcOrEngineGame == true
            if (pc) inPlacePage = id
            pc
        }
        onDispose { dev.droidtop.library.GameSurfaces.showPage = null }
    }
    dev.droidtop.shell.gamepad.pc.GameInPlace(
        library = library,
        pageId = inPlacePage,
        onClosePage = { inPlacePage = null },
        onLaunch = onLaunch,
        onOpenDownloads = {
            inPlacePage = null
            nav.openSection(GamingSection.DOWNLOADS)
        },
    )

    // L2 on the running game leads with its sheet (Droidtop/tracker#740): Resume and Restart launch the shell's one way,
    // and a console game's search opens Gaming's.
    val runningGameHost = remember(library) {
        RunningGameHost(library, { entry -> onLaunch(entry) }, { id, name -> overlays.systemSearch = id to name })
    }

    // Which entry the running session (LaunchDisplay.running) is for,
    // resolved when a menu is open: the left menu's Resume row (docs/SPEC.md
    // 7j) and the Quick Menu's plugin panels read it. A game that ended unseen ends the
    // session in SessionMonitor, which this follows, so a menu never
    // offers a dead launch.
    val sideMenuOpen = overlays.quickMenuOpen || overlays.leftMenuOpen || overlays.runningGameOpen
    val runningSession by dev.droidtop.library.LaunchDisplay.running.collectAsState()
    val runningEntry = remember(sideMenuOpen, runningSession, gameEntries, appEntries) {
        if (!sideMenuOpen) {
            null
        } else {
            runningSession?.context?.gameId?.let { id ->
                gameEntries.orEmpty().firstOrNull { it.id == id } ?: appEntries.orEmpty().firstOrNull { it.id == id }
            }
        }
    }

    if (overlays.quickMenuOpen) {
        QuickMenu(
            runningEntry = runningEntry,
            startSection = overlays.quickMenuStart,
            onOpenLeftMenu = {
                overlays.quickMenuOpen = false
                overlays.leftMenuOpen = true
            },
            openPlace = { screenId ->
                placeForScreen(screenId, uiMode)?.let { place ->
                    overlays.quickMenuOpen = false
                    nav.openSection(place)
                } != null
            },
            placeAvailable = { screenId -> placeForScreen(screenId, uiMode) != null },
            onDismiss = {
                Log.i("droidtop.ReturnToGame", "Quick Menu closed")
                overlays.quickMenuOpen = false
            },
        )
    }
    if (overlays.leftMenuOpen) {
        LeftMenu(
            entries = leftMenuEntries(menuSectionsFor(uiMode)),
            current = section,
            runningTitle = runningEntry?.let { dev.droidtop.library.GameNaming.displayName(it.title) },
            // The Quick Menu Game section's Resume: the one launch path, reused.
            onResume = {
                overlays.leftMenuOpen = false
                runningEntry?.let(onLaunch)
            },
            onSelect = { entry ->
                overlays.leftMenuOpen = false
                selectSection(entry.section)
            },
            onOpenQuickMenu = {
                overlays.leftMenuOpen = false
                overlays.quickMenuOpen = true
            },
            // Kiosk and Kid keep the library and leave out what manages the device, downloads and settings with it.
            onSearch = if (menuSectionsFor(uiMode).any { it.managesDevice }) {
                {
                    overlays.leftMenuOpen = false
                    overlays.globalSearchOpen = true
                }
            } else {
                null
            },
            onDismiss = { overlays.leftMenuOpen = false },
        )
    }
    if (overlays.globalSearchOpen) {
        dev.droidtop.shell.gamepad.query.LauncherSearchScreen(
            initialText = "",
            games = gameEntries,
            // The apps are the shell's own list, matched in memory by the same text rule every list uses.
            findApps = { text ->
                appEntries.orEmpty().filter { !it.missing && dev.droidtop.shell.gamepad.query.matchesSearchText(it, text) }.take(20).map { app ->
                    dev.droidtop.shell.gamepad.query.LauncherSearchApp(
                        key = app.id,
                        title = dev.droidtop.library.GameNaming.displayName(app.title),
                        icon = null,
                        open = { onLaunch(app) },
                    )
                }
            },
            // Every game opens its page (Play or Install is there), never a raw start (docs/SPEC.md 12a).
            onOpen = { entry ->
                overlays.globalSearchOpen = false
                nav.openDetail(entry.id)
            },
            settingsRoot = remember(uiMode) { gamingSettingsRoot(menuSectionsFor(uiMode).filter { it.isPlace }.mapNotNull { it.placeScreenId }.toSet()) },
            onDismiss = { overlays.globalSearchOpen = false },
        )
    }
    if (overlays.runningGameOpen) {
        // The running game's sheet on coming back to it; Game options opens the game's own menu, as over the game.
        val entry = runningEntry
        var optionsOpen by remember(entry?.id) { mutableStateOf(false) }
        val close = { overlays.runningGameOpen = false }
        if (entry == null) {
            LaunchedEffect(runningSession) { if (runningSession == null) close() }
        } else {
            androidx.compose.runtime.CompositionLocalProvider(LocalRunningGameHost provides runningGameHost) {
                if (optionsOpen) {
                    GameOptionsOfRunningGame {
                        dev.droidtop.shell.gamepad.hosted.RunningGameOptions(
                            library = library,
                            entry = entry,
                            onResume = {
                                close()
                                onLaunch(entry)
                            },
                            onOpenDownloads = {
                                close()
                                nav.openSection(GamingSection.DOWNLOADS)
                            },
                            onClose = close,
                        )
                    }
                } else {
                    RunningGameSheet(
                        entry = entry,
                        onResume = {
                            close()
                            onLaunch(entry)
                        },
                        onGameOptions = { optionsOpen = true },
                        onDismiss = close,
                    )
                }
            }
        }
    }
    overlays.systemSearch?.let { (systemId, systemName) ->
        // One system's search (docs/SPEC.md 12a, "One search: where it opens"): its games, matched in memory over the
        // list already loaded, and the sources that serve it.
        // Missing games too, as the system's list shows them (searchablePool).
        val systemGames = remember(gameEntries, systemId) { gameEntries.orEmpty().filter { it.systemId == systemId } }
        dev.droidtop.shell.gamepad.query.LibrarySearchDialog(
            query = dev.droidtop.shell.gamepad.query.LibraryQuery(),
            matchCount = 0,
            totalCount = systemGames.size,
            onTextChange = {},
            onDismiss = { overlays.systemSearch = null },
            systemId = systemId,
            localKey = systemGames,
            local = { text ->
                systemGames.filter { dev.droidtop.shell.gamepad.query.matchesSearchText(it, text) }.take(20).map { game ->
                    dev.droidtop.library.integrations.LocalSearchRow(
                        game.id,
                        dev.droidtop.library.integrations.SearchRowKind.GAME,
                        dev.droidtop.library.GameNaming.displayName(game.title),
                        listOf(systemName, if (game.needsInstall()) "Not installed" else "Installed").joinToString(" · "),
                    ) {
                        overlays.systemSearch = null
                        // Its page, with Play or Install, as the global search does (docs/SPEC.md 12a).
                        nav.openDetail(game.id)
                    }
                }
            },
        )
    }
    // A store's cloud-save sync asks here when a game's saves differ (docs/SPEC.md 7g, "Stores").
    SaveConflictHost()
    // Anchor for [requestFocusWhenAttached] below -- attached to the
    // invisible Spacer in the content Box, never to the tab bar (owner,
    // 2026-09-27: the top bar must never be a D-pad focus target).
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
    // scanKindsProgressive, not scanKinds: the screen fills in as each
    // folder is scanned instead of waiting for the whole scan. Each
    // collected emission is already a growing snapshot (see
    // Library.scanKindsProgressive's own doc comment), so assigning it
    // directly is enough, and this file needs to know nothing about how
    // the scan is chunked.
    LaunchedEffect(library, rescanTrigger) {
        // Collected for as long as this shell is composed: see
        // scanFollowingGamesRoots for why a changed root set is a walk.
        library.scanFollowingGamesRoots(context, GAME_KINDS, rescan = rescanTrigger != 0)
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
    // A rescan never clears gameEntries/appEntries: the known library
    // stays on screen while the fresh walk runs. Bumping rescanTrigger
    // alone restarts the LaunchedEffects above against
    // rescanKindsProgressive, whose first emission is the already-known
    // cached data (see ConsoleRomProvider.rescanProgressive's own doc
    // comment), not an empty list. "Rescan library" itself lives in
    // SettingsGamingFragment (see MainActivity's own
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
            EsDeNavigationSounds.play(UiSound.FAVORITE)
            gameEntries = gameEntries?.map { if (it.id == entry.id) it.copy(favorite = newFavorite) else it }
        }
    }
    // onKeyEvent modifiers (L1/R1 section-switching below, GamesSection's
    // Left/Right sibling-system switching) only see a key event that
    // bubbles up from the focused node, so something must always have
    // focus, including on the first frame before `entries` loads and in
    // an empty section. Focus goes to the invisible anchor Spacer in the
    // content Box (never the tab bar: see its own comment, owner
    // 2026-09-27) on first composition; GamesSection/AppsSection move
    // focus onto real content once it loads.
    LaunchedEffect(Unit) { requestFocusWhenAttached(tabBarFocus, "Shell anchor") }

    // Outermost back swallow: droidtop is the HOME surface -- system back
    // (which this hardware's B button doubles as) at the shell's top level
    // must never finish the Activity and drop the user into whatever app
    // happened to be behind the launcher (confirmed live, per report).
    // Composed FIRST, so every deeper BackHandler (detail close, drill-up)
    // registers later on the dispatcher and takes precedence while active.
    androidx.activity.compose.BackHandler(enabled = true) {
        // Home is the hub and the top of the shell, where back goes nowhere
        // (as on any Android home screen). From any other section whose own
        // handlers have nothing left to close, back returns to Home.
        if (GamingSection.HOME in menuSectionsFor(uiMode) && section != GamingSection.HOME) {
            selectSection(GamingSection.HOME)
        }
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
        padDriving = dev.droidtop.shell.gamepad.input.PadModality.showsFocus,
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShellWindow provides shellWindow,
        LocalHelpRowOwner provides helpRowOwner,
        LocalShoulderStrips provides shoulderStrips,
        LocalFocusedHints provides focusedHints,
        LocalShellMenus provides shellMenus,
        LocalHelpRowSlotReport provides { slot -> helpRowSlotReport = currentScreenKey to slot },
        LocalRunningGameHost provides runningGameHost,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .groundBackground()
            // Shoulder-button section switching -- a standard console-UI
            // pattern (Daijishō and most console launchers use L1/R1 to
            // cycle top-level tabs) that works regardless of what currently
            // has focus, unlike D-pad navigation up into the tab bar. Only
            // active when nothing has already consumed the event (detail
            // screen's own Back handling, individual card key handlers,
            // etc. all take priority since they're closer to the focused
            // node in the bubbling chain).
            // Touch counts as activity too, so the screensaver never
            // appears while somebody is tapping. Initial pass, so it
            // observes without consuming anything.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        lastInputMs.value = android.os.SystemClock.elapsedRealtime()
                    }
                }
            }
            // Outermost, so it sees only what nothing below wanted: the
            // shell owns the pad, and Android's generic fallbacks never
            // act on it (Modifier.ownPadButtons).
            .ownPadButtons { backDispatcher?.onBackPressed() }
            // What the whole shell means by a press, wherever it is: the
            // two menus and the shoulders. Closest to the content, so a
            // screen that wants one of these presses for itself takes it
            // first (docs/SPEC.md 6e).
            .onPad { press ->
                when (press.action) {
                    // R2, the Quick Menu's own button (named by the R2 Quick
                    // Menu pill in the footer); a held Select arrives here
                    // as R2 too, made by the pipeline's front (PadGate) for
                    // pads whose triggers send no key. The press opens; its
                    // release is this owner's and goes nowhere, and a fresh
                    // press inside the menu closes it there.
                    GamepadAction.R2 -> {
                        if (!overlays.quickMenuOpen) overlays.quickMenuOpen = true
                        true
                    }
                    // Start, the left menu's button: navigation, where the
                    // Quick Menu is quick management (docs/SPEC.md 7j,
                    // "Gaming controls"). Only this window ever sees it:
                    // once a launched game is in front the shell is not
                    // the foreground and receives no input.
                    GamepadAction.START -> {
                        if (!overlays.leftMenuOpen) overlays.leftMenuOpen = true
                        true
                    }
                    // L1/R1 step the page's own nearest strip only. A page
                    // without one leaves these presses unhandled.
                    GamepadAction.L, GamepadAction.R -> {
                        val step = if (press.action == GamepadAction.L) -1 else 1
                        when (shoulderRoute(stripOwned = shoulderStrips.current != null, detailOpen = detailEntry != null)) {
                            ShoulderRoute.STRIP -> {
                                shoulderStrips.current?.step?.invoke(step)
                                true
                            }
                            ShoulderRoute.NONE -> false
                        }
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    lastInputMs.value = android.os.SystemClock.elapsedRealtime()
                    if (screensaverOn) {
                        // The press that wakes the shell belongs to the
                        // screensaver, not to whatever it was over.
                        screensaverOn = false
                        return@onKeyEvent true
                    }
                }
                false
            },
    ) {
        // Live setup progress (Droidtop/tracker#140): the launch path's
        // own lines about what a Windows setup is fetching, drawn as
        // chrome rather than as the failure banner below -- a
        // several-minute download painted red is exactly how the
        // system-files setup came to read as an error nobody asked for.
        // No self-dismiss: the line stays honest for as long as the
        // attempt runs, and onLaunch clears it when the attempt ends.
        launchProblems.progress?.let { message ->
            Text(
                message,
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MenuTokens.HintBar)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // Launch failure (see onLaunch's crash boundary): a focused
        // dialog, not a banner (Droidtop/tracker#171) -- the shell stays
        // visible behind it, and the pad's B, a row tap or a tap outside
        // dismisses it; a failure the user can fix offers the fix on the
        // spot instead of getting out of the way.
        launchProblems.error?.let { message ->
            LaunchFailureDialog(
                message = message,
                actions = listOfNotNull(launchProblems.missingEmulator?.let { problem ->
                    LaunchFailureAction("Get an emulator") {
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
                        launchProblems.missingEmulator = null
                        launchProblems.error = null
                    }
                }, launchProblems.unknownProgram?.let { problem ->
                    // The game's program choice, the same screen its options open.
                    LaunchFailureAction("Choose the program") {
                        launchProblems.programScreen = dev.droidtop.library.WindowsPrograms.screen(problem.entryId, problem.title, problem.gameRoot)
                        launchProblems.unknownProgram = null
                        launchProblems.error = null
                    }
                }, launchProblems.missingCore?.let { problem ->
                    // The launch watchdog's Get the core, here before anything started: RetroArch's Core
                    // Downloader, or droidtop placing it once the person confirms on the emulator screen.
                    LaunchFailureAction("Get the core") {
                        launchProblems.missingCore = null
                        launchProblems.error = null
                        scope.launch {
                            when (val outcome = dev.droidtop.library.consoles.RetroArchCores.ensure(context, problem.need, asked = true)) {
                                dev.droidtop.library.consoles.RetroArchCores.Outcome.Ready -> Unit
                                is dev.droidtop.library.consoles.RetroArchCores.Outcome.Manual -> launchProblems.error = outcome.line
                                is dev.droidtop.library.consoles.RetroArchCores.Outcome.Failed -> launchProblems.error = outcome.line
                            }
                        }
                    }
                }),
                onDismiss = {
                    launchProblems.missingEmulator = null
                    launchProblems.unknownProgram = null
                    launchProblems.missingCore = null
                    launchProblems.error = null
                },
            )
        }
        launchProblems.programScreen?.let { screen -> CatalogSheet(root = screen, onExit = { launchProblems.programScreen = null }) }
        // A launch that never answered or left at once (LaunchWatchdog, docs/SPEC.md "The launch
        // watchdog"): plain words and the three ways out, never a black screen.
        val watchAlert by dev.droidtop.library.LaunchWatchdog.alert.collectAsState()
        watchAlert?.let { alert ->
            LaunchFailureDialog(
                message = alert.message,
                actions = listOf(
                    LaunchFailureAction("Close it") {
                        scope.launch {
                            val outcome = dev.droidtop.library.LaunchWatchdog.closeIt(context, alert)
                            if (outcome !is dev.droidtop.runtime.tasks.CloseOutcome.Closed) {
                                dev.droidtop.library.LaunchWatchdog.dismiss()
                                launchProblems.error = outcome.text
                            }
                        }
                    },
                    LaunchFailureAction("Return to droidtop") {
                        dev.droidtop.library.LaunchWatchdog.returnToShell(context)
                        dev.droidtop.library.LaunchWatchdog.dismiss()
                    },
                ).let { ways ->
                    // A stuck RetroArch whose core is not confirmed installed: getting the core is the likely fix, so
                    // it comes first.
                    val core = alert.retroArchCore?.let {
                        LaunchFailureAction("Get the core") {
                            scope.launch {
                                dev.droidtop.library.LaunchWatchdog.getCore(context, alert)?.let { launchProblems.error = it }
                            }
                        }
                    }
                    listOfNotNull(core) + ways
                },
                detail = "What droidtop saw is written to ${alert.logPath}",
                onDismiss = dev.droidtop.library.LaunchWatchdog::dismiss,
            )
        }
        // ONE definition of the shell's help row, placed in one of two
        // ways: over a themed screen, which laid a help row out itself,
        // and under everything else. Real ES-DE draws its one
        // HelpComponent ON the view at the theme's own <helpsystem>
        // position (Window.cpp:126, :884); droidtop's own bar takes that
        // same place rather than a strip of its own below a canvas
        // shortened to make room for it, so a theme's own help plate is
        // never drawn empty above the bar and a theme gets the same canvas
        // in portrait as in landscape (rig, build 547).
        // A screen drawn over the group -- a game's detail, the group's
        // own options screen -- takes A and B and nothing else: Info,
        // Options, the system jump and the section switch all act on the
        // group UNDER it, so the bar does not promise them there (rig,
        // build 550). The hint row promises only what dispatches (SPEC 7j).
        val overlayScreen = detailEntry != null || nav.optionsOpen
        // Y/Info is bound only where Y acts on something focused (SPEC
        // 7j: a hint row promises only what dispatches; UI pass
        // 2026-09-24, H8): on the selected row in Settings (the
        // catalog's own Y opens its Info sheet), on the focused Apps
        // tile -- and an empty or still-loading Apps grid has none --
        // and in an open GAMES gamelist. Never over the GAMES carousel:
        // the focused thing there is a system and Y dispatches nothing,
        // the themed system view's own hint list already drops it for
        // that reason (see systemListHints), and the shell's own row --
        // which draws over that same canvas on a touch-first window and
        // always on the unthemed fallback -- had kept promising "Y Info"
        // to a button that did nothing.
        val infoBound = !overlayScreen && when (section) {
            GamingSection.GAMES -> canGoBack
            // The PC Games tab draws its own row (HelpRowClaim.SCREEN);
            // the shell's bar shows there only over its setup screen.
            GamingSection.HOME, GamingSection.PC_GAMES -> false
            GamingSection.APPS -> !appEntries.isNullOrEmpty()
            GamingSection.SETTINGS,
            GamingSection.STORES,
            GamingSection.SOCIAL,
            GamingSection.DOWNLOADS,
            GamingSection.UPDATES,
            GamingSection.NEW_GAMES,
            GamingSection.PLUGINS,
            -> true
        }
        val shellHelpRow: @Composable (Color) -> Unit = shellHelpRow@{ background ->
            if (screensaverOn) return@shellHelpRow
            ButtonHintFooter(
                background = background,
                // A names what it does on a detail's primary button
                // ("A Play"). In Retro Games A goes into what the cursor is
                // on (a system, a game's page): "Open", the verb Home and
                // the left menu use for it (Droidtop/tracker#681). "Select"
                // is left for where A chooses.
                aLabel = detailPrimaryLabel.takeIf { detailEntry != null }
                    ?: if (!overlayScreen && section == GamingSection.GAMES) "Open" else "Select",
                canGoBack = canGoBack || overlayScreen,
                showInfo = infoBound,
                showSystemSwitch = !overlayScreen && section == GamingSection.GAMES && canGoBack,
                showOptions = !overlayScreen && section == GamingSection.GAMES,
            )
        }
        Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            // A zero-size, invisible focus anchor -- not the top bar
            // (owner, 2026-09-27: the top bar itself must never be a
            // D-pad focus target). Real content steals focus the
            // moment it has any (GamesSection/AppsSection/etc. each
            // call requestFocusWhenAttached on their own first row);
            // this exists only so shell-level pad actions still fire
            // on the very first frame or a genuinely empty library,
            // where nothing else has focus yet to bubble the key
            // event up from (see tabBarFocus's own comment).
            Spacer(Modifier.size(0.dp).focusRequester(tabBarFocus).focusable())
            // ONE transition for every screen the shell itself draws,
            // including a game's detail opened out of a themed gamelist
            // (research/ui-polish item 18). The theme's own inter-view transitions stay the
            // theme's: this is keyed on the section and the open detail,
            // not on the group, so a move between systems is still ES-DE's
            // own animation and not two animations at once.
            androidx.compose.animation.Crossfade(
                targetState = currentScreenKey,
                animationSpec = Motion.screen(),
                label = "shell screen",
                modifier = Modifier.fillMaxSize(),
            ) { screenKey ->
                // Read out of the KEY, not out of the live state: the copy
                // that is leaving has to keep drawing the screen it was, or
                // this is a dim rather than a transition.
                val entry = detailEntry?.takeIf { it.id == screenKey }
                val shownSection = GamingSection.entries.firstOrNull { "section:${it.name}" == screenKey } ?: section
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // While the launch screen covers the content, the screen under it
                        // keeps its place but takes no presses; only what means Back gets through (the
                        // pad's cancel button with the face-button swap applied, Escape, Back: FaceKeys),
                        // to leave a launch that never produces a window.
                        .onPreviewKeyEvent { event ->
                            launching != null && !org.pocketworkstation.pckeyboard.FaceKeys.isCancel(event.nativeKeyEvent.keyCode)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        screensaverOn -> {
                            Screensaver(foldedGameEntries.orEmpty()) {
                                screensaverOn = false
                                lastInputMs.value = android.os.SystemClock.elapsedRealtime()
                            }
                            androidx.activity.compose.BackHandler(enabled = true) { screensaverOn = false }
                        }
                        // The loading spinner gates only the sections that need scan
                        // data: Settings is a static list and never waits on a scan.
                        // detailEntry is section-independent, so it is checked
                        // before the loading gate too.
                        // A PC or engine game does not open a detail screen of
                        // its own (docs/SPEC.md 7i, redecided 2026-09-26): its
                        // metadata is the theme's own gamelist elements and its
                        // actions are PcGameMenu, an in-context overlay opened
                        // from the gamelist itself (L2/Y), so this branch is
                        // console ROMs and native apps only, as EntryDetailScreen
                        // has always been.
                        entry != null -> EntryDetailScreen(
                            entry = entry,
                            library = library,
                            onLaunch = { onLaunch(entry); nav.back() },
                            onClose = { nav.back() },
                            onPrimaryFocus = { detailPrimaryLabel = it },
                            // An edited Sort name, title or favourite shows in the list at once, as a favourite's toggle does.
                            onEntryChanged = { changed -> gameEntries = gameEntries?.map { if (it.id == changed.id) changed else it } },
                        )
                        // Get new games (Droidtop/tracker#612, #621): a page of Updates that opens on what is
                        // new for the person, with Search first, never on a blank search bar; leaving it goes back to
                        // Updates.
                        shownSection == GamingSection.NEW_GAMES -> {
                            canGoBack = true
                            GetNewGamesPage(
                                entries = foldedGameEntries.orEmpty(),
                                onOpenUpdates = { nav.openSection(GamingSection.UPDATES) },
                                // Get new games is a row of Downloads and Updates' Get games now
                                // (Droidtop/tracker#749).
                                onBack = { nav.openSection(GamingSection.DOWNLOADS) },
                            )
                        }
                        // The left menu's places: a registered settings screen each,
                        // in place (docs/SPEC.md 7j "Places"). Back is always
                        // something here too, for the reason Settings' is.
                        shownSection.placeScreenId != null -> {
                            canGoBack = true
                            // Downloads and Updates' Get games row opens the one search here, over the place (Droidtop/tracker#825).
                            var getGamesOpen by remember { mutableStateOf(false) }
                            // Every registered store's Open library and holding rows, built in or plugged in,
                            // and Game sources' Wine shortcuts and Imported rows: PC Games, filtered.
                            val storeLibraries = remember {
                                fun openGames(show: () -> Unit): () -> Unit = {
                                    show()
                                    nav.openSection(GamingSection.PC_GAMES)
                                }
                                buildMap<String, () -> Unit> {
                                    for (store in dev.droidtop.library.stores.StoreLibraries.all()) {
                                        put("${PcSource.LIBRARY_ITEM_PREFIX}${store.id}", openGames { pcGames.showSource(store.id) })
                                        for (holding in dev.droidtop.library.stores.StoreHolding.entries) {
                                            put(PcSource.holdingItemId(store.id, holding), openGames { pcGames.showSource(store.id, holding) })
                                        }
                                    }
                                    val wine = PcSource.WineShortcut.id
                                    put("${PcSource.LIBRARY_ITEM_PREFIX}$wine", openGames { pcGames.showSource(wine) })
                                    put(PcSource.IMPORTED_ITEM_ID, openGames { pcGames.showImported() })
                                    // Get games' Get new games row opens the search here, in place.
                                    put(dev.droidtop.library.integrations.GetGamesEntry.UPDATES_ROW_ID) { nav.openSection(GamingSection.NEW_GAMES) }
                                    // Downloads and Updates' "Updates · N available" row opens the Updates place, the one page; as a
                                    // link of its own it was a second Updates entry in the place's column (Droidtop/tracker#837).
                                    put(dev.droidtop.library.integrations.PluginJobsScreen.UPDATES_LINK_ID) { nav.openSection(GamingSection.UPDATES) }
                                    put(dev.droidtop.library.integrations.GetGamesEntry.SEARCH_ROW_ID) { getGamesOpen = true }
                                }
                            }
                            if (getGamesOpen) GetGamesSearch { getGamesOpen = false }
                            PlaceCatalogView(
                                screenId = shownSection.placeScreenId.orEmpty(),
                                // A page of another place goes back to that place (Updates to Downloads and Updates).
                                onBack = {
                                    nav.openSection(if (shownSection.menuSection != shownSection) shownSection.menuSection else GamingPrefs.defaultSection(context))
                                },
                                nativeActions = storeLibraries,
                            )
                        }
                        shownSection == GamingSection.SETTINGS -> {
                            // Back always does something in Settings: it pops a
                            // nested screen, or leaves Settings for the default
                            // section. So the footer always shows the B hint, which
                            // is the touch route to B (design language: "the
                            // help/hint row is the touch route to pad buttons"):
                            // without it a nested settings screen has no way out
                            // that a finger can reach.
                            canGoBack = true
                            SettingsCatalogView(
                                onBack = { nav.openSection(GamingPrefs.defaultSection(context)) },
                                browseThemesToken = browseThemesRequest,
                                // The places the left menu leads to, and their pages (Updates, of Downloads and
                                // Updates):
                                // the menu is their way in. A place off the left menu (Plugins, Social, Game sources)
                                // keeps
                                // its Settings row, or it had none (Droidtop/tracker#759: Plugins, and with it every
                                // plugin's Approve, could not be found).
                                placeScreenIds = settingsHiddenPlaceScreens(uiMode),
                                onHelpRowClaim = { claim ->
                                    if (screenKey == currentScreenKey) {
                                        helpRowClaimant = screenKey to claim
                                    }
                                },
                            )
                        }
                        // Each section now gates on its own scan only (see
                        // gameEntries/appEntries' own comment) -- Games' spinner no
                        // longer has anything to do with whether Apps is ready, and
                        // vice versa.
                        (shownSection == GamingSection.GAMES || shownSection == GamingSection.PC_GAMES || shownSection == GamingSection.HOME) &&
                            (gameEntries == null || foldedGameEntries == null) -> ShellLoading()
                        shownSection == GamingSection.APPS && appEntries == null -> ShellLoading()
                        else -> when (shownSection) {
                            // Retro Games: the console systems, themed.
                            // Every PC and engine game is on the PC Games
                            // tab instead (LibraryEntry.onPcGamesTab, one
                            // rule for both tabs, docs/SPEC.md 7i).
                            GamingSection.GAMES -> GamesSection(
                                entries = foldedGameEntries.orEmpty().let { all ->
                                    if (uiMode.kidGamesOnly) all.filter { it.kidGame } else all
                                }.filterNot { it.onPcGamesTab },
                                library = library,
                                nav = nav,
                                onLaunch = onLaunch,
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
                            // Home and PC Games draw the same shelves surface, each with its own state; keyed, so
                            // nothing one remembers inside it leaks into the other.
                            GamingSection.HOME, GamingSection.PC_GAMES -> androidx.compose.runtime.key(shownSection) { dev.droidtop.shell.gamepad.pc.PcGamesSection(
                                entries = foldedGameEntries.orEmpty().let { all ->
                                    if (uiMode.kidGamesOnly) all.filter { it.kidGame } else all
                                }.filter { it.onPcGamesTab },
                                // Home's Continue playing and Recently added also take the Retro
                                // library and the launcher apps (docs/SPEC.md 7i, "Home art").
                                retro = remember(foldedGameEntries, uiMode.kidGamesOnly) {
                                    foldedGameEntries.orEmpty().let { all ->
                                        if (uiMode.kidGamesOnly) all.filter { it.kidGame } else all
                                    }.filterNot { it.onPcGamesTab }
                                },
                                apps = if (uiMode.kidGamesOnly) emptyList() else appEntries.orEmpty(),
                                library = library,
                                onHomeActivityChanged = onHomeActivityChanged,
                                state = if (shownSection == GamingSection.HOME) homeGames else pcGames,
                                onLaunch = onLaunch,
                                onToggleFavorite = onToggleFavorite,
                                onShowDetail = { nav.openDetail(it.id) },
                                onFocusedEntryChanged = onFocusedEntryChanged,
                                onHelpRowClaim = { claim ->
                                    if (screenKey == currentScreenKey) {
                                        helpRowClaimant = screenKey to claim
                                    }
                                },
                                onCanGoBackChanged = { canGoBack = it },
                                onRequestRescan = { rescanTrigger++ },
                                onOpenSection = { target -> selectSection(target) },
                                onOpenQuickMenu = { overlays.quickMenuOpen = true },
                            ) }
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
                            GamingSection.SETTINGS,
                            GamingSection.STORES,
                            GamingSection.SOCIAL,
                            GamingSection.DOWNLOADS,
                            GamingSection.UPDATES,
                            GamingSection.NEW_GAMES,
                            GamingSection.PLUGINS,
                            -> Unit
                        }
                    }
                    // Starting a game owns the content area until the game's own window
                    // arrives, drawn OVER the screen the launch was triggered from rather
                    // than instead of it: taking that screen out of the composition threw
                    // away its focus, so the cursor came back on the first tile after the
                    // launch screen, the screen chooser's Back included (tracker#250).
                    launching?.let { starting ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                                    }
                                },
                        ) { LaunchScreen(starting) }
                        // A launch that never produces a window must
                        // never trap the shell behind this.
                        androidx.activity.compose.BackHandler(enabled = true) { launching = null }
                    }
                }
            }
            // Float status over the page rather than reserving a header
            // band; it is a readout and the tap route to the Quick Menu.
            // On Retro Games it yields to a theme that draws its own clock
            // or system status in the view on screen (docs/SPEC.md 7k2), so
            // the two never overlap; the system list and a gamelist are
            // decided apart (Droidtop/tracker#658).
            val retroView = if (canGoBack) "gamelist" else "system"
            // Home's top band draws the status facts itself (docs/SPEC.md 7i, "Home"; Droidtop/tracker#494, #485 F7a).
            val homeBand = section == GamingSection.HOME && nav.place is ShellPlace.Section
            if (!screensaverOn && !homeBand && !(section == GamingSection.GAMES && retroView in GamingTheme.palette.ownStatusViews)) {
                FloatingStatusCluster(
                    onClick = { overlays.quickMenuOpen = true },
                    // The activity spot opens where the jobs are: the Quick Menu's Downloads tab
                    // (Droidtop/tracker#742).
                    onActivity = {
                        overlays.quickMenuStart = QuickSection.DOWNLOADS
                        overlays.quickMenuOpen = true
                    },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            // A theme that draws its own help legend (a pad is attached, so
            // the legend is the theme's) names nothing of the two menus and
            // is not tappable: the Start and R2 pills sit in the corner
            // beside it (docs/SPEC.md 7j, "Gaming controls").
            if (helpRowOwner == HelpRowOwner.THEME && !screensaverOn) {
                // Each pill carries its own plate (TouchHintBar over a theme), so the pair needs none of its own.
                ShellMenuPills(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = shellWindow.edgePadding, bottom = 4.dp),
                )
            }
            // Over the themed canvas, at the place the theme itself laid
            // out for a help row, rather than under a canvas shortened to
            // make room for it (rig, build 548): the bar sits at the
            // theme's own help position, not the bottom edge, and paints
            // no plate of its own over the theme's art. ES-DE's
            // HelpComponent draws text on the view and no plate at all, so
            // neither does droidtop's bar when it is the one on a themed
            // view.
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
            shellHelpRow(MenuTokens.HintBar)
        }
    }
    }
}

/**
 * Marks this process. Saved shell state from an earlier process (a cold
 * start) is dropped so Gaming opens on its default, Home; a recreate in
 * this process (the Text size setting) keeps the user's place.
 */
internal val PROCESS_MARK: Long = System.currentTimeMillis()

/**
 * Whether the Activity that was just destroyed was destroyed for a
 * configuration change (a recreate or a rotation), the only time the shell
 * restores its place. The host sets it in `onDestroy`
 * (`isChangingConfigurations`). Any other destroy with the process still
 * alive (Android freeing a backgrounded Activity) leaves it false, so the
 * next start is a cold one and opens on Home, not on the last view
 * (Droidtop/tracker#361). False until the host says otherwise.
 */
object ShellRestore {
    @Volatile
    var keepPlace: Boolean = false
}

internal fun <T : Any> sessionOnly(inner: Saver<T, Any>): Saver<T, Any> = Saver(
    save = { value -> with(inner) { save(value) }?.let { listOf(PROCESS_MARK, it) } },
    restore = { saved ->
        val parts = saved as? List<*>
        if (parts != null && parts.getOrNull(0) == PROCESS_MARK && ShellRestore.keepPlace) parts.getOrNull(1)?.let { inner.restore(it) } else null
    },
)

/**
 * The Gaming shell's reads of its own settings. [GamingSettingsCatalog]
 * owns the keys, defaults and every write (both the in-shell settings and
 * Standard's SettingsGamingFragment render that catalog); this only reads
 * them, from the same file ([CatalogPrefs]).
 */
internal object GamingPrefs {
    private fun prefs(context: Context) = CatalogPrefs.prefs(context)

    /** The section Gaming opens on: Home (the default), PC Games ("pc"), Retro Games or Apps. */
    fun defaultSection(context: Context): GamingSection =
        when (prefs(context).getString(GamingSettingsCatalog.ID_DEFAULT_SECTION, "home")) {
            "apps" -> GamingSection.APPS
            "games" -> GamingSection.GAMES
            "pc" -> GamingSection.PC_GAMES
            else -> GamingSection.HOME
        }

    fun showHints(context: Context): Boolean =
        prefs(context).getBoolean(GamingSettingsCatalog.ID_SHOW_HINTS, true)

    // Deliberately separate from :shell-default's own drawer grid-width
    // override (SettingsDrawerFragment's GRID_SIZE_WIDTH_DRAWER_OVERRIDE):
    // that one sizes Standard's app drawer, a different view with its own
    // icon size needs.
    fun appsGridColumns(context: Context): Int =
        prefs(context).getInt(
            GamingSettingsCatalog.ID_APPS_GRID_COLUMNS,
            GamingSettingsCatalog.DEFAULT_APPS_GRID_COLUMNS,
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
internal fun ButtonHintFooter(
    aLabel: String = "Select",
    canGoBack: Boolean,
    showInfo: Boolean,
    showSystemSwitch: Boolean = false,
    showOptions: Boolean = false,
    background: Color = MenuTokens.HintBar,
) {
    // One mechanism per row, shared with every screen's own hints
    // (input/HintBindings.kt): each promise is a HintBinding gated on
    // the condition under which that action really dispatches here, and
    // the row keeps only the ones that hold -- never a hand-built list
    // that can drift from what the key handlers above actually bind.
    FocusedHintRow(
        background = background,
        leading = ShellMenuHints,
        fallback = listOf(
            HintBinding(GamepadAction.A, aLabel),
            HintBinding(GamepadAction.Y, "Info") { showInfo },
            // B is the hint row's own touch route to back.
            HintBinding(GamepadAction.B, "Back") { canGoBack },
            // Gamelist options (sort/scrape/import for where you are)
            // were on Select and named nowhere on screen: with no pad
            // attached they were unreachable, and with one they were
            // undiscoverable.
            HintBinding(GamepadAction.SELECT, "List options") { showOptions },
            // ES-DE's own documented "General navigation": Left/Right
            // inside a gamelist jump to the adjacent system rather than
            // going back and reselecting. Named as two separate hints
            // rather than one compound arrow glyph, because each has to
            // be tappable on its own.
            HintBinding(GamepadAction.LEFT, "Previous system") { showSystemSwitch },
            HintBinding(GamepadAction.RIGHT, "Next system") { showSystemSwitch },
            // L1/R1 are named by a page's own strip when one exists.
        ),
    )
}

/**
 * The shell's tabs. Two game tabs since 2026-10-01 (docs/SPEC.md 7i, the
 * owner: "having two games tabs instead of one might be better"): [GAMES]
 * is Retro Games, the ES-DE-themed console library; [PC_GAMES] is
 * droidtop's own Steam-like library of every PC and engine game, which no
 * theme draws. The enum name GAMES is kept so a saved place and the
 * Settings deep link that name it keep working.
 */
internal enum class GamingSection(val place: dev.droidtop.library.settings.Place? = null) {
    /**
     * Home, a section of its own (owner, 2026-10-23: "Home should NOT be the same section. Home should be dynamic",
     * Droidtop/tracker#611). It was PC Games in another view, sharing one state, so visiting Home threw away where
     * PC Games was (its grid view, its cursor) and the other way round. It draws the same shelves surface with a
     * state of its own.
     */
    HOME,
    GAMES, PC_GAMES, APPS, SETTINGS,

    /**
     * The places things live (docs/SPEC.md 7j, "Places", Droidtop/tracker#258):
     * reached from the left menu only, never as top-bar tabs, each one a
     * registered settings catalog screen drawn in place ([PlaceCatalogView]).
     * Which places there are is the one list every mode reads
     * ([dev.droidtop.library.settings.Place], Droidtop/tracker#346).
     */
    STORES(dev.droidtop.library.settings.Place.STORES),
    SOCIAL(dev.droidtop.library.settings.Place.SOCIAL),
    DOWNLOADS(dev.droidtop.library.settings.Place.DOWNLOADS),
    UPDATES(dev.droidtop.library.settings.Place.UPDATES),

    /**
     * Get new games: the one search over the download sources, on its own (owner, 2026-10-23: "add a 'get new games'
     * under 'updates' that's its own UI", Droidtop/tracker#612). A page of Updates, opened by its Get new games row,
     * not a row of the left menu (owner, 2026-10-23: "THe "get new games" menu needs to be UNDER Updates. The left menu
     * is growing really large", Droidtop/tracker#621). Back returns to Updates, itself a page of Downloads and Updates
     * (Droidtop/tracker#639), which the left menu marks.
     */
    NEW_GAMES,
    PLUGINS(dev.droidtop.library.settings.Place.PLUGINS),
    ;

    val isPlace: Boolean get() = place != null

    /** A place or Settings: device management, which Kiosk and Kid hide. */
    val managesDevice: Boolean get() = this == SETTINGS || isPlace

    /** The settings-registry screen a place draws, null for a tab with a view of its own. */
    val placeScreenId: String? get() = place?.screenId

    /**
     * The left menu row that stands for this section: itself, or Downloads and Updates for Updates and Get new games,
     * its pages (Droidtop/tracker#639).
     */
    val menuSection: GamingSection get() = if (this == NEW_GAMES || this == UPDATES) DOWNLOADS else this

    /**
     * A place that is no row of Gaming's left menu (Droidtop/tracker#644 slice 3). Plugins and Social are sections of
     * the Quick Menu: Plugins' management is the last row of its section (owner, 2026-10-17, Droidtop/tracker#586:
     * "The plugin menu on the left pretty much shouldn't exist, the quick menu should be for lots of that stuff";
     * 2026-10-24: "move ALL the plugin menu stuff to the QAM plugins menu"), and Social is drawn in its section
     * (owner, 2026-10-24: "Game sources go into the qam, as does social"). Game sources is no destination: getting
     * games and each source's standing lead Downloads and Updates, and setting one up is Settings > Accounts and
     * sources. A link to any of them (a notification, that Settings row) still opens it.
     */
    val offLeftMenu: Boolean get() = this == PLUGINS || this == SOCIAL || this == STORES
}

// Registry ids of the screens the places draw, from the one place list.
internal const val PLACE_SOCIAL_SCREEN_ID = dev.droidtop.library.settings.Place.ID_SOCIAL
internal const val PLACE_UPDATES_SCREEN_ID = dev.droidtop.library.settings.Place.ID_UPDATES
internal const val PLACE_PLUGINS_SCREEN_ID = dev.droidtop.library.settings.Place.ID_PLUGINS

/**
 * The place a settings screen id IS, when [mode] lets a link open it ([dev.droidtop.library.settings.Place.reachable]:
 * a mode that hides Settings hides the places). A link to such a screen opens the place in the shell instead of a
 * second copy of the screen inside a sheet, so a screen has one home (docs/SPEC.md 7j "Places"). That covers the
 * places the left menu does not list: Updates, a page of Downloads and Updates, and Plugins, kept in the Quick Menu.
 */
/**
 * The places whose Settings row is left out in [mode] because the left menu already leads to them, with their pages
 * ([GamingSection.menuSection]): never a place off the left menu, whose Settings row is its way in
 * (Droidtop/tracker#759).
 */
internal fun settingsHiddenPlaceScreens(mode: dev.droidtop.library.settings.UiMode): Set<String> {
    val menu = menuSectionsFor(mode).toSet()
    return GamingSection.entries
        .filter { it.isPlace && !it.offLeftMenu && it.menuSection in menu && it.place in dev.droidtop.library.settings.Place.reachable(mode) }
        .mapNotNull { it.placeScreenId }
        .toSet()
}

internal fun placeForScreen(screenId: String, mode: dev.droidtop.library.settings.UiMode): GamingSection? =
    GamingSection.entries.firstOrNull { it.isPlace && it.placeScreenId == screenId && it.place in dev.droidtop.library.settings.Place.reachable(mode) }

/**
 * The sections a given UI mode allows. Kiosk and Kid hide Settings --
 * the point of both is handing the device to somebody without handing
 * over the device's configuration.
 */
internal fun sectionsFor(mode: dev.droidtop.library.settings.UiMode): List<GamingSection> =
    GamingSection.entries.filter { !it.isPlace && it != GamingSection.NEW_GAMES && !(mode.hidesSettings && it.managesDevice) }

/**
 * The destinations the left menu lists: the main tabs, then the
 * places things live, with Settings last. A mode that hides Settings
 * hides the places too -- stores, downloads, updates and plugins are the
 * device's configuration as much as Settings is.
 */
internal fun menuSectionsFor(mode: dev.droidtop.library.settings.UiMode): List<GamingSection> {
    val tabs = sectionsFor(mode)
    // The listed places; Kiosk and Kid hide them all, since they manage the device. Updates and Get new games are pages
    // of Downloads and Updates (Place.listed, Droidtop/tracker#792); Plugins, Social and Game sources are elsewhere
    // (offLeftMenu).
    val places = GamingSection.entries.filter { it.place?.listed == true && !it.offLeftMenu && !mode.hidesSettings }
    // Home leads, then PC Games (Home, PC Games, Retro Games, Apps); a stable sort keeps the rest in order.
    val main = tabs.filterNot { it == GamingSection.SETTINGS }.sortedBy {
        when (it) {
            GamingSection.HOME -> 0
            GamingSection.PC_GAMES -> 1
            else -> 2
        }
    }
    return main + places + tabs.filter { it == GamingSection.SETTINGS }
}

// Which kinds are Apps and which are Games is the library's split, not
// this shell's: the Launcher's Games screen reads the same two sets
// (LibraryEntry.kt, LibraryKinds).
internal val APP_KINDS = LibraryKinds.APPS
internal val GAME_KINDS = LibraryKinds.GAMES

internal fun GamingSection.displayName(): String = when (this) {
    GamingSection.HOME -> "Home"
    GamingSection.GAMES -> "Retro Games"
    GamingSection.PC_GAMES -> "PC Games"
    GamingSection.APPS -> "Apps"
    GamingSection.SETTINGS -> "Settings"
    GamingSection.STORES, GamingSection.SOCIAL, GamingSection.DOWNLOADS,
    GamingSection.UPDATES, GamingSection.PLUGINS -> place!!.title
    GamingSection.NEW_GAMES -> dev.droidtop.library.integrations.GetGamesEntry.NEW_GAMES
}

/**
 * One browsable group in Games' system list -- either a non-ROM engine
 * ([LibraryEntryKind] like RENPY) or a real console system (an NES/GBA/PSX
 * ROM folder, keyed by [LibraryEntry.systemId]). Each console system is
 * its own group, giving the System → Game structure ES-DE (and every other
 * emulation frontend) uses.
 */
/**
 * Requests focus once the [androidx.compose.ui.focus.FocusRequester]'s
 * node actually exists. A themed view composes its list widget through
 * several nested layout passes, so the focusable node attaches a LATER
 * frame than a LaunchedEffect's first dispatch, and a single request on
 * that first dispatch leaves the screen with no focus at all (seen on
 * device via logcat). Retries across frames until the node exists; gives
 * up quietly after ~1s of frames rather than looping forever on a theme
 * whose view never attaches one (ES-DWEE's widgetless system view).
 *
 * The Compose droidtop ships (1.9) does not throw for an unattached
 * requester: requestFocus() prints a warning and does nothing, and only
 * requestFocus(FocusDirection) says whether focus was taken. Success is
 * what Compose returns, never "did not throw", so the retries continue
 * until a dialog's field exists (console, 0.2.0-dev.2127).
 */
internal suspend fun requestFocusWhenAttached(
    focusRequester: androidx.compose.ui.focus.FocusRequester,
    tag: String,
) {
    val taken = retryEachFrame(
        attempts = FOCUS_ATTEMPTS,
        attempt = { runCatching { focusRequester.requestFocus(androidx.compose.ui.focus.FocusDirection.Enter) }.getOrDefault(false) },
        nextFrame = { withFrameNanos {} },
    )
    if (!taken) {
        android.util.Log.w("droidtop.GamepadShell", "$tag focus never attached after $FOCUS_ATTEMPTS frames")
    }
}

/** Runs [attempt] once a frame until it returns true, at most [attempts] times; whether it ever did. Pure. */
internal suspend fun retryEachFrame(attempts: Int, attempt: () -> Boolean, nextFrame: suspend () -> Unit): Boolean {
    repeat(attempts) { i ->
        if (attempt()) return true
        if (i < attempts - 1) nextFrame()
    }
    return false
}

internal const val FOCUS_ATTEMPTS = 60

internal sealed interface GameGroup {
    val key: String
    val label: String

    /**
     * The ES-DE `${system.theme}` folder this group themes as -- real
     * ES-DE's own es_systems.xml `<theme>` mechanism (`SystemData::
     * mThemeFolder`), where a system's theme folder is a separate,
     * declared value rather than always its id.
     */
    val systemThemeFolder: String?

    // Retro Games holds console systems and collections only; PC and
    // engine games belong to the separate PC Games tab (docs/SPEC.md 7i).

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
internal fun GameGroup.esDeCollectionKind(): EsDeCollectionKind = when {
    this !is GameGroup.Collection -> EsDeCollectionKind.NONE
    themeFolder == AutoCollections.CUSTOM_THEME_FOLDER -> EsDeCollectionKind.CUSTOM
    else -> EsDeCollectionKind.AUTO
}

/**
 * What one group's theme parse is keyed on: the arguments
 * [ThemeAssets.loadActiveTheme] takes for it. The system view, its
 * neighbour slots, the gamelist and the carousel's logos all ask for a
 * group's theme through this one key, so they share one parse.
 */
internal data class GroupThemeKey(
    val systemId: String?,
    val collectionThemeFolder: String?,
    val systemFullName: String?,
    val collectionKind: EsDeCollectionKind,
) {
    fun load(context: android.content.Context) = ThemeAssets.loadActiveTheme(
        context, systemId, collectionThemeFolder, systemFullName, collectionKind,
    )

    fun cached(context: android.content.Context) = ThemeAssets.cachedActiveTheme(
        context, systemId, collectionThemeFolder, systemFullName, collectionKind,
    )
}

internal fun GameGroup.themeKey() = GroupThemeKey(
    systemId = systemThemeFolder,
    collectionThemeFolder = (this as? GameGroup.Collection)?.themeFolder,
    systemFullName = label,
    collectionKind = esDeCollectionKind(),
)

/**
 * [group]'s theme parse, read in composition but parsed off it.
 *
 * A parse is XML and file reads (includes, `${system.*}` substitution),
 * so composition only reads the parse cache and never parses on the
 * main thread. On a miss it keeps drawing the theme this call site
 * last drew (a neighbouring system's, for the moment a parse takes)
 * while [Dispatchers.Default] parses the new one. The one exception is a
 * call site that has drawn nothing yet: it parses in place, once,
 * because drawing droidtop's unthemed fallback for a frame and then
 * swapping in the theme would be a visible flash on every start. The
 * system view's logo loop parses every group in the background, so
 * after start every site hits the cache.
 *
 * [wanted] false is "no group is open": no parse, and null.
 */
@Composable
internal fun rememberActiveTheme(group: GameGroup?, wanted: Boolean = true): dev.droidtop.library.theme.EsDeTheme? {
    val context = LocalContext.current
    val key = group?.themeKey() ?: GroupThemeKey(null, null, null, EsDeCollectionKind.NONE)
    val version = ThemePrefs.version
    var drawn by remember { mutableStateOf<dev.droidtop.library.theme.EsDeTheme?>(null) }
    val cached = remember(key, version, wanted) {
        when {
            !wanted -> null
            else -> key.cached(context) ?: if (drawn == null) key.load(context) else null
        }
    }
    LaunchedEffect(key, version, wanted) {
        if (!wanted) return@LaunchedEffect
        drawn = cached ?: withContext(Dispatchers.Default) { key.load(context) }
    }
    if (!wanted) return null
    return cached ?: drawn
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
internal fun rememberEsDeSystemSlot(
    group: GameGroup?,
    entries: List<LibraryEntry>,
    countsOnly: Boolean,
): EsDeSystemSlot? {
    val theme = rememberActiveTheme(group)
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

/**
 * Real ES-DE auto-collection ids/theme-folder names, confirmed against `CollectionSystemsManager.cpp`'s own real
 * declaration table -- not guessed.
 */
internal object AutoCollections {
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
 * Which carousel card a Retro Games entry belongs to: its console system.
 * The entries this tab is handed have already passed
 * [LibraryEntry.onPcGamesTab]'s complement, so every one carries a real
 * console systemId.
 */
internal fun LibraryEntry.gameGroup(): GameGroup =
    GameGroup.System(checkNotNull(systemId) { "Retro Games entries must have a console system id" })

/**
 * The card a list starts on when it is rebuilt: the one whose [keys] entry is
 * [focusKey], else the first. By id, so the order changing under a rescan
 * does not move it (Droidtop/tracker#642).
 */
internal fun restoredFocusIndex(keys: List<String>, focusKey: String?): Int =
    keys.indexOf(focusKey).coerceAtLeast(0)

/** System cards shown by Retro Games after applying its PC Games ownership rule. */
internal fun retroGamesSystemIds(entries: List<LibraryEntry>): List<String> =
    entries.asSequence()
        .filterNot { it.onPcGamesTab }
        .mapNotNull { it.systemId }
        .distinct()
        .toList()

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
 * [LibraryEntryKind] declaration order. Entries keep the order they come
 * in: the Apps view's query sorts them (A to Z until the person says
 * otherwise), and a section only groups.
 */
internal fun buildAppSections(entries: List<LibraryEntry>): List<HomeSection> {
    val byDisplayName = entries.groupBy { it.kind.displayName() }
    val order = LibraryEntryKind.entries.map { it.displayName() }.distinct()
    return order.mapNotNull { name ->
        byDisplayName[name]?.let { HomeSection(name, it) }
    }
}

// LibraryEntryKind.displayName() moved to library-core (shared with the
// second-screen companion panel) -- see its doc comment there.

@Composable
internal fun HomeSectionRow(
    section: HomeSection,
    firstCardFocus: FocusRequester?,
    onShowDetail: (LibraryEntry) -> Unit,
    onOptions: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    modifier: Modifier = Modifier,
    onToggleFavorite: (LibraryEntry) -> Unit = {},
) {
    Column(modifier = modifier) {
        Text(
            section.title,
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(
                start = LocalShellWindow.current.edgePadding,
                end = LocalShellWindow.current.edgePadding,
                top = MenuTokens.SectionHeadingTopGap,
                bottom = MenuTokens.SectionHeadingGap,
            ),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = LocalShellWindow.current.edgePadding),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(section.entries, key = { _, entry -> entry.id }) { index, entry ->
                GameCard(
                    entry = entry,
                    modifier = if (index == 0 && firstCardFocus != null) Modifier.focusRequester(firstCardFocus) else Modifier,
                    // A opens the details, where Play is (Droidtop/tracker#641).
                    onActivate = { onShowDetail(entry) },
                    onShowDetail = { onShowDetail(entry) },
                    onOptions = { onOptions(entry) },
                    onFocused = { onFocusedEntryChanged(entry) },
                    onToggleFavorite = { onToggleFavorite(entry) },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun GameCard(
    entry: LibraryEntry,
    modifier: Modifier = Modifier,
    // What A and a tap do. Gaming opens the game's details, where Play is (owner, 2026-10-24, Droidtop/tracker#641:
    // "it should open the game's page"); Standard's games screen plays.
    onActivate: () -> Unit,
    // Y: the game's details.
    onShowDetail: () -> Unit,
    // L2 and a long press: the game's own menu, one menu by two paths (Droidtop/tracker#621). Standard's games
    // screen has no details page and passes its options as both.
    onOptions: () -> Unit = onShowDetail,
    onFocused: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val lift = rememberFocusLift(focused)
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
            .onPad { press ->
                when (press.action) {
                    GamepadAction.A -> {
                        onActivate()
                        true
                    }
                    GamepadAction.Y -> {
                        onShowDetail()
                        true
                    }
                    // L2 is the focused game's menu everywhere in Gaming (proposal 6.A, Droidtop/tracker#644 slice
                    // 4), a Retro game's too (Droidtop/tracker#621 M6).
                    GamepadAction.L2 -> {
                        onOptions()
                        true
                    }
                    // X toggles LibraryEntry.favorite, which themes draw as
                    // a badge. This is the shell's one place that sets it.
                    GamepadAction.X -> {
                        onToggleFavorite()
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            // The click handler is what makes the card respond to touch:
            // .focusable() alone does not respond to taps, only to focus
            // and the onKeyEvent below. It also gives DPAD_CENTER/Enter
            // clickable's own default key handling on a focused node. A
            // controller's face button (A / cross) still
            // reports as a distinct keycode on most Android gamepad
            // mappings, so it's still handled explicitly below rather than
            // relying on clickable() to cover it. Y opens the detail screen
            // (§7).
            // A long press is the touch path to L2: the game's own menu
            // (Droidtop/tracker#621), Android's list convention.
            .padRow(onClick = onActivate, onLongClick = onOptions)
            // ONE selection idiom across the shell: the menus' own
            // accent border over a brightened surface (MenuTokens), not a
            // third one beside the menus' brightened card and the theme's
            // own highlight.
            // The card rises with its ring at its own edge; its art grows inside that edge (focusZoom).
            .focusRise(lift, Corners.Tile)
            .selectionFrame(
                selected = focused,
                shape = Corners.Tile,
                rest = MenuTokens.Surface,
                restOutline = MenuTokens.CardOutline,
            )
            .focusZoom(lift, Corners.Tile),
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
                        Brush.verticalGradient(listOf(Color.Transparent, MenuTokens.Scrim)),
                    )
                    .padding(12.dp),
            ) {
                Column {
                    // Constrained to the tile, so a long name (an app
                    // named after its own class is the longest of all)
                    // never runs past the card into its neighbour's.
                    Text(
                        dev.droidtop.library.GameNaming.displayName(entry.title),
                        color = MenuTokens.OnSurface,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.kind.displayName(),
                        color = MenuTokens.Value,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.focusMarquee(focused),
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                Text(
                    dev.droidtop.library.GameNaming.displayName(entry.title),
                    color = MenuTokens.OnSurface,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.kind.displayName(),
                    color = MenuTokens.OnSurfaceMuted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.focusMarquee(focused),
                )
            }
        }
        if (entry.favorite) {
            Text(
                "★",
                color = MenuTokens.Favourite,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
        // The same state badge a PC capsule carries (installed, update,
        // missing), from what the entry already says.
        CapsuleStatusBadge(entry)
    }
}


/** One pending "launch on which screen?" question, as handed to [LaunchDisplayChooserDialog]. */
internal data class DisplayChoiceRequest(
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
internal class EsDeGroupHistory {
    var previous: GameGroup? = null
    var current: GameGroup? = null
}

/**
 * What the last launch left for the person (Droidtop/tracker#763: the shell body's launch state, held together).
 * [error] is the user-visible failure: a failed launch must inform, never kill. [progress] is a Windows setup's live
 * progress on the same path (Droidtop/tracker#140), kept apart from [error] because it is not a failure, and cleared
 * when the attempt ends either way. The fixable kinds a banner offers to fix: [missingEmulator] (the system had no
 * emulator), [unknownProgram] (a Windows game whose program droidtop could not tell, Droidtop/tracker#308) and
 * [missingCore] (a RetroArch core missing or incomplete, Droidtop/tracker#271); [programScreen] is the program
 * choice once opened.
 */
@androidx.compose.runtime.Stable
internal class LaunchProblems {
    var error by mutableStateOf<String?>(null)
    var progress by mutableStateOf<String?>(null)
    var missingEmulator by mutableStateOf<dev.droidtop.library.consoles.NoEmulatorInstalled?>(null)
    var unknownProgram by mutableStateOf<dev.droidtop.library.ProgramNotIdentified?>(null)
    var missingCore by mutableStateOf<dev.droidtop.library.consoles.RetroArchCores.Missing?>(null)
    var programScreen by mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null)
}

/**
 * The shell's own overlays and which is open (Droidtop/tracker#763: held together): the Quick Menu (hold Select) and
 * the section it opens on when something asked for one ([quickMenuStart], Downloads for the status cluster's activity spot,
 * cleared on close); the left menu (Start, docs/SPEC.md 7j); the one search over the library, apps, sources and settings; and one
 * system's search, by its id and name, from the running console game's sheet; and that sheet itself, on coming back
 * to the game ([runningGameOpen]).
 */
@androidx.compose.runtime.Stable
internal class ShellOverlays {
    var quickMenuOpen by mutableStateOf(false)
    var quickMenuStart by mutableStateOf<QuickSection?>(null)
    // The running game's sheet, opened on coming back to the game (Droidtop/tracker#740).
    var runningGameOpen by mutableStateOf(false)
    var leftMenuOpen by mutableStateOf(false)
    var globalSearchOpen by mutableStateOf(false)
    var systemSearch by mutableStateOf<Pair<String, String>?>(null)
}
