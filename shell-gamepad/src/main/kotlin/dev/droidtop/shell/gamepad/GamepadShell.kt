package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.runtime.systemstatus.SettingsLaunch
import android.util.Log
import dev.droidtop.library.userFacingErrorMessage
import dev.droidtop.shell.gamepad.input.PadCadence
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.focus.focusProperties
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
import android.widget.Toast
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
import dev.droidtop.shell.gamepad.query.LibraryViewPrefs
import dev.droidtop.shell.gamepad.query.retroQueryScope
import dev.droidtop.shell.gamepad.query.LibrarySortSheet
import dev.droidtop.shell.gamepad.query.PersistQuery
import dev.droidtop.shell.gamepad.query.appsActiveView
import dev.droidtop.shell.gamepad.query.appsStripLabel
import dev.droidtop.shell.gamepad.query.appsStripViews
import dev.droidtop.shell.gamepad.query.appsViewCounts
import dev.droidtop.shell.gamepad.query.appsViewQuery
import dev.droidtop.shell.gamepad.query.pillText
import dev.droidtop.shell.gamepad.query.SheetAction
import dev.droidtop.shell.gamepad.query.appsQueryScope
import dev.droidtop.shell.gamepad.query.rememberSavedViews
import dev.droidtop.library.LibraryEntry
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

@Composable
private fun GamepadShellBody(
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
    // Where a plugin's first-use permission sheet is drawn (docs/plugin-api.md 4.3).
    PluginGrantSheetHost()
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
    // the PC grid" was. Saveable rather than plain remember so the
    // Activity recreate the Text size setting triggers
    // (AccessibilityPrefs, Droidtop/tracker#87) restores this whole
    // place instead of dropping the user on the default section.
    val nav = rememberSaveable(saver = sessionOnly(ShellBackStack.Saver)) { ShellBackStack(GamingPrefs.defaultSection(context)) }
    val section = nav.section
    // Where the PC Games tab is (its view, its cursor, an open page), held
    // here for the same reason the back stack is: the tab is rebuilt
    // whenever another tab is shown (docs/SPEC.md 7i).
    val pcGames = rememberSaveable(saver = sessionOnly(dev.droidtop.shell.gamepad.pc.PcGamesState.Saver)) {
        dev.droidtop.shell.gamepad.pc.PcGamesState().apply {
            if (GamingPrefs.opensOnPcGames(context)) view = dev.droidtop.shell.gamepad.pc.PcView.OVERVIEW
        }
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
    // Real user-visible launch-failure state -- see launchError's render
    // site. A failed launch must inform, never kill.
    var launchError by remember { mutableStateOf<String?>(null) }
    // Live setup progress for the same launch path (Droidtop/tracker#140):
    // what a Windows setup is fetching and how far along it is. A
    // different state from launchError because it is not a failure --
    // painted as one, the system-files download read as an error the
    // person never asked for. Cleared when the attempt ends either way.
    var launchProgress by remember { mutableStateOf<String?>(null) }
    // The fixable half of a launch failure: which system had no
    // emulator, so the banner can offer to go get one.
    var missingEmulator by remember {
        mutableStateOf<dev.droidtop.library.consoles.NoEmulatorInstalled?>(null)
    }
    // The other fixable kind: a Windows game whose program droidtop could
    // not tell (Droidtop/tracker#308), and the program choice once opened.
    var unknownProgram by remember { mutableStateOf<dev.droidtop.library.ProgramNotIdentified?>(null) }
    var programScreen by remember { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
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
    // press no longer recomposes the whole shell body (it was a
    // LaunchedEffect key here). See SPEC "Performance on the console".
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
    var quickMenuOpen by remember { mutableStateOf(false) }
    // A quick menu left open when this shell lost the foreground (the
    // Switch-mode dialog's own "Android"/"Desktop" rows, or Home) must not
    // still be showing when a fresh, real deep-link brings this same
    // singleTask composition back -- rig, dq-modefix-01 steps 3-4:
    // MainActivity/GamepadShell is never destroyed by a mode round trip,
    // so quickMenuOpen stayed true from before, and picking "Gaming" from
    // the Switch-mode dialog resumed the SAME composition with the old
    // Quick Menu still stacked on top, which read as "Gaming never
    // appears" even though mode had actually already resolved correctly.
    // The left menu (Start; docs/SPEC.md 7j "Gaming controls") closes for
    // the same reason.
    var leftMenuOpen by remember { mutableStateOf(false) }
    // The game screen of a Windows game sends Back here with openQuickMenu set (OwnGameScreens): the
    // menu opens on its Game section, since the game is running.
    LaunchedEffect(deepLinkToken) {
        quickMenuOpen = openQuickMenu
        leftMenuOpen = false
    }
    // The face-button layout of the pad in use (SPEC 7b, "Console and controller detection"),
    // followed live because a handheld's own toggle can flip it while this shell stays in front.
    // The check is an in-memory read and is repeated on every destination change, every menu and
    // dialog opening or closing, resume, and window-focus regain; the glyphs and the key map both
    // read ControllerLayouts.layout, so they change together and nothing else recomposes.
    LaunchedEffect(Unit) { ControllerLayouts.attach(context) }
    LaunchedEffect(nav.place.key, quickMenuOpen, leftMenuOpen, focusedHints.layerOpen) {
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
                quickMenuOpen = false
                leftMenuOpen = true
            },
            openQuick = {
                leftMenuOpen = false
                quickMenuOpen = true
            },
        )
    }
    var displayChoice by remember {
        mutableStateOf<DisplayChoiceRequest?>(null)
    }
    // The launch-static experiment's variant B (tracker#160): the theme's launch sample is held back from the
    // A press until the launch is really dispatched, which is after this question has been answered.
    val pendingLaunchSound = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        dev.droidtop.library.LaunchDisplay.chooser = { options, canRemember, onChosen ->
            displayChoice = DisplayChoiceRequest(options, canRemember, onChosen)
        }
        dev.droidtop.library.LaunchDisplay.beforeDispatch = {
            if (pendingLaunchSound.getAndSet(false)) EsDeNavigationSounds.play(UiSound.LAUNCH)
        }
        // The dispatch itself runs after the audio hand-off, so a failure
        // there arrives here rather than through library.launch.
        dev.droidtop.library.LaunchDisplay.onLaunchFailed = {
            launching = null
            launchError = LaunchFailureMessage.userMessage(null, it)
        }
        onDispose {
            dev.droidtop.library.LaunchDisplay.chooser = null
            dev.droidtop.library.LaunchDisplay.beforeDispatch = null
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
            dev.droidtop.runtime.LaunchSoundPlan::silenced,
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
            onCancel = {
                displayChoice = null
                // Backed out: nothing launches, so nothing stays held back or silenced (tracker#160).
                dev.droidtop.runtime.AudioHandOff.mark("screen question cancelled")
                pendingLaunchSound.set(false)
                dev.droidtop.runtime.AudioHandOff.setQuiet("A pressed", false)
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

    // The actual dispatch: unchanged for every entry, PC and engine games
    // included -- once something has decided this game IS ready, it
    // launches exactly the same way a console ROM does.
    val dispatchLaunch: (LibraryEntry) -> Unit = { entry ->
        // Real ES-DE launch sound -- played unconditionally on any game
        // launch (ViewController.cpp:1064-1066 plays LAUNCHSOUND whether
        // or not a launch transition is configured), so it lives here in
        // the ONE launch handler rather than per key-handling site.
        //
        // The launch-static experiment (tracker#160, docs/SPEC.md "Launch
        // audio hand-off") decides here when that sample sounds and whether
        // the rest of droidtop's sound carries on: see LaunchSoundPlan.
        val soundVariant = dev.droidtop.runtime.LaunchSoundExperiment.variant(context)
        dev.droidtop.runtime.AudioHandOff.mark(
            "A pressed on a game: launch begins; open: ${dev.droidtop.runtime.AudioHandOff.openStreams()}",
        )
        if (dev.droidtop.runtime.LaunchSoundPlan.launchSoundAtPress(soundVariant)) EsDeNavigationSounds.play(UiSound.LAUNCH)
        if (dev.droidtop.runtime.LaunchSoundPlan.launchSoundAtDispatch(soundVariant)) pendingLaunchSound.set(true)
        if (dev.droidtop.runtime.LaunchSoundPlan.quietFromPress(soundVariant)) {
            dev.droidtop.runtime.AudioHandOff.setQuiet("A pressed", true)
        }
        scope.launch {
            launchError = null
            launching = entry
            // The audio hand-off happens where the app is actually
            // dispatched (LaunchDisplay.startOn), after the display
            // chooser, not here (tracker#160).
            runCatching { library.launch(entry) }
                .onFailure {
                    android.util.Log.e("droidtop.GamepadShell", "Launching ${entry.title} failed", it)
                    pendingLaunchSound.set(false)
                    dev.droidtop.runtime.AudioHandOff.setQuiet("A pressed", false)
                    launching = null
                    missingEmulator = it as? dev.droidtop.library.consoles.NoEmulatorInstalled
                    unknownProgram = it as? dev.droidtop.library.ProgramNotIdentified
                    launchError = LaunchFailureMessage.userMessage(entry.title, it)
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
            // A launch that never reached the dispatch (and is not waiting on the screen question) leaves
            // nothing silenced or held back by the launch-static experiment.
            if (displayChoice == null && !dev.droidtop.runtime.AudioHandOff.handedOff.value) {
                pendingLaunchSound.set(false)
                dev.droidtop.runtime.AudioHandOff.setQuiet("A pressed", false)
            }
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
                launchError = null
                launchProgress = null
                dev.droidtop.library.PcRunnerOptions.resolveAndPlay(
                    context = context,
                    entry = entry,
                    onLaunch = { dispatchLaunch(entry) },
                    // Progress, not failure: the setup's own live lines,
                    // which stay until the attempt ends. A successful
                    // Windows setup ends with nothing more to say -- the
                    // next A press launches, exactly as the offer said.
                    onStatus = { message -> launchProgress = message },
                    onFailure = { message ->
                        launchProgress = null
                        launchError = message
                    },
                )
                launchProgress = null
            }
        } else {
            dispatchLaunch(entry)
        }
    }

    // The Quick Menu's Game tab (docs/SPEC.md, Droidtop/tracker#82):
    // which entry the most recent still-parked launch was for, resolved
    // fresh each time the menu opens -- LaunchDisplay.runningGame is
    // plain process state, not a flow, read the same "ask when you need
    // it" way LaunchDisplay's chooser/askOptions already are elsewhere
    // in this file, rather than a second observable copy of it. The left
    // menu's Resume row reads the same answer (docs/SPEC.md 7j).
    val sideMenuOpen = quickMenuOpen || leftMenuOpen
    val forceStoppedLaunch by produceState(
        initialValue = false,
        sideMenuOpen,
        dev.droidtop.library.LaunchDisplay.runningPackageName,
    ) {
        value = false
        while (
            sideMenuOpen &&
            dev.droidtop.library.LaunchDisplay.runningGame != null &&
            dev.droidtop.library.LaunchDisplay.runningPackageName != null
        ) {
            val stopped = dev.droidtop.library.LaunchDisplay.isRunningPackageForceStopped(context)
            if (stopped) {
                dev.droidtop.library.LaunchDisplay.clearRunning()
                value = true
                break
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    val runningEntry = remember(sideMenuOpen, forceStoppedLaunch, gameEntries, appEntries) {
        if (!sideMenuOpen) {
            null
        } else if (forceStoppedLaunch) {
            null
        } else {
            dev.droidtop.library.LaunchDisplay.runningGame?.gameId?.let { id ->
                gameEntries.orEmpty().firstOrNull { it.id == id } ?: appEntries.orEmpty().firstOrNull { it.id == id }
            }
        }
    }

    // What the last Quit to Library actually did, for the row's subtitle
    // (Droidtop/tracker#82). Null until a quit runs: the row then reads
    // "Ends <game>", and a real quit replaces it with the outcome, so a
    // quit that left the emulator alive is shown honestly instead of being
    // reported as a success. Held outside the `if` so it survives the sheet
    // closing; reset whenever the running game changes.
    var quitOutcome by remember { mutableStateOf<dev.droidtop.library.QuitResult?>(null) }
    LaunchedEffect(runningEntry?.id) { quitOutcome = null }
    if (quickMenuOpen) {
        QuickMenu(
            runningEntry = runningEntry,
            library = library,
            // The same launch path every entry already goes through
            // (console ROM, PC and engine games alike) -- relaunching the
            // entry is what "resume" already means for the planned
            // Recents tab (docs/SPEC.md, "Recents (decided 2026-08-30)"),
            // reused here rather than a second resume mechanism.
            quitOutcome = quitOutcome,
            onResume = { entry ->
                quickMenuOpen = false
                onLaunch(entry)
            },
            onQuit = { entry, restart ->
                // The sheet stays open until the game really ended, so a
                // quit that could not end it shows its reason in the row.
                scope.launch {
                    // The one quit path (Library.quitRunning); it clears the running-game state only on a
                    // confirmed end, and anything short of that is shown in the row's subtitle.
                    val outcome = library.quitRunning(context, entry)
                    quitOutcome = outcome
                    if (outcome is dev.droidtop.library.QuitResult.Ended) {
                        quickMenuOpen = false
                        // Restart (the Quick Menu's Game section): start the
                        // entry again, but only now that it really ended.
                        if (restart) onLaunch(entry)
                    }
                }
            },
            onOpenLeftMenu = {
                quickMenuOpen = false
                leftMenuOpen = true
            },
            openPlace = { screenId ->
                placeForScreen(screenId, menuSectionsFor(uiMode))?.let { place ->
                    quickMenuOpen = false
                    nav.openSection(place)
                } != null
            },
            placeAvailable = { screenId -> placeForScreen(screenId, menuSectionsFor(uiMode)) != null },
            onDismiss = { quickMenuOpen = false },
        )
    }
    if (leftMenuOpen) {
        LeftMenu(
            entries = leftMenuEntries(menuSectionsFor(uiMode)),
            current = section,
            atHome = pcGames.home,
            runningTitle = runningEntry?.let { dev.droidtop.library.GameNaming.displayName(it.title) },
            // The Quick Menu Game section's Resume: the one launch path, reused.
            onResume = {
                leftMenuOpen = false
                runningEntry?.let(onLaunch)
            },
            onSelect = { entry ->
                leftMenuOpen = false
                // Home and PC Games are one section: the row says which view.
                if (entry.section == GamingSection.PC_GAMES) pcGames.open(entry.home)
                selectSection(entry.section)
            },
            onOpenQuickMenu = {
                leftMenuOpen = false
                quickMenuOpen = true
            },
            onDismiss = { leftMenuOpen = false },
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
    // scanKindsProgressive, not scanKinds: real, reported UX request --
    // this screen used to show nothing but its spinner until the entire
    // scan (every root, every system folder) finished, even though most
    // folders individually are fast. Each collected emission is already a
    // growing snapshot (see Library.scanKindsProgressive's own doc
    // comment), so just assigning it directly here is enough to make the
    // screen fill in gradually as real results arrive, without this file
    // needing to know anything about how the underlying scan is chunked.
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
            EsDeNavigationSounds.play(UiSound.FAVORITE)
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
    // library left nothing focused. Grabbing focus onto the invisible
    // anchor Spacer in the content Box (never the tab bar -- see its own
    // comment, owner 2026-09-27) on first composition guarantees a valid
    // focus target always exists; GamesSection/AppsSection still steal
    // focus onto real content once it loads, same as before.
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
        if (GamingSection.PC_GAMES in menuSectionsFor(uiMode) && !(section == GamingSection.PC_GAMES && pcGames.home)) {
            pcGames.open(home = true)
            selectSection(GamingSection.PC_GAMES)
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
    )
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShellWindow provides shellWindow,
        LocalHelpRowOwner provides helpRowOwner,
        LocalShoulderStrips provides shoulderStrips,
        LocalFocusedHints provides focusedHints,
        LocalShellMenus provides shellMenus,
        LocalHelpRowSlotReport provides { slot -> helpRowSlotReport = currentScreenKey to slot },
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
            // Touch counts as activity too: only key events reset the
            // idle timer before this, so the screensaver could appear
            // while somebody was actively tapping. Initial pass, so it
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
                        if (!quickMenuOpen) quickMenuOpen = true
                        true
                    }
                    // Start, the left menu's button: navigation, where the
                    // Quick Menu is quick management (docs/SPEC.md 7j,
                    // "Gaming controls"). Only this window ever sees it:
                    // once a launched game is in front the shell is not
                    // the foreground and receives no input.
                    GamepadAction.START -> {
                        if (!leftMenuOpen) leftMenuOpen = true
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
        launchProgress?.let { message ->
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
        launchError?.let { message ->
            LaunchFailureDialog(
                message = message,
                actions = listOfNotNull(missingEmulator?.let { problem ->
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
                        missingEmulator = null
                        launchError = null
                    }
                }, unknownProgram?.let { problem ->
                    // The game's program choice, the same screen its options open.
                    LaunchFailureAction("Choose the program") {
                        programScreen = dev.droidtop.library.WindowsPrograms.screen(problem.entryId, problem.title, problem.gameRoot)
                        unknownProgram = null
                        launchError = null
                    }
                }),
                onDismiss = {
                    missingEmulator = null
                    unknownProgram = null
                    launchError = null
                },
            )
        }
        programScreen?.let { screen -> CatalogSheet(root = screen, onExit = { programScreen = null }) }
        // A launch that never answered or left at once (LaunchWatchdog, docs/SPEC.md "The launch
        // watchdog"): plain words and the three ways out, where a black screen used to be.
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
                                launchError = outcome.text
                            }
                        }
                    },
                    LaunchFailureAction("Return to droidtop") {
                        dev.droidtop.library.LaunchWatchdog.returnToShell(context)
                        dev.droidtop.library.LaunchWatchdog.dismiss()
                    },
                ).let { ways ->
                    // A stuck RetroArch whose core is not confirmed installed: getting the core is the likely fix, so it comes first.
                    val core = alert.retroArchCore?.let {
                        LaunchFailureAction("Get the core") {
                            scope.launch {
                                dev.droidtop.library.LaunchWatchdog.getCore(context, alert)?.let { launchError = it }
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
            GamingSection.PC_GAMES -> false
            GamingSection.APPS -> !appEntries.isNullOrEmpty()
            GamingSection.SETTINGS,
            GamingSection.STORES,
            GamingSection.SOCIAL,
            GamingSection.DOWNLOADS,
            GamingSection.UPDATES,
            GamingSection.PLUGINS,
            -> true
        }
        val shellHelpRow: @Composable (Color) -> Unit = shellHelpRow@{ background ->
            if (screensaverOn) return@shellHelpRow
            ButtonHintFooter(
                background = background,
                // A names what it does on a detail's primary button
                // ("A Play"), and stays "Select" everywhere else.
                aLabel = detailPrimaryLabel.takeIf { detailEntry != null } ?: "Select",
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
                        // keeps its place but takes no presses; only Back gets through,
                        // to leave a launch that never produces a window.
                        .onPreviewKeyEvent { event ->
                            launching != null && event.nativeKeyEvent.keyCode !in LAUNCH_COVER_PASS_KEYS
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
                        // Real bug this fixes: the loading spinner used to gate this
                        // entire content area unconditionally, before `section` was
                        // ever checked -- Settings (which needs zero scan data) was
                        // stuck behind the same load state as Games/Apps, showing as
                        // "empty" even though it's a plain static list with nothing
                        // to wait for. detailEntry is also section-independent, so
                        // it stays checked before the loading gate too.
                        // A PC or engine game no longer opens a detail screen of
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
                        )
                        // The left menu's places: a registered settings screen each,
                        // in place (docs/SPEC.md 7j "Places"). Back is always
                        // something here too, for the reason Settings' is.
                        shownSection.placeScreenId != null -> {
                            canGoBack = true
                            // Every registered store's Open library, built in or plugged in.
                            val storeLibraries = remember {
                                dev.droidtop.library.stores.StoreLibraries.all().associate { store ->
                                    "${PcSource.LIBRARY_ITEM_PREFIX}${store.id}" to {
                                        pcGames.showSource(store.id)
                                        nav.openSection(GamingSection.PC_GAMES)
                                    }
                                }
                            }
                            PlaceCatalogView(
                                screenId = shownSection.placeScreenId.orEmpty(),
                                onBack = { nav.openSection(GamingPrefs.defaultSection(context)) },
                                nativeActions = storeLibraries,
                            )
                        }
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
                                browseThemesToken = browseThemesRequest,
                                placeScreenIds = menuSectionsFor(uiMode).filter { it.isPlace }.mapNotNull { it.placeScreenId }.toSet(),
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
                        (shownSection == GamingSection.GAMES || shownSection == GamingSection.PC_GAMES) &&
                            (gameEntries == null || foldedGameEntries == null) -> CircularProgressIndicator(color = MenuTokens.OnSurface)
                        shownSection == GamingSection.APPS && appEntries == null -> CircularProgressIndicator(color = MenuTokens.OnSurface)
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
                            GamingSection.PC_GAMES -> dev.droidtop.shell.gamepad.pc.PcGamesSection(
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
                                state = pcGames,
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
                                onOpenSection = { target ->
                                    if (target == GamingSection.PC_GAMES) pcGames.open(home = false)
                                    selectSection(target)
                                },
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
                            GamingSection.SETTINGS,
                            GamingSection.STORES,
                            GamingSection.SOCIAL,
                            GamingSection.DOWNLOADS,
                            GamingSection.UPDATES,
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
            // or system status (docs/SPEC.md 7k2), so the two never overlap.
            if (!screensaverOn && !(section == GamingSection.GAMES && GamingTheme.palette.drawsOwnStatus)) {
                FloatingStatusCluster(onClick = { quickMenuOpen = true }, modifier = Modifier.align(Alignment.TopEnd))
            }
            // A theme that draws its own help legend (a pad is attached, so
            // the legend is the theme's) names nothing of the two menus and
            // is not tappable: the Start and R2 pills sit in the corner
            // beside it (docs/SPEC.md 7j, "Gaming controls").
            if (helpRowOwner == HelpRowOwner.THEME && !screensaverOn) {
                ShellMenuPills(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = shellWindow.edgePadding, bottom = 4.dp)
                        .background(MenuTokens.Surface.copy(alpha = 0.58f), RoundedCornerShape(12.dp)),
                )
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
private val PROCESS_MARK: Long = System.currentTimeMillis()

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

private fun <T : Any> sessionOnly(inner: Saver<T, Any>): Saver<T, Any> = Saver(
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
private object GamingPrefs {
    private fun prefs(context: Context) = CatalogPrefs.prefs(context)

    /** Home (the default) and "pc" both start in PC Games; [opensOnPcGames] tells them apart. */
    fun defaultSection(context: Context): GamingSection =
        when (prefs(context).getString(GamingSettingsCatalog.ID_DEFAULT_SECTION, "home")) {
            "apps" -> GamingSection.APPS
            "games" -> GamingSection.GAMES
            else -> GamingSection.PC_GAMES
        }

    /** "pc": open on PC Games' Overview rather than Home (docs/SPEC.md 7i). */
    fun opensOnPcGames(context: Context): Boolean =
        prefs(context).getString(GamingSettingsCatalog.ID_DEFAULT_SECTION, "home") == "pc"

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
 * Full-screen detail view for one entry — one horizontal row of primary
 * actions (Launch, leading/highlighted) instead of launch being a card's
 * only behavior. Reached via a card's Y/Info action, closed via B/Back.
 */
@Composable
private fun EntryDetailScreen(
    entry: LibraryEntry,
    library: Library,
    onLaunch: () -> Unit,
    onClose: () -> Unit,
    onPrimaryFocus: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val launchFocus = remember { FocusRequester() }
    LaunchedEffect(entry) { launchFocus.requestFocus() }

    var editingMetadata by remember { mutableStateOf(false) }
    var viewingMedia by remember(entry) { mutableStateOf(false) }
    var pickingMatch by remember(entry) { mutableStateOf(false) }
    var scrapeStatus by remember(entry) { mutableStateOf<String?>(null) }
    var scrapeResult by remember(entry) { mutableStateOf<String?>(null) }
    var pluginActionStatus by remember(entry) { mutableStateOf<String?>(null) }
    var pluginActionScreen by remember(entry) { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    // Everything scraped for this game, for the media viewer: listed on
    // IO once per entry, never while drawing (the same as PcGameMenu).
    val media by produceState(emptyList<Pair<String, String>>(), entry) {
        value = withContext(Dispatchers.IO) {
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
    }
    var editingCollections by remember { mutableStateOf(false) }

    // The user's own "open with" hooks (docs/SPEC.md section 12), paired
    // with the real files on this entry droidtop itself cannot open.
    // Resolved off the main thread: which of the entry's files exist,
    // the integrations folder, and whether the PackageManager has each
    // declared target app.
    var openWithTargets by remember(entry) { mutableStateOf<List<dev.droidtop.library.integrations.OpenWithTarget>>(emptyList()) }
    var openWith by remember(entry) { mutableStateOf<List<Integration>>(emptyList()) }
    var integrationError by remember(entry) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry) {
        val (targets, integrations) = withContext(Dispatchers.IO) {
            val targets = openWithTargetsFor(entry)
            targets to if (targets.isEmpty()) {
                emptyList()
            } else {
                IntegrationStore.available(context, IntegrationCapability.OPEN_WITH)
            }
        }
        openWithTargets = targets
        openWith = integrations
    }

    // Context actions plugins offer on this game or app (docs/plugin-api.md 3 C4). Which ones apply comes from
    // manifests alone, and `enabled` is asked once, when this screen opens, never while drawing.
    val contextTarget = remember(entry) {
        dev.droidtop.pluginhost.ContextTarget(
            kind = if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) "app" else "game",
            id = entry.id,
            title = dev.droidtop.library.GameNaming.displayName(entry.title),
            systemId = entry.systemId,
            packageName = if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) entry.id else null,
        )
    }
    var pluginActions by remember(entry) { mutableStateOf<List<dev.droidtop.library.integrations.PluginContextActions.Action>>(emptyList()) }
    // Rows plugins add to a game's page (docs/plugin-api.md 3 C18): on this screen each section is a chip that opens
    // it as a page, where its rows, inputs and actions work; the PC game page draws them as its own rows instead.
    var pluginSections by remember(entry) { mutableStateOf<List<dev.droidtop.library.integrations.PluginGameSections.Section>>(emptyList()) }
    LaunchedEffect(entry) {
        pluginActions = withContext(Dispatchers.IO) {
            dev.droidtop.library.integrations.PluginContextActions.actionsFor(context, contextTarget, dev.droidtop.pluginhost.PluginModes.GAMING)
                .filter { dev.droidtop.library.integrations.PluginContextActions.enabled(context, it, contextTarget) }
        }
        pluginSections = withContext(Dispatchers.IO) {
            dev.droidtop.library.integrations.PluginGameSections.sectionsFor(context, contextTarget)
        }
    }

    // Console ROMs and native apps only: a PC or engine game never
    // reaches this screen any more (see the PC branch at the call site),
    // so the launch-strategy picker, the PC scrape action and the PC
    // "choose match" branch that used to live here moved wholesale to
    // PcGameMenu rather than being duplicated across two screens.
    val isRomEntry = entry.kind == LibraryEntryKind.CONSOLE_ROM
    // Achievements, play time and an emulator's compatibility, asked for once when the page opens (docs/SPEC.md 7h,
    // "Game info"); a console game only, an app has none of them.
    val gameInfo = rememberGameInfoRows(entry, enabled = isRomEntry)

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
            // B closes the detail; the system back key reaches the same
            // close through the shell's BackHandler for it (SPEC 6e).
            .onPad { press ->
                if (press.action == GamepadAction.B) {
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
                    .background(MenuTokens.Card, RoundedCornerShape(16.dp)),
            ) {
                if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) {
                    // An app's artwork is its launcher icon: drawn at an
                    // icon's size on the plate, never cropped and blown up
                    // into a blurred banner (UI pass 2026-09-24, L1).
                    AsyncImage(
                        model = entry.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(96.dp).align(Alignment.Center),
                    )
                } else {
                    AsyncImage(
                        model = entry.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(MenuTokens.Card, RoundedCornerShape(16.dp)),
                    )
                }
                // Platform/kind label overlaid on the art, matching Daijishō's
                // own detail-screen layout (boxart with the platform name
                // overlaid at the bottom of the art) -- structure, not pixels.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MenuTokens.Scrim)))
                        .padding(12.dp),
                ) {
                    Text(entry.kind.itemName(), color = MenuTokens.OnSurface, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(dev.droidtop.library.GameNaming.displayName(entry.title), color = MenuTokens.OnSurface, style = MaterialTheme.typography.headlineMedium)
        if (entry.artworkUri == null) {
            Text(entry.kind.itemName(), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.titleMedium)
        }
        if (entry.playtimeSeconds > 0) {
            Text("Played ${entry.playtimeSeconds / 60} min", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        }
        gameInfo.forEach { row ->
            Text(
                row.value?.let { "${row.title}: $it" } ?: row.title,
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            row.subtitle?.let { Text(it, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall) }
        }
        // What this game's Switch files add up to -- "Update v131072 ·
        // 2 DLC", or, for a row that is itself an update/DLC whose base
        // game is missing, that fact. Empty (and so drawn not at all)
        // for a base game with nothing beside it.
        entry.switchFacts?.line()?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
        }
        val detailScope = rememberCoroutineScope()
        // Scrolls rather than clipping: on a phone the chips outgrow the
        // width, and a chip off the edge is an action nobody can reach.
        Row(
            modifier = Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShellChip(
                "Launch",
                primary = true,
                modifier = Modifier
                    .focusRequester(launchFocus)
                    .onFocusChanged { onPrimaryFocus(if (it.isFocused) "Launch" else null) },
                onClick = onLaunch,
            )
            gameInfo.filter { it.chip != null }.forEach { row ->
                ShellChip(
                    row.chip.orEmpty(),
                    onClick = {
                        val page = row.screen
                        val address = row.url
                        if (page != null) pluginActionScreen = page else if (address != null) openAddress(context, address)
                    },
                )
            }
            // Real ConsoleRomProvider-specific concept -- same honest
            // "not applicable" gating Library.toggleFavorite/
            // saveMetadata already use for a non-ROM entry.
            if (isRomEntry) {
                ShellChip("Choose match", onClick = { pickingMatch = true })
            }
            // A Switch game with an update or DLC beside it: opens its emulator and names the files to pick (SwitchContentHandoff).
            if (dev.droidtop.library.consoles.SwitchContentHandoff.applies(entry)) {
                ShellChip(
                    "Add update and DLC",
                    onClick = {
                        detailScope.launch {
                            integrationError = dev.droidtop.library.consoles.SwitchContentHandoff.open(context, entry)
                        }
                    },
                )
            }
            // More games for this game's own system (an app's page asks which system).
            GetGamesChip(
                dev.droidtop.library.integrations.GetGamesContext.GAME_PAGE,
                systemId = entry.systemId.takeIf { isRomEntry },
            )
            if (media.size > 1) {
                ShellChip("View media (${media.size})", onClick = { viewingMedia = true })
            }
            pluginActions.forEach { action ->
                ShellChip(
                    action.label,
                    onClick = {
                        pluginActionStatus = "${action.label}…"
                        pluginActionScreen = null
                        detailScope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                dev.droidtop.library.integrations.PluginContextActions.run(context, action, contextTarget)
                            }
                            pluginActionStatus = outcome.message
                            pluginActionScreen = outcome.screen
                        }
                    },
                )
            }
            pluginSections.forEach { section ->
                ShellChip(
                    section.label,
                    onClick = {
                        detailScope.launch {
                            // Built off the main thread: the page's game context reads the plugin's grants.
                            pluginActionScreen = withContext(Dispatchers.IO) {
                                dev.droidtop.library.integrations.PluginGameSections.screenFor(context, section, contextTarget)
                            }
                        }
                    },
                )
            }
            // One chip per (hook, openable file). Never a substitution and
            // never a silent pick: droidtop has no manual reader and no
            // full video player of its own, so these open a door rather
            // than taking over a behaviour, and if the user declared two
            // hooks they both appear and the user chooses -- droidtop does
            // not rank them.
            openWith.forEach { integration ->
                openWithTargets.forEach { target ->
                    ShellChip(
                        openWithChipLabel(integration, target, openWithTargets.size),
                        onClick = {
                            integrationError = runCatching {
                                IntegrationStore.run(
                                    context = context,
                                    integration = integration,
                                    systemId = entry.systemId,
                                    file = target.file,
                                )
                            }.exceptionOrNull()?.let { failure ->
                                Log.w("droidtop.gamepad", "Integration failed: ${integration.label}", failure)
                                "${integration.label} failed: ${userFacingErrorMessage(failure)}"
                            }
                        },
                    )
                }
            }
            if (isRomEntry) {
                ShellChip("Edit metadata", onClick = { editingMetadata = true })
                ShellChip("Collections", onClick = { editingCollections = true })
                ShellChip(
                    scrapeStatus?.let { "Scraping…" } ?: "Scrape",
                    onClick = {
                        if (scrapeStatus == null) {
                            scrapeStatus = "Scraping ${entry.title}…"
                            detailScope.launch {
                                val romFile = java.io.File(entry.id)
                                val folder = romFile.parentFile
                                val systemsById = withContext(Dispatchers.IO) {
                                    dev.droidtop.library.consoles.ConsoleSystemsRepository.allSystems(context)
                                }.associateBy { it.id }
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
            // An app's own actions, on its own screen (UI pass 2026-09-24,
            // L1). Android's screens do the work and Uninstall asks its own
            // confirmation. There is no Back chip: B is on the hint row,
            // which is also its touch route.
            if (entry.kind == LibraryEntryKind.NATIVE_ANDROID_APP) {
                ShellChip("App info", onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", entry.id, null),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
                ShellChip("Uninstall", onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_DELETE,
                                android.net.Uri.fromParts("package", entry.id, null),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
            }
        }
        (pluginActionStatus ?: scrapeStatus ?: scrapeResult)?.let {
            Text(it, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        // An integration drives another app's own real Activity, so it can
        // fail for reasons droidtop cannot see coming (the template names a
        // component that app no longer exports). Shown, not swallowed.
        integrationError?.let {
            Text(it, color = MenuTokens.Danger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
    pluginActionScreen?.let { screen ->
        val close = { pluginActionScreen = null }
        androidx.compose.ui.window.Dialog(onDismissRequest = close) {
            GatePadInThisDialog()
            dev.droidtop.shell.gamepad.CatalogNavigator(root = screen, onExit = close)
        }
    }
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
            HintBinding(GamepadAction.SELECT, "Options") { showOptions },
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
    PLUGINS(dev.droidtop.library.settings.Place.PLUGINS),
    ;

    val isPlace: Boolean get() = place != null

    /** A place or Settings: device management, which Kiosk and Kid hide. */
    val managesDevice: Boolean get() = this == SETTINGS || isPlace

    /** The settings-registry screen a place draws, null for a tab with a view of its own. */
    val placeScreenId: String? get() = place?.screenId
}

// Registry ids of the screens the places draw, from the one place list.
internal const val PLACE_SOCIAL_SCREEN_ID = dev.droidtop.library.settings.Place.ID_SOCIAL
internal const val PLACE_UPDATES_SCREEN_ID = dev.droidtop.library.settings.Place.ID_UPDATES
internal const val PLACE_PLUGINS_SCREEN_ID = dev.droidtop.library.settings.Place.ID_PLUGINS

/**
 * The place a settings screen id IS, when the left menu lists it ([allowed]: a mode that hides
 * Settings hides the places). A link to such a screen opens the place in the shell instead of a
 * second copy of the screen inside a sheet, so a screen has one home (docs/SPEC.md 7j "Places").
 */
internal fun placeForScreen(screenId: String, allowed: List<GamingSection>): GamingSection? =
    allowed.firstOrNull { it.isPlace && it.placeScreenId == screenId }

/**
 * The sections a given UI mode allows. Kiosk and Kid hide Settings --
 * the point of both is handing the device to somebody without handing
 * over the device's configuration.
 */
internal fun sectionsFor(mode: dev.droidtop.library.settings.UiMode): List<GamingSection> =
    GamingSection.entries.filter { !it.isPlace && !(mode.hidesSettings && it.managesDevice) }

/**
 * The destinations the left menu lists: the main tabs, then the
 * places things live, with Settings last. A mode that hides Settings
 * hides the places too -- stores, downloads, updates and plugins are the
 * device's configuration as much as Settings is.
 */
internal fun menuSectionsFor(mode: dev.droidtop.library.settings.UiMode): List<GamingSection> {
    val tabs = sectionsFor(mode)
    val places = GamingSection.entries.filter { it.isPlace && !(mode.hidesSettings && it.managesDevice) }
    // PC Games leads (Home, PC Games, Retro Games, Apps); a stable sort keeps the rest in order.
    val main = tabs.filterNot { it == GamingSection.SETTINGS }.sortedBy { if (it == GamingSection.PC_GAMES) 0 else 1 }
    return main + places + tabs.filter { it == GamingSection.SETTINGS }
}

// Which kinds are Apps and which are Games is the library's split, not
// this shell's: the Launcher's Games screen reads the same two sets
// (LibraryEntry.kt, LibraryKinds).
private val APP_KINDS = LibraryKinds.APPS
private val GAME_KINDS = LibraryKinds.GAMES

internal fun GamingSection.displayName(): String = when (this) {
    GamingSection.GAMES -> "Retro Games"
    GamingSection.PC_GAMES -> "PC Games"
    GamingSection.APPS -> "Apps"
    GamingSection.SETTINGS -> "Settings"
    GamingSection.STORES, GamingSection.SOCIAL, GamingSection.DOWNLOADS,
    GamingSection.UPDATES, GamingSection.PLUGINS -> place!!.title
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
private fun GameGroup.esDeCollectionKind(): EsDeCollectionKind = when {
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
private data class GroupThemeKey(
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

private fun GameGroup.themeKey() = GroupThemeKey(
    systemId = systemThemeFolder,
    collectionThemeFolder = (this as? GameGroup.Collection)?.themeFolder,
    systemFullName = label,
    collectionKind = esDeCollectionKind(),
)

/**
 * [group]'s theme parse, read in composition but parsed off it.
 *
 * A parse is XML and file reads (includes, `${system.*}` substitution),
 * and these used to run inside `remember` on the main thread, once per
 * carousel system, before the first frame. Now composition only reads
 * the parse cache. On a miss it keeps drawing the theme this call site
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
private fun rememberActiveTheme(group: GameGroup?, wanted: Boolean = true): dev.droidtop.library.theme.EsDeTheme? {
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
private fun rememberEsDeSystemSlot(
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
 * Which carousel card a Retro Games entry belongs to: its console system.
 * The entries this tab is handed have already passed
 * [LibraryEntry.onPcGamesTab]'s complement, so every one carries a real
 * console systemId.
 */
private fun LibraryEntry.gameGroup(): GameGroup =
    GameGroup.System(checkNotNull(systemId) { "Retro Games entries must have a console system id" })

/** System cards shown by Retro Games after applying its PC Games ownership rule. */
internal fun retroGamesSystemIds(entries: List<LibraryEntry>): List<String> =
    entries.asSequence()
        .filterNot { it.onPcGamesTab }
        .mapNotNull { it.systemId }
        .distinct()
        .toList()

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
    // One rule with PC Games (docs/SPEC.md 7g, "A store's collections"): a
    // collection is a Retro one only while it has a Retro member, so a store's
    // collections of PC games never show here, and it lists its Retro members only.
    val retroMembership = remember(customCollections, customCollectionMembership) {
        dev.droidtop.library.CollectionMembership(customCollections, customCollectionMembership)
    }
    val customCollectionGroups = remember(retroMembership, entriesById) {
        dev.droidtop.library.CollectionScope.retroCollections(retroMembership) { it in entriesById }
            .map { GameGroup.Collection(it.id, it.name, AutoCollections.CUSTOM_THEME_FOLDER) }
    }
    val collectionGroups = autoCollectionGroups + customCollectionGroups
    // Real cross-cutting membership -- a game can be in several
    // collections at once, unlike the strict system/engine partition
    // below (see GameGroup.Collection's own doc comment).
    val collectionGroupMembers = remember(collectionGroups, entries, retroMembership) {
        collectionGroups.associateWith { group ->
            when (group.id) {
                AutoCollections.ALL_GAMES_ID -> entries
                AutoCollections.FAVORITES_ID -> entries.filter { it.favorite }
                AutoCollections.LAST_PLAYED_ID -> entries
                    .filter { it.lastPlayedEpochMs != null }
                    .sortedByDescending { it.lastPlayedEpochMs }
                    .take(AutoCollections.LAST_PLAYED_LIMIT)
                else -> dev.droidtop.library.CollectionScope.retroMembers(group.id, retroMembership, entriesById)
            }
        }
    }
    // Present-and-non-empty groups -- engines first (in GAME_KINDS'
    // declaration order), then real console systems (alphabetical by
    // display name) -- hoisted so both the system-list view and the
    // per-system grid view share one ordering (needed for ES-DE-style
    // Left/Right sibling-system switching below).
    val byGroup = entries.filter { it.systemId != null }.groupBy { it.gameGroup() }
    // Keyed on the platform database's load version as well as the
    // groups: labels resolve through its cache, which warms on a
    // background thread -- sorting before the warm lands used raw
    // system ids and froze "switch" at the end of the carousel
    // (observed live) instead of "Nintendo Switch" among the Nintendos.
    val platformsLoadVersion by dev.droidtop.library.consoles.PlatformsDatabase.loadVersion.collectAsState()
    val orderedSystemGroups = remember(entries, platformsLoadVersion) {
        retroGamesSystemIds(entries).map { GameGroup.System(it) }.sortedBy { it.label.lowercase() }
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
    val selectedGroupLabel = selectedGroup?.label
    val gamelistTheme = rememberActiveTheme(selectedGroup, wanted = selectedGroup != null)
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
    // The group's own filter and sort: the same query model, sheets and
    // remembered state every library list uses (docs/SPEC.md 7j). The
    // stored view is read once per group (an in-memory preference read) and
    // written back off the main thread.
    val retroScope = remember(selectedGroup?.label) { retroQueryScope(selectedGroup?.label.orEmpty()) }
    // Real ES-DE's Last Played collection opens in recency order, the one list where A to Z would defeat its
    // purpose; the person can still pick another sort and it is remembered like any list's.
    val retroDefault = remember(selectedGroup) {
        if ((selectedGroup as? GameGroup.Collection)?.id == AutoCollections.LAST_PLAYED_ID) {
            LibraryQuery(sort = dev.droidtop.shell.gamepad.query.LibrarySortKey.RECENT)
        } else {
            LibraryQuery()
        }
    }
    var retroQuery by remember(retroScope.id) { mutableStateOf(LibraryViewPrefs.activeQuery(context, retroScope.id, retroDefault)) }
    LaunchedEffect(retroQuery, retroScope.id) {
        withContext(Dispatchers.IO) { LibraryViewPrefs.setActiveQuery(context, retroScope.id, retroQuery) }
    }
    val retroSaved = rememberSavedViews(retroScope.id)
    val retroBase = remember(entries, selectedGroup, collectionGroupMembers) {
        when (val group = selectedGroup) {
            null -> emptyList()
            // A collection's members are not a system's games: its own list, through the same model.
            is GameGroup.Collection -> collectionGroupMembers[group].orEmpty()
            else -> entries.filter { it.gameGroup() == group }
        }
    }
    var retroSortOpen by remember { mutableStateOf(false) }
    var retroFilterOpen by remember { mutableStateOf(false) }
    val systemGamesBeforeSearch = remember(selectedGroup, sortVersion, retroQuery, retroScope, retroBase) {
        // The stored per-group sort and filter: NAME by default, which is real ES-DE's own gamelist
        // default, and no filter. A collection is a list like any other.
        if (selectedGroup == null) emptyList() else retroQuery.applyTo(retroBase, retroScope)
    }
    // The console gamelist's search (Select menu -> Search, the shared
    // LibrarySearchDialog, docs/SPEC.md 12a "Search fan-out"): narrows the
    // list by the one shared text rule.
    var gamelistSearchText by remember(selectedGroup) { mutableStateOf("") }
    val systemGamesForGroup = remember(systemGamesBeforeSearch, gamelistSearchText) {
        if (gamelistSearchText.isBlank()) {
            systemGamesBeforeSearch
        } else {
            systemGamesBeforeSearch.filter { dev.droidtop.shell.gamepad.query.matchesSearchText(it, gamelistSearchText) }
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
                systemId = (group as? GameGroup.System)?.systemId,
                // A finished scrape/import refreshes the REAL library
                // scan -- cached rows now live-resolve media, so the
                // refresh is what makes new art visible immediately.
                onScraped = {
                    sortVersion += 1
                    onRequestRescan()
                },
                onDismiss = { gamelistOptionsOpen = false },
                games = systemGamesForGroup,
                searchText = gamelistSearchText,
                totalGames = systemGamesBeforeSearch.size,
                onSearchTextChange = if (group == null) null else { text ->
                    gamelistSearchText = text
                    focusedGameIndex = 0
                },
                onJumpTo = { index ->
                    focusedGameIndex = index.coerceIn(0, (systemGamesForGroup.lastIndex).coerceAtLeast(0))
                },
                onOpenSort = if (group == null) null else ({ retroSortOpen = true }),
                onOpenFilter = if (group == null) null else ({ retroFilterOpen = true }),
            )
        }
    }
    if (retroSortOpen) {
        LibrarySortSheet(
            scope = retroScope,
            query = retroQuery,
            onQueryChange = {
                retroQuery = it
                focusedGameIndex = 0
            },
            onDismiss = { retroSortOpen = false },
        )
    }
    if (retroFilterOpen) {
        LibraryFilterSheet(
            scope = retroScope,
            base = retroBase,
            query = retroQuery,
            savedViews = retroSaved.views,
            onQueryChange = {
                retroQuery = it
                focusedGameIndex = 0
            },
            onSaveView = { retroSaved.save(it, retroQuery) },
            onForgetView = { retroSaved.forget(it) },
            onSearch = null,
            onDismiss = { retroFilterOpen = false },
        )
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
        // PC and engine games now go through this same themed gamelist
        // (docs/SPEC.md 7i, revised 2026-09-26) -- no more carve-out for a
        // screen that drew its own grid and reported its own focus.
        if (hasThemedGamelist) {
            val focused = systemGamesForGroup.getOrNull(focusedGameIndex)
            onFocusedEntryChanged(focused)
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
                    // A group's own options screen (ShellBackStack.Options,
                    // a level above the group) is a plain droidtop screen
                    // drawn over the group: it has no row of its own, so
                    // the shell's bar draws there -- and its B hint is the
                    // only touch route out.
                    nav.optionsOpen -> HelpRowClaim.NONE
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
        EsDeNavigationSounds.play(UiSound.BACK)
        // One level at a time: a group's own options screen is above the
        // group, so back leaves THAT first.
        nav.back()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The shoulders mean "switch system" at this level and "switch
            // section" above it; inside a screen opened from the group they
            // mean nothing, and letting them through tore that screen down
            // and changed the tab under it (rig, build 549). Taken before
            // anything below sees them.
            .onPad(preview = true) { press ->
                nav.optionsOpen && (press.action == GamepadAction.L || press.action == GamepadAction.R)
            }
            // B is not here: it is the back dispatcher's, through the shell's
            // root, and this group's BackHandler above is the one that answers
            // it -- one route for the pad's B and the system back key alike
            // (SPEC 6e). The guard this handler kept against the release of a
            // B that had closed the options screen above it is gone with it:
            // a release belongs to the press that began it.
            .onPad(cadence = PadCadence.THEMED_LIST) { press ->
                // A screen opened FROM the group owns its own presses while
                // it is up.
                if (nav.optionsOpen) return@onPad false
                val group = selectedGroup
                val themed = hasThemedGamelist
                val headless = group != null && themed && !gamelistHasListWidget &&
                    systemGamesForGroup.isNotEmpty()
                when (press.action) {
                    // Gamelist options (sort/scrape/import) right where the
                    // user is -- the ES-DE GuiGamelistOptions PATTERN in
                    // droidtop's own placement (Select; the Quick Menu is R2
                    // or a held Select). A system's gamelist gets sort/
                    // scrape/import, the carousel the library-wide actions.
                    GamepadAction.SELECT -> {
                        gamelistOptionsOpen = true
                        true
                    }
                    // ES-DE's "General navigation": Left/Right inside a
                    // gamelist jumps to the adjacent system's gamelist, with
                    // ES-DE's quicksysselect sound (ViewController.cpp:718/
                    // 728). The shoulders mean switch section everywhere
                    // (owner, 2026-09-27), so they fall through to the root.
                    GamepadAction.LEFT, GamepadAction.RIGHT -> {
                        if (group == null || orderedGroups.size <= 1) return@onPad false
                        EsDeNavigationSounds.play(UiSound.TAB)
                        val index = orderedGroups.indexOf(group)
                        val step = if (press.action == GamepadAction.LEFT) -1 else 1
                        selectGroup(orderedGroups[(index + step + orderedGroups.size) % orderedGroups.size])
                        true
                    }
                    // Headless per-game navigation, only when the theme's
                    // gamelist has no list widget of its own to own the
                    // cursor. Clamped (tracker#1): ES-DE's textlist pauses
                    // at its ends, and wrapping here read as "Up escaped the
                    // list". ES-DE's scroll sound (GamelistBase.cpp:133).
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        if (!headless) return@onPad false
                        val next = (focusedGameIndex + if (press.action == GamepadAction.UP) -1 else 1)
                            .coerceIn(0, systemGamesForGroup.size - 1)
                        if (next != focusedGameIndex) EsDeNavigationSounds.play(UiSound.MOVE)
                        focusedGameIndex = next
                        true
                    }
                    GamepadAction.A -> headless && systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onLaunch(it) } != null
                    // Y/Info, with or without a list widget.
                    GamepadAction.Y -> group != null && themed &&
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onShowDetail(it) } != null
                    // X/favourite, with or without a list widget.
                    GamepadAction.X -> group != null && themed &&
                        systemGamesForGroup.getOrNull(focusedGameIndex)?.let { onToggleFavorite(it) } != null
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
                    // An empty library offers the one thing that fills it,
                    // never a sentence with nothing to press
                    // (Droidtop/tracker#172): the same Game folders screen
                    // Settings and the Launcher's games grid open, drawn in
                    // place. CatalogNavigator owns its own B, so B leaves it.
                    val foldersScreen = remember { dev.droidtop.library.settings.SettingsScreenRegistry.get(GAME_FOLDERS_SCREEN_ID) }
                    var addingFolders by remember { mutableStateOf(false) }
                    if (addingFolders && foldersScreen != null) {
                        CatalogNavigator(root = foldersScreen, onExit = { addingFolders = false })
                    } else {
                        val emptyAction = remember { androidx.compose.ui.focus.FocusRequester() }
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = LocalShellWindow.current.edgePadding, vertical = Space.Xl),
                            verticalArrangement = Arrangement.spacedBy(Space.Lg),
                        ) {
                            Text("No games yet.", color = MenuTokens.OnSurface, style = TypeRole.body)
                            if (foldersScreen != null) {
                                ShellChip(
                                    "Add a folder",
                                    primary = true,
                                    modifier = Modifier.focusRequester(emptyAction),
                                    onClick = { addingFolders = true },
                                )
                                LaunchedEffect(Unit) { requestFocusWhenAttached(emptyAction, "Games empty") }
                            }
                            GetGamesChip(dev.droidtop.library.integrations.GetGamesContext.EMPTY_STATE, onChanged = onRequestRescan)
                        }
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
                        val focusedGroupLabel = focusedGroup?.label
                        val theme = rememberActiveTheme(focusedGroup)
                        // Same real navigation-sound rebinding as the gamelist
                        // screen's own hook (see that LaunchedEffect's comment)
                        // -- this is the site that runs FIRST after app start /
                        // a live theme switch, so sounds bind before any
                        // gamelist is ever entered.
                        LaunchedEffect(theme) { EsDeNavigationSounds.load(theme) }
                        val listElement = remember(theme) { theme?.views?.get("system")?.primaryListElement() }
                        // Every group's theme parse, and the carousel logo
                        // read out of it, worked out in the background: a
                        // parse per carousel system used to run in
                        // composition, all of them before the first frame,
                        // and the logo's file checks again on every library
                        // publish. Keyed on the groups' theme keys, not on
                        // the group list, which is a new list on every
                        // publish of a walk. Each logo lands as its group is
                        // done; until then that group draws its name, which
                        // is what a logo-less system draws anyway. The same
                        // loop warms the parse cache rememberActiveTheme
                        // reads, so moving to another system or opening its
                        // gamelist does not parse.
                        val groupThemeKeys = remember(orderedGroups) {
                            orderedGroups.map { it.key to it.themeKey() }
                        }
                        var logos by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
                        LaunchedEffect(groupThemeKeys, ThemePrefs.version) {
                            for ((groupKey, themeKey) in groupThemeKeys) {
                                val logo = withContext(Dispatchers.Default) {
                                    if (themeKey.systemId == null) null
                                    else themeKey.load(context)?.let { ThemeAssets.systemLogoPath(it) }
                                }
                                logos = logos + (groupKey to logo)
                            }
                        }
                        // remember(): the carousel recomposes every
                        // animation frame, and this list only changes with
                        // the groups or their logos.
                        val items = remember(orderedGroups, logos) {
                            orderedGroups.map { entryGroup ->
                                EsDeListItem(
                                    key = entryGroup.key,
                                    label = entryGroup.label,
                                    logoPath = logos[entryGroup.key],
                                    // Real `letterCaseAutoCollections` /
                                    // `letterCaseCustomCollections`: ES-DE cases a
                                    // collection's own name by which KIND of
                                    // collection it is (SystemView.cpp:835-849).
                                    collectionKind = entryGroup.esDeCollectionKind(),
                                    // Real ES-DE select sound -- entering a
                                    // system from the system view plays
                                    // SELECTSOUND (SystemView.cpp:129).
                                    onSelect = {
                                        EsDeNavigationSounds.play(UiSound.CONFIRM)
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
                        // byGroup only partitions system groups --
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
                        // 2026-09-24, screenshots 01/06). No shoulder either: L1/R1
                        // never switch sections (docs/SPEC.md 7j, "Gaming controls"),
                        // and nothing here is a tab strip they could step.
                        val systemListHints = listOf(
                            GamepadAction.A to "Select",
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
                                if (it != focusedSystemIndex) EsDeNavigationSounds.play(UiSound.SYSTEM)
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
            } else if (hasThemedGamelist && gamelistView != null) {
                // Real, unified theme-driven gamelist render -- ONE call into
                // the same generic EsDeThemedView/EsDeSystemListView machinery
                // the system-list screen already uses: a game is laid out by
                // the theme's own element positions, sizes, variants and md_*
                // metadata bindings. PC and engine games are not here at all
                // any more (docs/SPEC.md 7i, 2026-10-01): they have their own
                // tab, which no theme draws.
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
                // Per-game hints below are gated on this: every one of
                // them acts on the game under the cursor, and an empty
                // gamelist has none.
                val gameUnderCursor = systemGamesForGroup.isNotEmpty()
                EsDeThemedView(
                    view = gamelistView,
                    items = gamelistWidgetItems,
                    firstItemFocus = if (gamelistHasListWidget) firstFocus else null,
                    modifier = Modifier.fillMaxSize(),
                    // Same real SCROLLSOUND as the headless Up/Down
                    // branch above (a widget hosted in a gamelist
                    // scrolls with the scroll sound, CarouselComponent.
                    // h:105-108) -- guarded on a real index change,
                    // same reason as the system carousel.
                    onFocusedIndexChanged = {
                        if (it != focusedGameIndex) EsDeNavigationSounds.play(UiSound.MOVE)
                        focusedGameIndex = it
                    },
                    focusedSystemEntries = systemGamesForGroup,
                    focusedGameIndex = focusedGameIndex,
                    hints = rememberHintList(
                        listOf(
                            HintBinding(GamepadAction.A, "Launch") { gameUnderCursor },
                            HintBinding(GamepadAction.Y, "Info") { gameUnderCursor },
                            HintBinding(GamepadAction.X, "Favorite") { gameUnderCursor },
                            HintBinding(GamepadAction.B, "Back"),
                        )
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
                // The group's list through the one query model, like the themed list above.
                val games = systemGamesForGroup
                // Same "don't request focus on an unattached FocusRequester" fix
                // as the system-list view above -- games can be empty here too
                // (the "recent" filter selected with zero recently-played entries).
                LaunchedEffect(group, retroQuery) { if (games.isNotEmpty()) requestFocusWhenAttached(firstFocus, "Game grid") }
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
                    // The one pill the other lists draw while something filters ("Favourites, 12 of 80"),
                    // cleared by one press; no count line and no chip row otherwise.
                    retroQuery.pillText(retroScope, games.size, retroQuery.totalIn(retroBase, retroScope))?.let { pill ->
                        Row(
                            modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding, vertical = 8.dp),
                        ) {
                            ShellChip(pill, on = true, onClick = { retroQuery = retroQuery.cleared })
                        }
                    }
                    val pad = rememberGridPad()
                    LazyVerticalGrid(
                        state = pad.state,
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
                        //
                        // Up at the TOP row no longer answers true either:
                        // it leaves on both edges exactly like Left/
                        // Right at a real edge, so Compose's own focus
                        // search runs. That search once walked straight
                        // out of the grid into shell chrome above it,
                        // which is why Up was swallowed here. The
                        // swallow protected nothing while cutting the
                        // pad off from the real rows above this grid:
                        // the filter chips first, and in safe mode the
                        // banner's action behind them (docs/SPEC.md
                        // 10c). Destinations are chosen in the left
                        // menu, never through focus traversal.
                        modifier = Modifier.fillMaxSize().padding(horizontal = LocalShellWindow.current.edgePadding)
                            .onPad { press ->
                                val direction = gridDirection(press.action) ?: return@onPad false
                                // One card per step (GridPad); at an edge
                                // it answers false and Left/Right reach the
                                // switch-system handler above.
                                if (pad.canMove(direction)) {
                                    pad.move(direction)
                                    true
                                } else {
                                    false
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        gridItemsIndexed(games, key = { _, entry -> entry.id }) { index, entry ->
                            GameCard(
                                entry = entry,
                                modifier = Modifier
                                    .focusRequester(pad.requester(index))
                                    .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                                onLaunch = { onLaunch(entry) },
                                onShowDetail = { onShowDetail(entry) },
                                onFocused = {
                                    pad.focused = index
                                    onFocusedEntryChanged(entry)
                                },
                                onToggleFavorite = { onToggleFavorite(entry) },
                            )
                        }
                    }
                }
            }
        }

    }
}



/**
 * What an app tile promises while it has the focus: A opens, X and Y open
 * the Filter and Sort By sheets (the list around the tile answers them),
 * Select the app's own options. The one footer draws these
 * ([declaresHints]).
 */
private val APP_TILE_HINTS = listOf(
    HintBinding(GamepadAction.A, "Open"),
    HintBinding(GamepadAction.X, "Filter"),
    HintBinding(GamepadAction.Y, "Sort By"),
    HintBinding(GamepadAction.SELECT, "Options"),
)

/**
 * The Apps tab (docs/SPEC.md 7j, "Filters, sort and the hint bar"): every
 * launchable thing that is not a game library, as one list over the same
 * query model the game libraries use. All apps, A to Z until the person
 * says otherwise; X opens the Filter sheet (Category, Running, Recently
 * used, Recently installed, Favourites, Hidden, Source, each with counts),
 * Y Sort By, Select the focused app's options, and a long press marks an
 * app as a game. One strip sits above the list ([ViewStrip]): All, Games,
 * Emulators, Tools and Recently used with their counts, stepped by L1/R1,
 * and the filters no view stands for as its one pill. The view's filters
 * and sort are remembered, and nothing here reads a disk while drawing:
 * the category rules, the usage log, the counts and the filtered list are
 * worked out off the main thread.
 */
@Composable
private fun AppsSection(
    entries: List<LibraryEntry>,
    onLaunch: (LibraryEntry) -> Unit,
    onShowDetail: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
    onToggleFavorite: (LibraryEntry) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Which apps are running, from the task manager's one list (read here
    // once, and again whenever the Filter sheet opens, so its Running count
    // is current); nothing polls while this tab is not showing.
    val running by TaskManager.snapshot.collectAsState()
    val runningPackages = remember(running) { running?.apps?.mapTo(HashSet()) { it.packageName }.orEmpty() }

    var query by remember { mutableStateOf(LibraryQuery()) }
    var queryLoaded by remember { mutableStateOf(false) }
    PersistQuery(APPS_SCOPE_ID, query, queryLoaded) {
        query = it
        queryLoaded = true
    }
    val savedViews = rememberSavedViews(APPS_SCOPE_ID)

    // What the rules and the usage log say, loaded off the main thread and
    // reloaded when the person changes a mark or comes back from granting
    // Usage access.
    var marksTick by remember { mutableIntStateOf(0) }
    var usageTick by remember { mutableIntStateOf(0) }
    val rules by produceState(AppCategoryRules(), marksTick) {
        value = withContext(Dispatchers.IO) { AppCategoryRules.load(context) }
    }
    val usage by produceState(emptyMap<String, Long>(), usageTick) {
        value = withContext(Dispatchers.IO) { AppUsageAccess.lastUsed(context) }
    }
    val usageGranted by produceState(false, usageTick) {
        value = withContext(Dispatchers.IO) { AppUsageAccess.granted(context) }
    }
    val scope = remember(rules, usage, runningPackages) { appsQueryScope(rules, usage, runningPackages) }

    val shown by produceState<List<LibraryEntry>?>(null, entries, query, scope) {
        value = withContext(Dispatchers.Default) { query.applyTo(entries, scope) }
    }
    val total = remember(entries, query, scope) { query.totalIn(entries, scope) }
    // The strip's views and their counts (docs/SPEC.md 7j).
    val views = remember(savedViews.views) { appsStripViews(savedViews.views) }
    val counts by produceState(emptyMap<String, Int>(), entries, scope, views) {
        value = withContext(Dispatchers.Default) { appsViewCounts(entries, scope, views) }
    }

    var filterOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    LaunchedEffect(filterOpen) { TaskManager.refresh(context) }
    var optionsFor by remember { mutableStateOf<LibraryEntry?>(null) }

    // "Mark as game" from a long press and from the app's options: the
    // one answer is stored, then the category rules reload.
    val markGame: (LibraryEntry) -> Unit = { entry ->
        if (entry.appFacts != null) {
            val nowGame = !rules.isGame(entry.id, entry.appFacts)
            coroutineScope.launch {
                withContext(Dispatchers.IO) { AppGameMarks.set(context, entry.id, nowGame) }
                marksTick++
                dev.droidtop.shell.gamepad.theme.shellToast(
                    context,
                    if (nowGame) "${entry.title} marked as a game" else "${entry.title} is not a game",
                )
            }
        }
    }

    val list = shown
    if (list == null || !queryLoaded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MenuTokens.OnSurface)
        }
        return
    }

    val sections = remember(list) { buildAppSections(list) }
    val firstFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }
    // The first tile takes the focus when the list first arrives, and the
    // empty state when a filter leaves nothing, so X and Y always have a
    // focused element to answer them.
    LaunchedEffect(list.isEmpty()) {
        if (list.isEmpty()) requestFocusWhenAttached(emptyFocus, "Apps") else requestFocusWhenAttached(firstFocus, "Sections")
    }

    // L1/R1 step the strip's views, never wrapping; the strip owns the
    // press even at its end (docs/SPEC.md 7j, "Gaming controls"). With a
    // pill showing (no view lit) the first press lands on All.
    val activeView = appsActiveView(views, query)
    OwnShoulders { step ->
        val next = if (activeView < 0) 0 else menuStep(activeView, views.size, step)
        if (next != activeView) {
            EsDeNavigationSounds.play(UiSound.MOVE)
            query = appsViewQuery(views[next], query)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPad { press ->
                when (press.action) {
                    GamepadAction.X -> {
                        filterOpen = true
                        true
                    }
                    GamepadAction.Y -> {
                        sortOpen = true
                        true
                    }
                    else -> false
                }
            },
    ) {
        ViewStrip(
            labels = views.map { appsStripLabel(it, counts) },
            active = activeView,
            focused = null,
            pill = if (activeView >= 0) null else query.pillText(scope, list.size, total),
            onSelect = { query = appsViewQuery(views[it], query) },
            onClearPill = { query = query.cleared },
            modifier = Modifier.padding(top = MenuTokens.SectionListTopGap),
        )
        if (list.isEmpty()) {
            val emptyHints = remember {
                listOf(
                    HintBinding(GamepadAction.A, "Clear filters") { !query.isEmpty },
                    HintBinding(GamepadAction.X, "Filter"),
                    HintBinding(GamepadAction.Y, "Sort By"),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(emptyFocus)
                    .declaresHints(emptyHints)
                    .onPad { press ->
                        if (press.action == GamepadAction.A && !query.isEmpty) {
                            query = query.cleared
                            true
                        } else {
                            false
                        }
                    }
                    .focusable(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (entries.isEmpty()) "No apps found yet." else "No apps match these filters",
                    color = MenuTokens.OnSurfaceMuted,
                )
            }
        } else {
            var firstAssigned = false
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp),
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
                            onOptions = { optionsFor = it },
                            onMarkGame = markGame,
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
                // Games are not apps, but this is where a person on the Apps tab looks for more of anything.
                item(key = "get_games") {
                    GetGamesChip(
                        dev.droidtop.library.integrations.GetGamesContext.APPS,
                        modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding),
                    )
                }
            }
        }
    }

    if (filterOpen) {
        val usageAction = SheetAction(
            title = "Include apps opened outside droidtop",
            subtitle = "Needs usage access",
            onClick = { runCatching { SettingsLaunch.start(context, AppUsageAccess.settingsIntent()) } },
        )
        LibraryFilterSheet(
            scope = scope,
            base = entries,
            query = query,
            savedViews = savedViews.views,
            onQueryChange = { query = it },
            onSaveView = { savedViews.save(it, query) },
            onForgetView = { savedViews.forget(it) },
            onSearch = null,
            onDismiss = {
                filterOpen = false
                // Back from the system's Usage access screen lands here.
                usageTick++
            },
            facetActions = if (usageGranted) emptyMap() else mapOf(LibraryFacet.RECENTLY_USED to listOf(usageAction)),
            footerActions = if (usageGranted) emptyList() else listOf(usageAction),
        )
    }
    if (sortOpen) {
        LibrarySortSheet(scope = scope, query = query, onQueryChange = { query = it }, onDismiss = { sortOpen = false })
    }
    optionsFor?.let { entry ->
        AppOptionsMenu(
            entry = entry,
            isGame = rules.isGame(entry.id, entry.appFacts),
            onDetails = {
                optionsFor = null
                onShowDetail(entry)
            },
            onToggleFavorite = {
                optionsFor = null
                onToggleFavorite(entry)
            },
            onMarkGame = {
                optionsFor = null
                markGame(entry)
            },
            onDismiss = { optionsFor = null },
        )
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
    onOptions: (LibraryEntry) -> Unit,
    onMarkGame: (LibraryEntry) -> Unit,
    onFocusedEntryChanged: (LibraryEntry?) -> Unit,
) {
    Column {
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
                    onOptions = { onOptions(entry) },
                    onMarkGame = { onMarkGame(entry) },
                    onFocused = { onFocusedEntryChanged(entry) },
                )
            }
        }
    }
}

private val APP_TILE_HEIGHT = 136.dp

/** The keys the shell lets through while the launch screen covers it: the ones that mean Back. */
private val LAUNCH_COVER_PASS_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_BACK,
    android.view.KeyEvent.KEYCODE_BUTTON_B,
    android.view.KeyEvent.KEYCODE_ESCAPE,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AppIconTile(
    entry: LibraryEntry,
    modifier: Modifier = Modifier,
    onLaunch: () -> Unit,
    onOptions: () -> Unit = {},
    onMarkGame: () -> Unit = {},
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
            .onPad { press ->
                when (press.action) {
                    GamepadAction.A -> {
                        onLaunch()
                        true
                    }
                    // Select is Options, the focused thing's own menu
                    // (docs/SPEC.md 7j): its details, favourite and
                    // Mark as game. X and Y are the list's (Filter and
                    // Sort By) and bubble to the section around the grid.
                    GamepadAction.SELECT -> {
                        onOptions()
                        true
                    }
                    else -> false
                }
            }
            .declaresHints(APP_TILE_HINTS)
            .focusable()
            // Same real touch-input fix as GameCard -- see its own
            // comment. A long press marks the app as a game (or not),
            // the touch route to the same answer the options menu gives.
            .combinedClickable(onClick = onLaunch, onLongClick = onMarkGame)
            // The shell's ONE selection idiom, the same one [GameCard]
            // and the menus draw: the accent ring over a brightened
            // surface. This tile kept a third one -- a thin white
            // rectangle over an unchanged card -- after the cards were
            // fixed (rig, build 546), which is two answers to "what does
            // selected look like" on two grids of the same shell.
            .selectionFrame(
                selected = focused,
                shape = RoundedCornerShape(16.dp),
                rest = Color.Transparent,
                restOutline = MenuTokens.CardOutline,
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(MenuTokens.Card, RoundedCornerShape(16.dp)),
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
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).focusMarquee(focused),
        )
        Text(
            entry.kindLine(),
            color = MenuTokens.OnSurfaceMuted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.focusMarquee(focused),
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
internal fun GameCard(
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
            .onPad { press ->
                when (press.action) {
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
            .focusLift(focused, RoundedCornerShape(12.dp))
            .selectionFrame(
                selected = focused,
                shape = RoundedCornerShape(12.dp),
                rest = MenuTokens.Surface,
                restOutline = MenuTokens.CardOutline,
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
                        Brush.verticalGradient(listOf(Color.Transparent, MenuTokens.Scrim)),
                    )
                    .padding(12.dp),
            ) {
                Column {
                    // Constrained to the tile: a long name (and an app
                    // named after its own class is the longest of all)
                    // used to run past the card and nearly collide with
                    // its neighbour's.
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
