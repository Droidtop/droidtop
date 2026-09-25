package dev.droidtop.app

import android.content.Intent
import android.os.Build
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.droidtop.hostbridge.ClipboardBridge
import dev.droidtop.library.Library
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.runtime.ContainerApp
import dev.droidtop.runtime.ContainerApplications
import dev.droidtop.runtime.ContainerTerminal
import dev.droidtop.runtime.DisplayOutputKind
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.shell.desktop.DesktopSessionMessage
import dev.droidtop.shell.desktop.DesktopShell
import dev.droidtop.shell.gamepad.GamepadShell
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.OnboardingGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Not an app-drawer entry point — droidtop defaults to the normal Android
 * home-screen experience (`com.android.launcher3.Launcher`, forked in as
 * `:shell-default`'s "Standard" shell; see that module's README and its own
 * AndroidManifest.xml for the real HOME/LAUNCHER intent-filter). This
 * Activity only ever gets started by explicit Intent from a long-press of
 * the back key (`BackButtonMenu`, wired into both `Launcher` and here — see
 * that class's own doc comment), carrying [BackButtonMenu.EXTRA_MODE] to say
 * which of the two non-Standard shells to render.
 *
 * `android:launchMode="singleTask"` (see AndroidManifest.xml) + [onNewIntent]
 * below are both required, not just one or the other: without singleTask,
 * `FLAG_ACTIVITY_NEW_TASK` from [BackButtonMenu] can spawn a second
 * MainActivity instance instead of reusing the running one; without
 * overriding onNewIntent, Android's documented behavior for re-launching an
 * activity that's already the top of its task is to just bring it forward
 * with its *original* Intent/mode still in effect, silently dropping
 * whatever mode the new Intent asked for. This was a real, confirmed bug —
 * once Desktop mode had opened once, no `EXTRA_MODE` switch back to Gaming
 * (or vice versa) could ever take effect, because onCreate (where `mode` was
 * read) never ran a second time.
 *
 * Desktop mode starts [DesktopSessionService] and observes its
 * [DesktopSessionService.state]. [DesktopShell] renders that
 * Idle/Connecting/Failed/Connected state distinctly via its own
 * [dev.droidtop.shell.desktop.DesktopSessionMessage] rather than a single
 * generic placeholder, including what a booting container last reported.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var library: Library
    private var mode by mutableStateOf<Mode?>(null)

    // Real bug this avoids: MainActivity is android:launchMode="singleTask",
    // so a deep-link Intent from SettingsGamingFragment (FLAG_ACTIVITY_
    // NEW_TASK against this same Activity) almost always resolves through
    // onNewIntent, not onCreate, whenever Gaming mode is already running
    // -- the common case, not an edge case, since the whole point of these
    // deep links is jumping back INTO an already-open Gaming session.
    // Reading `intent.getStringExtra(...)` directly inside `setContent`
    // would silently do nothing then: `mode` often doesn't change (already
    // MODE_GAMING), so nothing triggers GamepadShell to recompose with
    // the new extras. A separate token, bumped on every onCreate/onNewIntent
    // and read by GamepadShell via LaunchedEffect(token), fires every real
    // deep-link regardless of whether `mode` itself changed or the extras'
    // own values happen to repeat (e.g. "Rescan library" pressed twice).
    private var gamingDeepLinkToken by mutableStateOf(0)
    private var gamingStartSection by mutableStateOf<String?>(null)
    private var gamingTriggerRescan by mutableStateOf(false)
    private var gamingTriggerBrowseThemes by mutableStateOf(false)

    // Re-runs the dual-screen role orchestration on demand (home-press
    // reinit, explicit shell re-entry, a game launch) -- display
    // attach/detach events alone don't cover those triggers.
    private val roleRefresh = kotlinx.coroutines.flow.MutableStateFlow(0)

    // The display ids the last orchestration pass saw. A CHANGE to this
    // set is a real topology event (a screen plugged in or unplugged),
    // which must not be swallowed by the relocation cooldown -- that
    // cooldown exists to stop a relaunch LOOP, and "the hardware changed"
    // is the opposite of a loop. Without this, plugging a screen in
    // within the cooldown window did nothing at all until something else
    // happened to retrigger orchestration.
    private var lastDisplayIds: Set<Int> = emptySet()

    // Last seen DisplayArrangement.refresh value, so an explicit swap is
    // distinguishable from an ordinary re-emission.
    private var lastArrangementSeq: Int = -1

    /**
     * The host↔container clipboard bridge for the CURRENT desktop session,
     * rebuilt whenever the session's HostBridge changes and torn down with
     * it. Lives here rather than in DesktopSessionService because Android
     * only lets the focused app (or the active IME's owner) read the
     * clipboard — window focus is an Activity fact, and a Service has none
     * to report.
     */
    private var clipboardBridge: ClipboardBridge? = null

    // The LIVE companion window on the second screen, owned by this
    // foreground shell. :display's SecondaryDisplayActivity is the IDLE
    // surface underneath it; see that class and SecondScreenPresentation
    // for why droidtop needs both rather than one.
    private var secondScreenPresentation: SecondScreenPresentation? = null

    // This instance's companion tap-to-launch seam -- kept so onDestroy
    // can identity-check before clearing the process-wide hook.
    private var companionLaunchSeam: ((dev.droidtop.library.LibraryEntry) -> Unit)? = null

    /**
     * Re-runs orchestration from scratch: drops the parked display and the
     * relocation cooldown so the next pass really acts rather than being
     * suppressed as a repeat attempt. Called by the double-tap-home hard
     * reinit and by [dev.droidtop.runtime.DisplayArrangement]'s own
     * swap/reinitialize actions.
     */
    fun reinitializeDisplays() {
        dev.droidtop.library.LaunchDisplay.parkedDisplayId = null
        lastRelocationAttemptMs = 0L
        relocationAttempts = 0
        lastDisplayIds = emptySet()
        roleRefresh.value++
    }

    companion object DisplayRelocation {
        // Process-wide (companion), not per-instance: the relaunch loop
        // recreates the Activity, so an instance field would reset each
        // hop and guard nothing.
        @Volatile
        private var lastRelocationAttemptMs = 0L
        private const val RELOCATION_COOLDOWN_MS = 5000L

        // How many relocation attempts this display topology has consumed
        // without the shell verifiably ending up on the addon. Once
        // DualScreenOrchestration.relocationHasFailed says so, the
        // orchestration stops fighting a display that refuses activity
        // launches and falls back to shell-on-built-in with the live
        // companion covering the addon -- so the addon shows droidtop's
        // surface instead of a mirror. Reset wherever the cooldown is.
        @Volatile
        private var relocationAttempts = 0
    }

    private fun applyGamingDeepLink(intent: Intent) {
        gamingStartSection = intent.getStringExtra(BackButtonMenu.EXTRA_GAMING_START_SECTION)
        gamingTriggerRescan = intent.getBooleanExtra(BackButtonMenu.EXTRA_GAMING_RESCAN, false)
        gamingTriggerBrowseThemes = intent.getBooleanExtra(BackButtonMenu.EXTRA_GAMING_BROWSE_THEMES, false)
        gamingDeepLinkToken++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyGamingDeepLink(intent)

        // Real gap this closes: OnboardingGate was only ever called from
        // LauncherApplication.java (Standard's own boot) -- a user who
        // launches straight into Desktop/Gaming (droidtop not set as
        // system HOME, or opened via BackButtonMenu/EXTRA_MODE directly)
        // never saw onboarding at all. Both real entry points need this,
        // not just one.
        OnboardingGate.launchIfNeeded(this)

        // One library per process, built by the shared core rather than
        // here: launch resolution must work with Gaming and Desktop both
        // off, and this Activity does not run then (LibraryCore).
        library = LibraryCore.library(applicationContext)

        refreshModeIfUndecided()

        // Tap-to-launch from the companion surface (its recent-games
        // rail): the companion renders on a screen the user is not
        // driving with the gamepad, so touch is its input, and a launch
        // from there goes through the SAME Library.launch path as the
        // shell's own -- play history, launch-screen memory, error
        // logging included. One launch mechanism, two entry points.
        companionLaunchSeam = { entry ->
            lifecycleScope.launch {
                CompanionState.launchError.value = null
                runCatching { library.launch(entry) }
                    .onFailure {
                        android.util.Log.e("droidtop.MainActivity", "Companion launch of ${entry.title} failed", it)
                        // The shell's own wording for the same failure.
                        CompanionState.launchError.value = "Couldn't launch ${entry.title}: ${it.message}"
                    }
            }
        }
        CompanionState.onLaunchEntry = companionLaunchSeam

        observeSecondScreen()
        observeClipboardBridge()

        setContent {
            // DroidtopTheme provides Material tokens for droidtop's own
            // chrome (DesktopShell panels etc.); the Gaming shell's
            // ES-DE-themed surfaces take their colors from the active
            // ES-DE theme instead and simply don't read these tokens.
            dev.droidtop.app.ui.DroidtopTheme {
            // Two clocks disagreeing on one screen is what a themed view
            // under a visible system status bar looks like (portrait
            // capture, 2026-09-11: the OS bar read 5:14 and the theme's
            // own clock 17:14). A themed view owns its whole surface, so
            // in Gaming mode droidtop goes immersive and the theme's clock
            // is the only one. Every other mode keeps the system bars: a
            // home screen and a desktop both want them.
            LaunchedEffect(mode) { applySystemBars(mode) }
            when (mode) {
                Mode.GAMING -> GamepadShell(
                    library = library,
                    onFocusedEntryChanged = { CompanionState.focusedEntry.value = it },
                    // The companion's idle rotation draws from this
                    // (docs/SPEC.md section 4d). Published here rather
                    // than scanned there: the companion renders on a
                    // screen the user is not driving and must not do
                    // work of its own.
                    onEntriesChanged = { CompanionState.libraryEntries.value = it },
                    deepLinkToken = gamingDeepLinkToken,
                    startSectionName = gamingStartSection,
                    triggerRescan = gamingTriggerRescan,
                    triggerBrowseThemes = gamingTriggerBrowseThemes,
                )
                Mode.DESKTOP -> {
                    val sessionState by DesktopSessionService.state.collectAsState()
                    val desktopLaunchFailure by DesktopSessionService.launchFailure.collectAsState()
                    val connected = sessionState as? DesktopSessionState.Connected
                    DesktopShell(
                        library = library,
                        hostBridge = connected?.hostBridge,
                        primaryOutput = connected?.primaryOutput,
                        sessionMessage = when (val state = sessionState) {
                            is DesktopSessionState.Idle -> DesktopSessionMessage.Idle
                            is DesktopSessionState.Connecting -> DesktopSessionMessage.Connecting(state.detail)
                            is DesktopSessionState.Connected -> DesktopSessionMessage.Idle
                            is DesktopSessionState.Failed -> DesktopSessionMessage.Failed(state.message)
                        },
                        // Programs (the terminal, the Start menu's Linux apps)
                        // run in the session, not in this screen: see
                        // DesktopSessionService.runInPrimary. Only offered with
                        // a live session -- see DesktopShell's own comment on
                        // why the button is absent rather than disabled.
                        onOpenTerminal = connected?.let {
                            {
                                DesktopSessionService.runInPrimary { runtime, container ->
                                    ContainerTerminal.failureMessage(ContainerTerminal.open(runtime, container))
                                }
                                Unit
                            }
                        },
                        loadLinuxApps = connected?.let { session ->
                            suspend { ContainerApplications.list(session.runtime, session.container) }
                        },
                        onLaunchLinuxApp = connected?.let {
                            { app: ContainerApp ->
                                DesktopSessionService.runInPrimary { runtime, container ->
                                    ContainerApplications.launch(runtime, container, app)
                                }
                                Unit
                            }
                        },
                        launchFailure = desktopLaunchFailure,
                        onDismissLaunchFailure = { DesktopSessionService.dismissLaunchFailure() },
                        onLaunchFailure = { DesktopSessionService.reportLaunchFailure(it) },
                    )
                }
                // Nothing to render: both app-hosted modes are off, and
                // refreshModeIfUndecided has already handed back to the
                // launcher. Deliberately blank rather than falling through
                // to Desktop, which is what this branch used to do.
                Mode.LAUNCHER, null -> Unit
            }
            }
        }
    }


    /**
     * The system bars' one decision point. Gaming mode is immersive --
     * the ES-DE theme draws its own clock, help row and battery, and a
     * second set above it is two answers to one question (SPEC 7k, "one
     * help/hint bar per screen"; the clock defect was found on the
     * portrait rig). Transient-by-swipe, so the bars are still reachable.
     */
    private fun applySystemBars(mode: Mode?) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (mode == Mode.GAMING) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyGamingDeepLink(intent)
        val previous = mode
        mode = resolveMode(intent)
        // Leaving Desktop for another shell ends the desktop session: it is
        // Desktop mode's, and a compositor nobody can see must not keep
        // running behind Gaming (rig, dq-coordinator-23 F9).
        if (previous == Mode.DESKTOP && mode != Mode.DESKTOP) DesktopSessionService.stop(this)
        startDesktopSessionIfDesktop()
        // Display reinit on every re-entry (a HOME press routes here via
        // Launcher.onNewIntent's forwarding, carrying EXTRA_DISPLAY_REINIT).
        // An EXPLICIT shell entry (BackButtonMenu's Gaming item, no
        // reinit extra) also reclaims a display an app was launched onto;
        // the home-press reinit deliberately does NOT -- "fix my screens"
        // must never cover a running game (see LaunchDisplay.parkedDisplayId).
        if (!intent.getBooleanExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT, false) &&
            mode == Mode.GAMING
        ) {
            dev.droidtop.library.LaunchDisplay.parkedDisplayId = null
        }
        // HARD reinit (double-tap home, per direction): re-assert both
        // displays regardless of what's running -- clear the parked
        // display AND the relocation cooldown so the orchestration acts
        // immediately instead of waiting out the guard window.
        if (intent.getBooleanExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT_FORCE, false)) {
            reinitializeDisplays()
        } else {
            roleRefresh.value++
        }
    }

    /**
     * Real bug this closes, confirmed on a real device: `mode` used to be
     * resolved exactly once, in `onCreate`, and never re-checked. When
     * `OnboardingGate.launchIfNeeded` (called just above, in `onCreate`)
     * pushes `OnboardingActivity` on top of this same task *before*
     * onboarding has actually set the last mode to anything real,
     * [resolveMode] has nothing to resolve to yet and returns `null` --
     * which the `when(mode)` below's `else` branch silently treats as
     * Desktop. That's the correct behavior for "genuinely undecided," but
     * this Activity instance never got a chance to reconsider once the
     * user actually finished onboarding and picked Gaming: finishing a
     * child Activity that was merely stacked on top (not `startActivity`'d
     * with new-task/single-top semantics against *this* Activity) resumes
     * this Activity via `onResume`, not `onNewIntent` -- so `mode` stayed
     * frozen at its original `null` forever, and the user landed on
     * Desktop (which then fails outright, since it was never set up)
     * instead of the Gaming they actually chose. Re-resolving here,
     * gated on `mode == null` so an already-decided mode is never stomped
     * mid-session, is what actually fixes it.
     */
    override fun onResume() {
        super.onResume()
        refreshModeIfUndecided()
        // Where the second screen's trackpad sends navigation keys in
        // Gaming mode: this window, through ordinary dispatchKeyEvent.
        // See ForegroundShell for why that is the only route available.
        ForegroundShell.set(this)
    }

    override fun onPause() {
        // Cleared rather than left stale, so a trackpad swipe cannot
        // deliver keys into a window the user has navigated away from.
        if (ForegroundShell.current() === this) ForegroundShell.set(null)
        super.onPause()
    }

    private fun refreshModeIfUndecided() {
        if (mode != null) return
        mode = resolveMode(intent)
        startDesktopSessionIfDesktop()
    }

    /**
     * The desktop session is Desktop mode's, and only Desktop mode's. It
     * used to start for every mode that was not Gaming -- including the
     * "undecided" null, which meant a device with Desktop switched off
     * still started a foreground service for a session that could never
     * connect.
     */
    private fun startDesktopSessionIfDesktop() {
        if (mode != Mode.DESKTOP) return
        DesktopSessionService.start(this)
    }

    /**
     * Prefers an explicit [BackButtonMenu.EXTRA_MODE] (a real user choice,
     * from [BackButtonMenu] or Launcher's own cold-boot redirect — see the
     * "droidtop patch" in `Launcher.onCreate`); then a real, user-set
     * [Modes.defaultMode] (Global settings' own "Default mode" picker),
     * if one is set and its mode is still enabled — a disabled mode can't
     * silently become the resolved mode just because it's still saved as
     * the default; falls back to [Modes]'s last app-hosted mode when
     * neither applies, so this Activity resumes correctly even if launched
     * by something that didn't set the extra. Persists whatever mode is
     * resolved as a safety net — every known real caller already does this
     * before launching, but a null write here would be wrong (it would
     * forget the real last mode).
     */
    private fun resolveMode(intent: Intent): Mode? {
        val resolved = Modes.resolveAppMode(this, intent.getStringExtra(BackButtonMenu.EXTRA_MODE))
        if (resolved != null) {
            Modes.setLastMode(this, resolved)
        } else if (!isFinishing) {
            // Neither app-hosted mode is enabled, so there is nothing for
            // this Activity to be. Hand back to the launcher rather than
            // show an empty window.
            Modes.setLastMode(this, Mode.LAUNCHER)
            finish()
        }
        return resolved
    }

    /**
     * Keeps exactly one [ClipboardBridge] alive per live HostBridge. A
     * session that goes away and comes back gets a fresh bridge rather than
     * one still holding the previous connection's synced text.
     */
    private fun observeClipboardBridge() {
        lifecycleScope.launch {
            DesktopSessionService.state.collectLatest { state ->
                val hostBridge = (state as? DesktopSessionState.Connected)?.hostBridge
                if (hostBridge == null) {
                    clipboardBridge?.stop()
                    clipboardBridge = null
                    return@collectLatest
                }
                clipboardBridge?.stop()
                clipboardBridge = ClipboardBridge(applicationContext, hostBridge).also {
                    it.start()
                    // The Activity is already focused by the time a session
                    // connects, and nothing will tell the new bridge that
                    // unless it is told here -- without this its first read
                    // would wait for a focus change that may never come.
                    it.onWindowFocusChanged(hasWindowFocus())
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        clipboardBridge?.onWindowFocusChanged(hasFocus)
        // The bars were hidden only when the mode changed. Any other window
        // that shows them -- Android's own settings screens opened from
        // Settings, a permission dialog, the notification shade -- left them
        // over the shell's tab bar for the rest of the session, even on the
        // themed carousel (UI pass 2026-09-24, H1). Coming back to the front
        // is when the decision has to be made again.
        if (hasFocus) applySystemBars(mode)
    }

    /**
     * Dual-screen role orchestration (docs/SPEC.md §4, Gaming-mode
     * dual-screen roles — directed after the first live addon session):
     * when a second display is present and [dev.droidtop.runtime.MainScreen]
     * says so (the default), the SHELL ITSELF moves to it (the
     * addon is the upper/main screen) and the built-in screen gets the
     * widgets panel ([CompanionActivity] — a real Activity, since
     * `Presentation` can only target non-default displays). The
     * companion path stays as the real implementation of
     * the other choice (shell on built-in, widgets on the addon). Launch
     * ordering under SECOND_WHEN_PRESENT: companion first, then the shell
     * task moves (singleTask + setLaunchDisplayId relocates this same
     * instance) — so window focus, and every gamepad event with it, ends
     * on the shell. Also maintains [LaunchDisplay.targetDisplayId] — the
     * launcher-wide "games launch on which display" setting. Desktop mode
     * relocates the same way (§4c, external screen priority) but takes no
     * part in game launch targeting: its windows are the compositor's.
     */
    private fun observeSecondScreen() {
        val displayOutputs = DisplayOutputRepository(applicationContext)
        val displayManager = getSystemService(DisplayManager::class.java)

        // A game launch also retriggers orchestration (so the widgets
        // Presentation is dismissed off a display a game just went to --
        // Presentation windows layer ABOVE activities on that display).
        dev.droidtop.library.LaunchDisplay.onLaunched = { roleRefresh.value++ }

        // The mirroring fix (docs/SPEC.md section 4c): before a launch is
        // dispatched, droidtop's idle surface is placed explicitly on any
        // secondary display the launch would otherwise leave empty. An
        // empty secondary display MIRRORS the default display -- that is
        // Android's fallback, and it was the "launching apps mirrors
        // them" report. The platform's own SECONDARY_HOME placement can't
        // be relied on for this (it needs the HOME role AND system decor
        // support on that display), so droidtop places its own. The
        // covering surface starts BEFORE the game so the game's window
        // lands last and keeps input focus. Which displays qualify is
        // pure and unit-tested (DualScreenOrchestration).
        dev.droidtop.library.LaunchDisplay.coverVacatedDisplays = { launchTarget ->
            val outputs = displayOutputs.currentOutputsSnapshot()
            val secondaryIds = outputs
                .filter { it.kind == DisplayOutputKind.SECOND_SCREEN }
                .map { it.androidDisplayId }
            val shellDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.displayId
            }
            dev.droidtop.runtime.DualScreenOrchestration
                .displaysNeedingIdleCover(
                    secondaryDisplayIds = secondaryIds,
                    launchTargetDisplayId = launchTarget,
                    shellDisplayId = shellDisplayId,
                    parkedDisplayId = dev.droidtop.library.LaunchDisplay.parkedDisplayId,
                )
                .forEach { displayId ->
                    runCatching {
                        startActivity(
                            Intent(this@MainActivity, dev.droidtop.display.SecondaryDisplayActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            android.app.ActivityOptions.makeBasic()
                                .setLaunchDisplayId(displayId)
                                .toBundle(),
                        )
                    }.onFailure {
                        android.util.Log.w("droidtop.MainActivity", "Idle cover on display $displayId refused", it)
                    }
                }
        }

        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                displayOutputs.observe(),
                roleRefresh,
                // Swapping panels writes a preference and changes nothing
                // Android reports, so without this the new assignment
                // would sit unused until some unrelated display event.
                dev.droidtop.runtime.DisplayArrangement.refresh,
            ) { outputs, _, arrangement -> outputs to arrangement }
                .collectLatest { pair ->
                val (outputs, arrangementSeq) = pair
                // An explicit swap or reinitialize must actually act: it
                // leaves the display id set identical, so the check below
                // would never clear the cooldown for it.
                if (arrangementSeq != lastArrangementSeq) {
                    lastArrangementSeq = arrangementSeq
                    dev.droidtop.library.LaunchDisplay.parkedDisplayId = null
                    lastRelocationAttemptMs = 0L
                    relocationAttempts = 0
                }
                val displayIds = outputs.map { it.androidDisplayId }.toSet()
                if (displayIds != lastDisplayIds) {
                    lastDisplayIds = displayIds
                    lastRelocationAttemptMs = 0L
                    relocationAttempts = 0
                }
                // A shell left on a display that no longer exists is the
                // unplug case: Android does not necessarily bring the
                // activity home by itself, and a shell nobody can see is
                // indistinguishable from a crash. Come back to the
                // built-in screen immediately, ahead of any role logic.
                val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.displayId
                }
                if (currentDisplay != android.view.Display.DEFAULT_DISPLAY && currentDisplay !in displayIds) {
                    dev.droidtop.library.LaunchDisplay.parkedDisplayId = null
                    startActivity(
                        Intent(intent).setClass(this@MainActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        android.app.ActivityOptions.makeBasic()
                            .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
                            .toBundle(),
                    )
                    return@collectLatest
                }
                val second = outputs.firstOrNull { it.kind == DisplayOutputKind.SECOND_SCREEN }
                val gaming = mode == Mode.GAMING
                val desktop = mode == Mode.DESKTOP
                // Always current, whatever the mode: LaunchDisplay resolves
                // a remembered "add-on screen" choice through this.
                dev.droidtop.library.LaunchDisplay.secondDisplayId = second?.androidDisplayId
                // A display an app was launched onto is PARKED: droidtop
                // keeps its hands off it entirely (no shell relocation, no
                // widgets Presentation over the running app) until an
                // explicit shell entry reclaims it -- the "don't interfere
                // with apps we've launched" half of the home-press reinit.
                val parked = dev.droidtop.library.LaunchDisplay.parkedDisplayId
                val secondAvailable = second != null && second.androidDisplayId != parked
                // Which panel is the main output: one persisted, relative
                // choice (MainScreen), flipped by Swap screens and set by
                // the Main screen row. Read off the main thread -- the
                // first read loads a preferences file from disk.
                val mainScreen = withContext(Dispatchers.IO) {
                    dev.droidtop.runtime.MainScreen.choice(applicationContext)
                }
                // Gaming AND Desktop both put the shell on the addon by
                // default -- the add-on is the better surface and droidtop
                // treats it as the main output, not an afterthought (per
                // direction; docs/SPEC.md section 4). Standard stays with
                // the platform's own Launcher3 secondary-display handling.
                val wantShellOnSecond = (gaming || desktop) && secondAvailable &&
                    mainScreen == dev.droidtop.runtime.MainScreenChoice.SECOND_WHEN_PRESENT
                // Relocation give-up: a display can refuse activity
                // launches, and after MAX_RELOCATION_ATTEMPTS whole
                // cooldown windows without the shell actually being on the
                // addon, fall back to shell-on-built-in with the live
                // companion covering the addon. Without this, a refusing
                // addon left the shell built-in AND the addon empty --
                // which Android renders as a mirror of the built-in panel.
                val relocationGaveUp = currentDisplay != second?.androidDisplayId &&
                    dev.droidtop.runtime.DualScreenOrchestration.relocationHasFailed(relocationAttempts)
                val shellOnSecond = wantShellOnSecond && !relocationGaveUp

                val launchTarget = if (gaming && second != null) DisplayRolePrefs.gameLaunchTarget(this@MainActivity) else null
                dev.droidtop.library.LaunchDisplay.targetDisplayId = when (launchTarget) {
                    null, DisplayRolePrefs.GameLaunchTarget.ASK, DisplayRolePrefs.GameLaunchTarget.BUILT_IN -> null
                    DisplayRolePrefs.GameLaunchTarget.FOLLOW_SHELL ->
                        if (shellOnSecond) second!!.androidDisplayId else null
                    DisplayRolePrefs.GameLaunchTarget.SECOND -> second!!.androidDisplayId
                }
                // Per direction, ASK is the default: with two displays and
                // no explicit target, every launch asks which screen via
                // the shell's chooser (LaunchDisplay.chooser).
                dev.droidtop.library.LaunchDisplay.askOptions =
                    if (launchTarget == DisplayRolePrefs.GameLaunchTarget.ASK) {
                        // Relative first, absolute only as the clarifier
                        // (docs/SPEC.md section 4c), and the ADD-ON row
                        // first in both arrangements so the
                        // default-highlighted choice is the better screen.
                        // Candidate ordering is pure and unit-tested.
                        dev.droidtop.runtime.DualScreenOrchestration
                            .chooserCandidates(second!!.androidDisplayId, shellOnSecond)
                            .map { dev.droidtop.library.LaunchDisplayOption(it.displayId, it.label) }
                    } else {
                        null
                    }

                // Two surfaces cover the second display (docs/SPEC.md 4c):
                // :display's SECONDARY_HOME activity is the IDLE one the
                // platform places while droidtop is not foreground, and
                // the Presentation below is the LIVE one this foreground
                // shell owns. SECONDARY_HOME never applies to the DEFAULT
                // display, so when the addon is the main output the shell
                // moves there and the built-in gets CompanionActivity.
                //
                // Second screen, shell NOT on it: this shell is foreground
                // on the built-in panel, so it drives the companion
                // directly as a Presentation. The SECONDARY_HOME activity
                // stays underneath as the idle surface for when droidtop
                // is not foreground; this window sits above it while it is.
                //
                // No longer gated on `gaming`: Desktop mode wants this
                // window too, because that is where its second-screen
                // keyboard and trackpad live (docs/SPEC.md 4, 6c), and the
                // live window is the one that exists whether or not
                // droidtop holds the home role.
                if (!shellOnSecond && second != null && secondAvailable) {
                    if (secondScreenPresentation?.display?.displayId != second.androidDisplayId) {
                        secondScreenPresentation?.dismiss()
                        val display = displayManager.getDisplay(second.androidDisplayId)
                        secondScreenPresentation = display?.let {
                            SecondScreenPresentation(applicationContext, it).also { p -> p.show() }
                        }
                    }
                } else {
                    // No second display, one parked by a launched app (a
                    // Presentation would layer ABOVE that app), or the
                    // shell itself lives there.
                    secondScreenPresentation?.dismiss()
                    secondScreenPresentation = null
                }

                if (shellOnSecond && second != null) {
                        val currentDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
                        } else {
                            @Suppress("DEPRECATION")
                            windowManager.defaultDisplay.displayId
                        }
                        // ONE relocation attempt per cooldown window --
                        // confirmed-live relaunch loop this guards: right
                        // after the relocation startActivity, the
                        // (re)created instance can still read its display
                        // as DEFAULT before window attach, see a mismatch
                        // here, and relaunch again, forever. If relocation
                        // genuinely didn't take after an attempt (some
                        // displays refuse activity launches), the shell
                        // stays where it is instead of looping.
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (currentDisplayId == second.androidDisplayId) {
                            // Relocation verifiably took; the give-up
                            // counter starts over for this topology.
                            relocationAttempts = 0
                        }
                        if (currentDisplayId != second.androidDisplayId &&
                            now - lastRelocationAttemptMs > RELOCATION_COOLDOWN_MS
                        ) {
                            lastRelocationAttemptMs = now
                            relocationAttempts++
                            // Companion FIRST (built-in screen), then move
                            // this singleTask instance to the addon so the
                            // shell ends up focused.
                            // Companion explicitly on the BUILT-IN display:
                            // startActivity without options launches on the
                            // CALLER's display, which after relocation is
                            // the addon -- confirmed live: the companion
                            // landed behind the shell on the addon and the
                            // built-in screen kept showing the Standard
                            // launcher.
                            // A display is allowed to refuse activity
                            // launches (SecurityException); that must count
                            // as a failed attempt and re-run orchestration
                            // -- with the give-up policy above, that path
                            // ends at shell-on-built-in with the companion
                            // covering the addon, never at a crash or a
                            // mirrored addon.
                            runCatching {
                                startActivity(
                                    Intent(this@MainActivity, CompanionActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    android.app.ActivityOptions.makeBasic()
                                        .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
                                        .toBundle(),
                                )
                                startActivity(
                                    Intent(intent).setClass(this@MainActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    android.app.ActivityOptions.makeBasic()
                                        .setLaunchDisplayId(second.androidDisplayId)
                                        .toBundle(),
                                )
                            }.onFailure {
                                android.util.Log.w("droidtop.MainActivity", "Relocation to display ${second.androidDisplayId} refused", it)
                                // A synchronous refusal is definitive --
                                // no point burning the remaining attempts.
                                relocationAttempts = dev.droidtop.runtime.DualScreenOrchestration.MAX_RELOCATION_ATTEMPTS
                                roleRefresh.value++
                            }
                        } else if (currentDisplayId == second.androidDisplayId && !CompanionActivity.visible &&
                            now - lastRelocationAttemptMs > RELOCATION_COOLDOWN_MS
                        ) {
                            // Same cooldown as relocation: the companion's
                            // visible flag races its own onStart, and an
                            // unguarded re-assert would ping-pong.
                            lastRelocationAttemptMs = now
                            // Already relocated but the built-in screen
                            // lost/never showed the companion (confirmed
                            // live: it stayed on Android Settings after
                            // the shell moved). Re-assert it, then
                            // re-front this shell so gamepad focus stays
                            // here, not on the companion.
                            // Built-in display explicitly -- see the
                            // relocation branch above for the confirmed
                            // caller's-display default.
                            startActivity(
                                Intent(this@MainActivity, CompanionActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                android.app.ActivityOptions.makeBasic()
                                    .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
                                    .toBundle(),
                            )
                            startActivity(
                                Intent(intent).setClass(this@MainActivity, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                }
            }
        }
    }

    /**
     * A foldable changes the shape of the SAME display rather than
     * adding one, so unfolding arrives here (the activity survives it --
     * see this activity's own configChanges) and not necessarily through
     * DisplayListener. Role orchestration has to re-run either way: an
     * unfolded inner screen can be a different enough surface to deserve
     * a different role, and the cooldown is cleared for the same reason
     * a topology change clears it.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        lastRelocationAttemptMs = 0L
        relocationAttempts = 0
        roleRefresh.value++
    }

    override fun onStop() {
        super.onStop()
        // The shell is no longer foreground -- a game may have started, or
        // the user switched apps. The live companion window has to come
        // down (leaving it up would layer it over whatever now runs on
        // that display), but the display it covered must not be left
        // EMPTY: an empty secondary display mirrors the default one. So
        // the idle surface is asserted there first, best-effort -- this
        // Activity is still visible during onStop, and a refusal is
        // logged, never fatal. Launches droidtop itself dispatched
        // already covered their displays via coverVacatedDisplays.
        secondScreenPresentation?.let { presentation ->
            val displayId = presentation.display?.displayId
            if (displayId != null && displayId != dev.droidtop.library.LaunchDisplay.parkedDisplayId) {
                runCatching {
                    startActivity(
                        Intent(this, dev.droidtop.display.SecondaryDisplayActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        android.app.ActivityOptions.makeBasic()
                            .setLaunchDisplayId(displayId)
                            .toBundle(),
                    )
                }.onFailure {
                    android.util.Log.w("droidtop.MainActivity", "Idle cover on display $displayId refused at onStop", it)
                }
            }
        }
        secondScreenPresentation?.dismiss()
        secondScreenPresentation = null
    }

    override fun onStart() {
        super.onStart()
        // Coming back to the foreground re-asserts the live companion,
        // through the same orchestration pass everything else uses.
        roleRefresh.value++
    }

    override fun onDestroy() {
        secondScreenPresentation?.dismiss()
        secondScreenPresentation = null
        // The companion's launch seam captures this instance's scope and
        // library; a destroyed Activity must not be reachable through it.
        // Identity-guarded: the relocation flow creates the NEW instance
        // (which installs its own seam) before the old one is destroyed,
        // and the old one must not tear the new one's seam down.
        if (CompanionState.onLaunchEntry === companionLaunchSeam) {
            CompanionState.onLaunchEntry = null
        }
        clipboardBridge?.stop()
        clipboardBridge = null
        super.onDestroy()
    }

    // Same long-press-of-back shell switcher as Launcher — see
    // BackButtonMenu's doc comment for why long-press rather than a plain
    // back press (which keeps doing its normal job, here just finishing
    // this Activity).
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            BackButtonMenu.show(this)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }
}
