package dev.droidtop.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import dev.droidtop.runtime.nameOf
import dev.droidtop.runtime.DualScreenOrchestration
import dev.droidtop.display.SecondScreenHost
import dev.droidtop.display.SecondScreenOrchestrator
import dev.droidtop.shell.desktop.DesktopSessionMessage
import dev.droidtop.shell.desktop.DesktopShell
import dev.droidtop.shell.gamepad.GamepadShell
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.OnboardingGate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
class MainActivity : AppCompatActivity(), SecondScreenHost {

    // Lazy: only ever touched from onResume/onPause/dispatchGenericMotionEvent/
    // dispatchKeyEvent, all on the main thread, well after the window (and
    // this Activity's own dispatchKeyEvent target) exists. See
    // GamepadAxisNav's own doc comment -- Droidtop/tracker#1, a real
    // gamepad's D-pad/stick reported through the joystick MotionEvent axes
    // rather than real KeyEvents.
    private val gamepadAxisNav by lazy { gamepadAxisNavFor(this, Handler(Looper.getMainLooper())) }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        gamepadAxisNav.onGenericMotionEvent(event)
        return super.dispatchGenericMotionEvent(event)
    }

    /**
     * Two jobs, both Droidtop/tracker#1/#43 (owner, on the console with a
     * real gamepad): teach [gamepadAxisNav] about a (device, key) pair that
     * ALSO sends real DPAD KeyEvents (so it stops translating just that
     * exact key's own hat/stick motion -- a device sending both was
     * doubling every press), and log every DPAD press, its source, and
     * whether it was handled, so the owner's next on-console test can be
     * read back from logcat without a debugger, since this session cannot
     * reach the console itself to watch it live.
     *
     * `event.deviceId` is how "real" is told from "this app's own
     * synthetic dispatch": a real hardware event always carries the
     * originating `InputDevice`'s positive id; [GamepadAxisNav]'s own
     * synthetic `KeyEvent`s (this class's own `gamepadAxisNavFor`, and
     * `ForegroundShell.send` for the second screen) are built with no
     * device id at all, which defaults to 0. Per-KEY, not per-device
     * (Droidtop/tracker#1/#43, 2026-09-28): the Retroid Pocket 5's own pad
     * sends DOWN/RIGHT as real KeyEvents but UP/LEFT only through the hat
     * axis, so a device-wide dedupe here blinded [gamepadAxisNav] to the
     * hat's own UP/LEFT the moment the first real DOWN arrived -- see
     * [GamepadAxisNav]'s own "Per-KEY dedupe" doc comment.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dpadName = dpadKeyName(event.keyCode)
        if (dpadName != null && event.deviceId > 0) gamepadAxisNav.noteRealDpadKeyEvent(event.deviceId, event.keyCode)
        val handled = super.dispatchKeyEvent(event)
        if (dpadName != null) {
            val edge = when (event.action) {
                KeyEvent.ACTION_DOWN -> "down"
                KeyEvent.ACTION_UP -> "up"
                else -> "action${event.action}"
            }
            val source = if (event.deviceId > 0) "key" else "axis"
            android.util.Log.d(
                "droidtop.input",
                "$dpadName edge=$edge source=$source device=${event.deviceId} repeat=${event.repeatCount} handled=$handled",
            )
        }
        return handled
    }

    private fun dpadKeyName(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        else -> null
    }

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

    // Owns the dual-screen relocation/companion decisions
    // (docs/SPEC.md section 4/4c); this Activity supplies SecondScreenHost,
    // the handful of things only the foreground Activity can do. Created
    // fresh each onCreate, like the fields it replaces -- see its own doc
    // comment for why the relocation cooldown counters are process-wide
    // instead.
    private lateinit var displayOrchestrator: SecondScreenOrchestrator

    /**
     * The host↔container clipboard bridge for the CURRENT desktop session,
     * rebuilt whenever the session's HostBridge changes and torn down with
     * it. Lives here rather than in DesktopSessionService because Android
     * only lets the focused app (or the active IME's owner) read the
     * clipboard — window focus is an Activity fact, and a Service has none
     * to report.
     */
    private var clipboardBridge: ClipboardBridge? = null

    /** Whether the desktop's notification-permission question is on screen (Desktop mode only). */
    private var askDesktopNotifications by mutableStateOf(false)

    // Android's own prompt, after droidtop's reason. The desktop runs
    // either way, so the answer needs no handling here.
    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

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
        displayOrchestrator.reinitialize()
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

        // A third crash while starting sends the app to Global settings,
        // where Data > Share diagnostics is, instead of into any shell
        // (SPEC 10c).
        if (dev.droidtop.library.diagnostics.CrashRecovery.consumeSettingsRoute(this)) {
            BackButtonMenu.openGlobalSettings(this)
            finish()
            return
        }

        // Unfinished setup is resumed instead of drawing a shell under it
        // (docs/SPEC.md 7b): onboarding ends by opening the mode it set up,
        // so there is nothing for this instance to be until then, and a
        // Gaming shell composed underneath started its library scan and
        // recorded itself as the last mode before any of that was chosen.
        if (OnboardingGate.resumeIfUnfinished(this)) {
            finish()
            return
        }

        // One library per process, built by the shared core rather than
        // here: launch resolution must work with Gaming and Desktop both
        // off, and this Activity does not run then (LibraryCore).
        library = LibraryCore.library(applicationContext)

        refreshModeIfUndecided()

        // A mode switched off while its shell is on screen leaves it: for
        // the other app-hosted mode if that one is on, else for the Android
        // home screen. Turning Gaming off in Global settings used to leave
        // the running Gaming shell fully usable, and droidtop's icon kept
        // bringing it back, until a force-stop (rig, dq-onboard-01).
        lifecycleScope.launch {
            Modes.enabledFlow.collect { enabled -> leaveIfSwitchedOff(enabled) }
        }

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

        displayOrchestrator = SecondScreenOrchestrator(applicationContext, this)
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
            // A desktop session is used through the seat, which Android's screen-off timer does
            // not count as activity, so the screen would blank mid-use (SPEC 4e). The window
            // flag holds only while this window is showing the live desktop.
            val keepAwakeState by DesktopSessionService.state.collectAsState()
            LaunchedEffect(mode, keepAwakeState is DesktopSessionState.Connected) {
                setKeepScreenOn(mode == Mode.DESKTOP && keepAwakeState is DesktopSessionState.Connected)
            }
            Box(modifier = Modifier.fillMaxSize()) {
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
                                    ContainerTerminal.failureMessage(ContainerTerminal.open(runtime, container, runtime.nameOf(container)))
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
                        onStartSession = { DesktopSessionService.start(this@MainActivity) },
                    )
                    if (askDesktopNotifications) {
                        DesktopNotificationPermission.Dialog(
                            onAllow = {
                                DesktopNotificationPermission.markAsked(this@MainActivity)
                                askDesktopNotifications = false
                                notificationPermission.launch(DesktopNotificationPermission.PERMISSION)
                            },
                            onDecline = {
                                DesktopNotificationPermission.markAsked(this@MainActivity)
                                askDesktopNotifications = false
                            },
                        )
                    }
                }
                // Nothing to render: both app-hosted modes are off, and
                // refreshModeIfUndecided has already handed back to the
                // launcher. Deliberately blank rather than falling through
                // to Desktop, which is what this branch used to do.
                Mode.LAUNCHER, null -> Unit
            }
            // Drawn OVER whichever shell is showing rather than by
            // either shell's own chrome (docs/SPEC.md section 4c): the
            // broken state it reports is a MainActivity-level fact,
            // true in Gaming and Desktop alike, and it must stay
            // reachable over a themed Gaming screen exactly as it is
            // over Desktop's own panels.
            val safeMode by dev.droidtop.library.theme.ThemeSafeMode.activeFlow.collectAsState()
            if (mode == Mode.GAMING && safeMode) {
                SafeModeBanner(
                    onRetry = { dev.droidtop.library.diagnostics.CrashRecovery.retryTheme(this@MainActivity) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            if (mode == Mode.GAMING || mode == Mode.DESKTOP) {
                ReinitializeDisplaysPill(
                    onClick = { reinitializeDisplays() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 72.dp),
                )
            }
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
    private fun setKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

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
            dev.droidtop.library.LaunchDisplay.clearRunning()
        }
        // HARD reinit (double-tap home, per direction): re-assert both
        // displays regardless of what's running -- clear the parked
        // display AND the relocation cooldown so the orchestration acts
        // immediately instead of waiting out the guard window.
        if (intent.getBooleanExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT_FORCE, false)) {
            reinitializeDisplays()
        } else {
            displayOrchestrator.refresh()
        }
    }

    /**
     * Real bug this closes, confirmed on a real device: `mode` used to be
     * resolved exactly once, in `onCreate`, and never re-checked. When
     * onboarding (then launched from `onCreate`; it now finishes this
     * Activity instead, see `OnboardingGate.resumeIfUnfinished`) pushed
     * `OnboardingActivity` on top of this same task *before*
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
        // Same reasoning as ForegroundShell just above: a repeat in
        // flight must not keep firing dispatchKeyEvent into a window
        // that is no longer the one the user is looking at.
        gamepadAxisNav.cancel()
        super.onPause()
    }

    private fun leaveIfSwitchedOff(enabled: Set<Mode>) {
        val current = mode ?: return
        if (current in enabled || isFinishing) return
        if (current == Mode.DESKTOP) DesktopSessionService.stop(this)
        val next = Modes.resolveAppMode(this, null)
        if (next != null) {
            Modes.setLastMode(this, next)
            mode = next
            startDesktopSessionIfDesktop()
        } else {
            Modes.setLastMode(this, Mode.LAUNCHER)
            finish()
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
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
        // The first start asks for the notification the session shows,
        // reason first (DesktopNotificationPermission). The session does
        // not wait for the answer.
        if (DesktopNotificationPermission.shouldAsk(this)) askDesktopNotifications = true
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
     * dual-screen roles — directed after the first live addon session).
     * The decision logic itself now lives in SecondScreenOrchestrator
     * (:display, docs/SPEC.md §4/§4c) -- this just wires the two things
     * only this Activity can supply: itself as SecondScreenHost, and the
     * LaunchDisplay hooks the orchestrator has no dependency on.
     */
    private fun observeSecondScreen() {
        // A game launch also retriggers orchestration (so the widgets
        // Presentation is dismissed off a display a game just went to --
        // Presentation windows layer ABOVE activities on that display).
        dev.droidtop.library.LaunchDisplay.onLaunched = { displayOrchestrator.refresh() }

        // The mirroring fix (docs/SPEC.md section 4c): before a launch is
        // dispatched, droidtop's idle surface is placed explicitly on any
        // secondary display the launch would otherwise leave empty.
        dev.droidtop.library.LaunchDisplay.coverVacatedDisplays = { launchTarget ->
            displayOrchestrator.coverVacatedDisplays(launchTarget)
        }

        lifecycleScope.launch {
            displayOrchestrator.observe()
        }
    }

    // --- SecondScreenHost: the handful of decisions SecondScreenOrchestrator
    // needs this Activity for, because they are genuinely Activity-only
    // (its own current display) or :app-only (CompanionActivity, LaunchDisplay). ---

    override fun currentDisplayId(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }

    override fun activityMode(): Mode? = mode

    override fun parkedDisplayId(): Int? = dev.droidtop.library.LaunchDisplay.parkedDisplayId

    override fun clearParkedDisplayId() {
        dev.droidtop.library.LaunchDisplay.clearRunning()
    }

    override fun publishLaunchTargeting(
        secondDisplayId: Int?,
        targetDisplayId: Int?,
        askOptions: List<DualScreenOrchestration.ChooserCandidate>?,
    ) {
        dev.droidtop.library.LaunchDisplay.secondDisplayId = secondDisplayId
        dev.droidtop.library.LaunchDisplay.targetDisplayId = targetDisplayId
        // Relative first, absolute only as the clarifier (docs/SPEC.md
        // section 4c), and the ADD-ON row first in both arrangements so
        // the default-highlighted choice is the better screen -- candidate
        // ordering itself is pure and unit-tested (DualScreenOrchestration).
        dev.droidtop.library.LaunchDisplay.askOptions = askOptions?.map {
            dev.droidtop.library.LaunchDisplayOption(it.displayId, it.label)
        }
    }

    override fun relaunchOnDisplay(displayId: Int) {
        startActivity(
            Intent(intent).setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
        )
    }

    override fun bringSelfForward() {
        startActivity(
            Intent(intent).setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // Companion explicitly on the BUILT-IN display: startActivity without
    // options launches on the CALLER's display, which after relocation is
    // the addon -- confirmed live: the companion landed behind the shell
    // on the addon and the built-in screen kept showing the Standard
    // launcher.
    override fun startCompanionOnBuiltIn() {
        startActivity(
            Intent(this, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
                .toBundle(),
        )
    }

    override fun companionVisible(): Boolean = CompanionActivity.visible

    override fun setDualScreenBroken(broken: Boolean) {
        CompanionState.dualScreenBroken.value = broken
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
        displayOrchestrator.onConfigurationChanged()
    }

    override fun onStop() {
        super.onStop()
        // The other real reason to tear the companion down: MainActivity
        // itself is leaving its mode, not merely losing foreground to a
        // launched game -- Home pressed to Standard/Alternative, or a mode
        // switch. HomeTrampolineActivity/AlternativeLauncherActivity/
        // BackButtonMenu all update Modes.lastMode BEFORE this Activity's
        // onStop runs (the new foreground Activity's own onCreate always
        // precedes the old one's onStop in Android's transition order), so
        // comparing it against the mode THIS instance was showing tells
        // the two cases apart without SecondScreenOrchestrator needing to
        // know why it stopped.
        val modeDeparted = mode != null && Modes.lastMode(this) != mode?.id
        displayOrchestrator.onActivityStop(modeDeparted)
    }

    override fun onStart() {
        super.onStart()
        // Coming back to the foreground re-asserts the live companion,
        // through the same orchestration pass everything else uses, and
        // restarts the "an app on the addon exited on its own" health
        // check (see SecondScreenOrchestrator.onActivityStart).
        displayOrchestrator.onActivityStart(lifecycleScope)
    }

    override fun onDestroy() {
        // onCreate finishes early (first-run onboarding hand-off, crash-recovery
        // route) before the orchestrator is built; there is nothing to tear down then.
        if (::displayOrchestrator.isInitialized) displayOrchestrator.onActivityDestroy()
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
