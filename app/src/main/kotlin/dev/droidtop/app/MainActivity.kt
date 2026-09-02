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
import androidx.lifecycle.lifecycleScope
import dev.droidtop.hostbridge.ClipboardBridge
import dev.droidtop.library.EngineGameProvider
import dev.droidtop.library.Library
import dev.droidtop.library.NativeAppProvider
import dev.droidtop.library.RoomPlayHistoryStore
import dev.droidtop.library.consoles.ConsoleRomProvider
import dev.droidtop.runtime.ContainerTerminal
import dev.droidtop.runtime.DisplayOutputKind
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.runtime.PrimaryContainerSession
import dev.droidtop.runtime.windows.PcGameProvider
import dev.droidtop.shell.desktop.DesktopSessionMessage
import dev.droidtop.shell.desktop.DesktopShell
import dev.droidtop.shell.gamepad.GamepadShell
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.ModePrefs
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
 * once Desktop mode had opened once, no `EXTRA_MODE` switch back to Handheld
 * (or vice versa) could ever take effect, because onCreate (where `mode` was
 * read) never ran a second time.
 *
 * Desktop mode starts [DesktopSessionService] and observes its
 * [DesktopSessionService.state] instead of a hardcoded null HostBridge/
 * DisplayOutput -- real wiring, but the session itself is still expected
 * to land in [DesktopSessionState.Failed] on any real device right now
 * (see that service's own doc comment for the two concrete gaps: no
 * primary container image published yet, and the non-root runtime
 * ([dev.droidtop.runtime.linux.noroot.ProotRuntime]) is unimplemented).
 * [DesktopShell] renders that Idle/Connecting/Failed/Connected state
 * distinctly via its own [dev.droidtop.shell.desktop.DesktopSessionMessage]
 * rather than a single generic placeholder.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var library: Library
    private var mode by mutableStateOf<String?>(null)

    // Real bug this avoids: MainActivity is android:launchMode="singleTask",
    // so a deep-link Intent from SettingsHandheldFragment (FLAG_ACTIVITY_
    // NEW_TASK against this same Activity) almost always resolves through
    // onNewIntent, not onCreate, whenever Handheld mode is already running
    // -- the common case, not an edge case, since the whole point of these
    // deep links is jumping back INTO an already-open Handheld session.
    // Reading `intent.getStringExtra(...)` directly inside `setContent`
    // would silently do nothing then: `mode` often doesn't change (already
    // MODE_HANDHELD), so nothing triggers GamepadShell to recompose with
    // the new extras. A separate token, bumped on every onCreate/onNewIntent
    // and read by GamepadShell via LaunchedEffect(token), fires every real
    // deep-link regardless of whether `mode` itself changed or the extras'
    // own values happen to repeat (e.g. "Rescan library" pressed twice).
    private var handheldDeepLinkToken by mutableStateOf(0)
    private var handheldStartSection by mutableStateOf<String?>(null)
    private var handheldTriggerRescan by mutableStateOf(false)
    private var handheldTriggerBrowseThemes by mutableStateOf(false)

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

    // Which physical panel is the main output. Written by
    // DisplayArrangement.swap, read on every orchestration pass. Android
    // exposes no reliable physical-position signal, so this is a guess
    // the user can correct and droidtop then remembers -- never a
    // decision droidtop keeps making for them.
    private val dualScreenStore by lazy {
        dev.droidtop.runtime.PrefsDualScreenAssignmentStore(applicationContext)
    }
    private val dualScreenCoordinator by lazy {
        dev.droidtop.runtime.DualScreenCoordinator(dualScreenStore)
    }

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
    }

    private fun applyHandheldDeepLink(intent: Intent) {
        handheldStartSection = intent.getStringExtra(BackButtonMenu.EXTRA_HANDHELD_START_SECTION)
        handheldTriggerRescan = intent.getBooleanExtra(BackButtonMenu.EXTRA_HANDHELD_RESCAN, false)
        handheldTriggerBrowseThemes = intent.getBooleanExtra(BackButtonMenu.EXTRA_HANDHELD_BROWSE_THEMES, false)
        handheldDeepLinkToken++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHandheldDeepLink(intent)

        // Real gap this closes: OnboardingGate was only ever called from
        // LauncherApplication.java (Standard's own boot) -- a user who
        // launches straight into Desktop/Handheld (droidtop not set as
        // system HOME, or opened via BackButtonMenu/EXTRA_MODE directly)
        // never saw onboarding at all. Both real entry points need this,
        // not just one.
        OnboardingGate.launchIfNeeded(this)

        // Real roots are read fresh by each provider on every scan (see
        // GamesRoots.current's own doc comment) -- not resolved once here
        // and frozen, since that would silently ignore any root added or
        // removed at runtime via the "ROM folders" Settings screen.
        library = Library(
            listOf(
                NativeAppProvider(applicationContext),
                EngineGameProvider(
                    applicationContext,
                    // Every store's install directories, not just Steam's, so a
                    // Ren'Py or RPG Maker game installed from GOG/Epic/Amazon
                    // flows through the same engine detection and launch
                    // resolution as one sitting in a games folder (docs/SPEC.md
                    // section 7g).
                    extraRoots = { dev.droidtop.runtime.windows.PcLibrary.knownInstallRoots() },
                    // The store's own facts about those installs. Engine
                    // detection owns a store game whose folder it
                    // recognises and PcGameProvider stops returning a
                    // second entry for it (docs/SPEC.md section 7g); this
                    // is what stops that from also losing the game's
                    // store, size, compatibility and cover art.
                    storeInstalls = { dev.droidtop.runtime.windows.PcLibrary.knownInstalls() },
                ),
                // Same roots as EngineGameProvider -- a folder can hold
                // real console ROMs (<root>/<systemId>/<romFile>), engine
                // games (<root>/<gameFolder>/...), or both; each provider
                // only ever matches what's actually its own shape.
                ConsoleRomProvider(applicationContext),
                // Real discovery (com.winlator.container.ContainerManager's
                // own shortcut scan), themed as ES-DE's "pc" system like
                // any other. It launches through the WineEngine seam, so
                // it needs no desktop session and no root.
                PcGameProvider(applicationContext),
            ),
            playHistory = RoomPlayHistoryStore(applicationContext),
        )

        // Fills library-core's PcGameRuntime seam, which is what makes the
        // WINE_PREFIX / LINUX_CONTAINER launch strategies real rather than
        // error() stubs. The session supplier is only for the native-Linux
        // half, which genuinely needs a Linux rootfs to run in; Windows
        // games go through the WineEngine seam and need neither it nor
        // root. It stays a supplier because DesktopSessionService may
        // still be connecting when this runs.
        dev.droidtop.library.PcGameRuntimeRegistry.runtime =
            dev.droidtop.runtime.windows.DroidtopPcGameRuntime(
                context = applicationContext,
                primarySession = {
                    (DesktopSessionService.state.value as? DesktopSessionState.Connected)
                        ?.let { PrimaryContainerSession(it.runtime, it.container) }
                },
            )
        refreshModeIfUndecided()

        observeSecondScreen()
        observeClipboardBridge()

        setContent {
            // DroidtopTheme provides Material tokens for droidtop's own
            // chrome (DesktopShell panels etc.); the Handheld shell's
            // ES-DE-themed surfaces take their colors from the active
            // ES-DE theme instead and simply don't read these tokens.
            dev.droidtop.app.ui.DroidtopTheme {
            when (mode) {
                BackButtonMenu.MODE_HANDHELD -> GamepadShell(
                    library = library,
                    onFocusedEntryChanged = { CompanionState.focusedEntry.value = it },
                    // The companion's idle rotation draws from this
                    // (docs/SPEC.md section 4d). Published here rather
                    // than scanned there: the companion renders on a
                    // screen the user is not driving and must not do
                    // work of its own.
                    onEntriesChanged = { CompanionState.libraryEntries.value = it },
                    deepLinkToken = handheldDeepLinkToken,
                    startSectionName = handheldStartSection,
                    triggerRescan = handheldTriggerRescan,
                    triggerBrowseThemes = handheldTriggerBrowseThemes,
                )
                else -> {
                    val sessionState by DesktopSessionService.state.collectAsState()
                    val connected = sessionState as? DesktopSessionState.Connected
                    DesktopShell(
                        library = library,
                        hostBridge = connected?.hostBridge,
                        primaryOutput = connected?.primaryOutput,
                        sessionMessage = when (val state = sessionState) {
                            is DesktopSessionState.Idle -> DesktopSessionMessage.Idle
                            is DesktopSessionState.Connecting -> DesktopSessionMessage.Connecting
                            is DesktopSessionState.Connected -> DesktopSessionMessage.Idle
                            is DesktopSessionState.Failed -> DesktopSessionMessage.Failed(state.message)
                        },
                        // Only offered when there is a live session to open a
                        // terminal in -- see DesktopShell's own comment on
                        // why the button is absent rather than disabled.
                        // Suspends until the terminal window is closed, since
                        // it is an ordinary foreground process on the shared
                        // desktop, and reports whatever went wrong if it
                        // never appeared.
                        onOpenTerminal = connected?.let { session ->
                            suspend {
                                ContainerTerminal.failureMessage(
                                    ContainerTerminal.open(session.runtime, session.container),
                                )
                            }
                        },
                    )
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyHandheldDeepLink(intent)
        mode = resolveMode(intent)
        if (mode != BackButtonMenu.MODE_HANDHELD) {
            startForegroundService(Intent(this, DesktopSessionService::class.java))
        }
        // Display reinit on every re-entry (a HOME press routes here via
        // Launcher.onNewIntent's forwarding, carrying EXTRA_DISPLAY_REINIT).
        // An EXPLICIT shell entry (BackButtonMenu's Handheld item, no
        // reinit extra) also reclaims a display an app was launched onto;
        // the home-press reinit deliberately does NOT -- "fix my screens"
        // must never cover a running game (see LaunchDisplay.parkedDisplayId).
        if (!intent.getBooleanExtra(BackButtonMenu.EXTRA_DISPLAY_REINIT, false) &&
            mode == BackButtonMenu.MODE_HANDHELD
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
     * onboarding has actually set `ModePrefs.lastMode` to anything real,
     * [resolveMode] has nothing to resolve to yet and returns `null` --
     * which the `when(mode)` below's `else` branch silently treats as
     * Desktop. That's the correct behavior for "genuinely undecided," but
     * this Activity instance never got a chance to reconsider once the
     * user actually finished onboarding and picked Handheld: finishing a
     * child Activity that was merely stacked on top (not `startActivity`'d
     * with new-task/single-top semantics against *this* Activity) resumes
     * this Activity via `onResume`, not `onNewIntent` -- so `mode` stayed
     * frozen at its original `null` forever, and the user landed on
     * Desktop (which then fails outright, since it was never set up)
     * instead of the Handheld they actually chose. Re-resolving here,
     * gated on `mode == null` so an already-decided mode is never stomped
     * mid-session, is what actually fixes it.
     */
    override fun onResume() {
        super.onResume()
        refreshModeIfUndecided()
    }

    private fun refreshModeIfUndecided() {
        if (mode != null) return
        mode = resolveMode(intent)
        if (mode != BackButtonMenu.MODE_HANDHELD) {
            startForegroundService(Intent(this, DesktopSessionService::class.java))
        }
    }

    /**
     * Prefers an explicit [BackButtonMenu.EXTRA_MODE] (a real user choice,
     * from [BackButtonMenu] or Launcher's own cold-boot redirect — see the
     * "droidtop patch" in `Launcher.onCreate`); then a real, user-set
     * [ModePrefs.defaultMode] (Global settings' own "Default mode" picker),
     * if one is set and its mode is still enabled — a disabled mode can't
     * silently become the resolved mode just because it's still saved as
     * the default; falls back to [ModePrefs]'s last app-hosted mode when
     * neither applies, so this Activity resumes correctly even if launched
     * by something that didn't set the extra. Persists whatever mode is
     * resolved as a safety net — every known real caller already does this
     * before launching, but a null write here would be wrong (it would
     * forget the real last mode).
     */
    private fun resolveMode(intent: Intent): String? {
        val explicit = intent.getStringExtra(BackButtonMenu.EXTRA_MODE)
        val default = ModePrefs.defaultMode(this)?.takeIf {
            (it == BackButtonMenu.MODE_HANDHELD || it == BackButtonMenu.MODE_DESKTOP) && ModePrefs.isModeEnabled(this, it)
        }
        val resolved = explicit ?: default ?: ModePrefs.lastMode(this).takeIf {
            it == BackButtonMenu.MODE_HANDHELD || it == BackButtonMenu.MODE_DESKTOP
        }
        if (resolved != null) ModePrefs.setLastMode(this, resolved)
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
    }

    /**
     * Dual-screen role orchestration (docs/SPEC.md §4, handheld
     * dual-screen roles — directed after the first live addon session):
     * when a second display is present and [DisplayRolePrefs.shellTarget]
     * says so (the default), the HANDHELD SHELL ITSELF moves to it (the
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
     * deliberately opts out of all of this (§4: its lower screen is an
     * input surface).
     */
    private fun observeSecondScreen() {
        val displayOutputs = DisplayOutputRepository(applicationContext)
        val displayManager = getSystemService(DisplayManager::class.java)

        // A game launch also retriggers orchestration (so the widgets
        // Presentation is dismissed off a display a game just went to --
        // Presentation windows layer ABOVE activities on that display).
        dev.droidtop.library.LaunchDisplay.onLaunched = { roleRefresh.value++ }

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
                }
                val displayIds = outputs.map { it.androidDisplayId }.toSet()
                if (displayIds != lastDisplayIds) {
                    lastDisplayIds = displayIds
                    lastRelocationAttemptMs = 0L
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
                val handheld = mode == BackButtonMenu.MODE_HANDHELD
                // A display an app was launched onto is PARKED: droidtop
                // keeps its hands off it entirely (no shell relocation, no
                // widgets Presentation over the running app) until an
                // explicit shell entry reclaims it -- the "don't interfere
                // with apps we've launched" half of the home-press reinit.
                val parked = dev.droidtop.library.LaunchDisplay.parkedDisplayId
                val secondAvailable = second != null && second.androidDisplayId != parked
                // The user's own panel assignment wins when they have made
                // one; the ShellTarget preference is only the seed for
                // before they ever have. Previously this read the
                // preference alone, so DualScreenCoordinator's whole
                // resolve/swap/persist mechanism -- written, unit-tested,
                // and wired to nothing -- could never affect anything, and
                // "swap my screens" had no way to take effect.
                val roles = dualScreenCoordinator.resolve(outputs)
                val assignedUpper = roles.entries
                    .firstOrNull { it.value == dev.droidtop.runtime.DualScreenRole.UPPER_OUTPUT }
                    ?.key
                val hasSavedAssignment = dualScreenStore.get().size >= 2
                val shellOnSecond = handheld && secondAvailable && if (hasSavedAssignment) {
                    assignedUpper?.androidDisplayId == second!!.androidDisplayId
                } else {
                    DisplayRolePrefs.shellTarget(this@MainActivity) == DisplayRolePrefs.ShellTarget.SECOND_WHEN_PRESENT
                }

                val launchTarget = if (handheld && second != null) DisplayRolePrefs.gameLaunchTarget(this@MainActivity) else null
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
                        // Relative first, absolute only as the clarifier.
                        // "The other screen" is right however Android
                        // enumerated the panels, and there is no reliable
                        // physical-position signal that would make a bare
                        // "second display" label trustworthy -- see
                        // docs/SPEC.md section 4c.
                        val shellOnAddon = shellOnSecond
                        val addonId = second!!.androidDisplayId
                        if (shellOnAddon) {
                            listOf(
                                dev.droidtop.library.LaunchDisplayOption(addonId, "This screen (add-on)"),
                                dev.droidtop.library.LaunchDisplayOption(null, "The other screen (built-in)"),
                            )
                        } else {
                            listOf(
                                dev.droidtop.library.LaunchDisplayOption(null, "This screen (built-in)"),
                                dev.droidtop.library.LaunchDisplayOption(addonId, "The other screen (add-on)"),
                            )
                        }
                    } else {
                        null
                    }

                // The second display needs no work from droidtop at all
                // now: the platform places :display's
                // SecondaryDisplayActivity on every secondary display and
                // re-places it when whatever ran there finishes. This used
                // to push a Presentation onto the same display and race
                // the platform for it -- see docs/SPEC.md section 4c.
                //
                // What remains is the one case the platform cannot cover:
                // SECONDARY_HOME never applies to the DEFAULT display, so
                // when the user has assigned the addon as the main output
                // the shell is moved there and the built-in gets the
                // companion as an ordinary Activity.
                // Second screen, shell NOT on it: this shell is foreground
                // on the built-in panel, so it drives the companion
                // directly as a Presentation. The SECONDARY_HOME activity
                // stays underneath as the idle surface for when droidtop
                // is not foreground; this window sits above it while it is.
                if (!shellOnSecond && handheld && second != null && secondAvailable) {
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
                        if (currentDisplayId != second.androidDisplayId &&
                            now - lastRelocationAttemptMs > RELOCATION_COOLDOWN_MS
                        ) {
                            lastRelocationAttemptMs = now
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
        roleRefresh.value++
    }

    override fun onStop() {
        super.onStop()
        // The shell is no longer foreground -- a game may have started, or
        // the user switched apps. Take the live companion window down so
        // :display's SecondaryDisplayActivity, the idle surface Android
        // places, is what remains on that screen. Leaving it up would
        // layer this shell's companion over whatever is now running.
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
