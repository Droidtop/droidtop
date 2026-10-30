package dev.droidtop.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.util.Log
import dev.droidtop.library.settings.Mode
import dev.droidtop.runtime.CompanionScreenGuard
import dev.droidtop.runtime.DisplayArrangement
import dev.droidtop.runtime.DisplayOutputKind
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.runtime.DualScreenOrchestration
import dev.droidtop.runtime.ScreenRoles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "droidtop.SecondScreen"

/**
 * What only the hosting Activity can do: read its own current display,
 * relaunch itself, and drive the companion Activity that lives in :app
 * (widgets/input, too UI-specific to belong in this module). Everything
 * that is a pure decision instead of one of these lives in
 * [SecondScreenOrchestrator] itself or in [DualScreenOrchestration].
 */
interface SecondScreenHost {
    /** The Activity's own current display id (its display?.displayId / windowManager.defaultDisplay). */
    fun currentDisplayId(): Int

    /** The mode this Activity instance is currently showing (null while undecided). */
    fun activityMode(): Mode?

    /** dev.droidtop.library.LaunchDisplay.parkedDisplayId -- a display a launched app owns. */
    fun parkedDisplayId(): Int?
    fun clearParkedDisplayId()

    /** Mirrors the resolved state into dev.droidtop.library.LaunchDisplay. */
    fun publishLaunchTargeting(
        shellDisplayId: Int,
        secondDisplayId: Int?,
        targetDisplayId: Int?,
        askOptions: List<DualScreenOrchestration.ChooserCandidate>?,
    )

    /** Relaunches this same singleTask Activity, explicitly pinned to [displayId]. */
    fun relaunchOnDisplay(displayId: Int)

    /** Starts :app's CompanionActivity on the built-in display. Throws on refusal. */
    fun startCompanionOnBuiltIn()

    /** Finishes the companion when no second display remains. */
    fun stopCompanion()

    /** Whether a companion instance is currently started/visible (CompanionActivity.visible). */
    fun companionVisible(): Boolean

    /** Whether the shell Activity is started (visible), false while something else is in front of it. */
    fun shellStarted(): Boolean

    /** Published for the "Reinitialize displays" pill (CompanionState.dualScreenBroken). */
    fun setDualScreenBroken(broken: Boolean)
}

/**
 * The dual-screen relocation and companion orchestration loop, moved out
 * of MainActivity into :display (docs/SPEC.md section 4/4c: this module
 * is where droidtop's secondary-display decisions live). MainActivity now
 * only supplies [SecondScreenHost] -- the handful of things that
 * genuinely require being the foreground Activity -- and this class owns
 * every decision plus the two surfaces it manages directly
 * ([SecondScreenPresentation], [SecondaryDisplayActivity]).
 *
 * One instance per MainActivity instance (created in onCreate, like the
 * fields it replaces), except relocationAttempts/lastRelocationAttemptMs
 * in the companion object below, which stay process-wide across a
 * relocation's Activity recreation for the same reason MainActivity's own
 * DisplayRelocation companion object did.
 */
class SecondScreenOrchestrator(
    private val context: Context,
    private val host: SecondScreenHost,
) {
    private val displayOutputs = DisplayOutputRepository(context)
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    /** Bumped to force a fresh orchestration pass (home-press reinit, explicit shell re-entry, a game launch). */
    private val roleRefresh = MutableStateFlow(0)

    private var lastArrangementSeq: Int = -1
    private var lastMainId: Int? = null

    /** The companion's screen as the last pass arranged it (ScreenRoles), for placing the idle cover first there. */
    @Volatile private var companionDisplayId: Int? = null

    /** The screens the person left to Android (ScreenRole.MIRROR): droidtop never covers them. */
    @Volatile private var mirroredIds: Set<Int> = emptySet()
    private var secondScreenPresentation: SecondScreenPresentation? = null
    private var healthCheckJob: Job? = null

    companion object {
        @Volatile
        private var lastRelocationAttemptMs = 0L
        private const val RELOCATION_COOLDOWN_MS = 5000L
        private const val SECOND_SCREEN_HEALTH_CHECK_MS = 4000L

        @Volatile
        private var relocationAttempts = 0

        /**
         * The displays the last pass saw, and the display the shell was last on. Process-wide like the attempts and
         * the cooldown above: a relocation recreates or moves this Activity, and a fresh instance (or the
         * configuration change a move delivers) must not read as a new topology. Reset there, they let a relocation
         * that does not stick retry at once and for ever: the cold start with two screens attached that "constantly
         * reinitializes both screens" (Droidtop/tracker#785).
         */
        @Volatile
        private var lastDisplayIds: Set<Int> = emptySet()

        @Volatile
        private var lastShellDisplayId: Int? = null
    }

    fun refresh() {
        roleRefresh.value++
    }

    /**
     * The person switched back to droidtop (Switch mode > Gaming or Desktop): an app that took the companion's screen
     * in the meantime (the stock launcher, after Android mode) no longer keeps the companion off it. Only the
     * companion's cover is released, so an app droidtop launched there ([SecondScreenHost.parkedDisplayId]) stays.
     */
    fun companionBack() {
        CompanionCover.clear()
        lastRelocationAttemptMs = 0L
    }

    /**
     * Re-runs orchestration from scratch: drops the parked display and the
     * relocation cooldown so the next pass really acts rather than being
     * suppressed as a repeat attempt.
     */
    fun reinitialize() {
        host.clearParkedDisplayId()
        giveScreensBack()
        lastRelocationAttemptMs = 0L
        relocationAttempts = 0
        lastDisplayIds = emptySet()
        roleRefresh.value++
    }

    /** The user asked for the screens back: neither the idle cover nor the companion counts as covered any more. */
    private fun giveScreensBack() {
        SecondaryDisplayActivity.clearCovered()
        CompanionCover.clear()
    }

    /**
     * The shell's configuration changed. A foldable changing the shape of the screen the shell is on gets a fresh,
     * uncooled pass. A change that comes from the shell having moved to another display (a relocation) does not: it
     * only re-runs the pass, so the cooldown and the attempt count keep bounding relocations (Droidtop/tracker#785).
     */
    fun onConfigurationChanged() {
        val here = host.currentDisplayId()
        val moved = lastShellDisplayId != null && lastShellDisplayId != here
        lastShellDisplayId = here
        if (!moved) {
            lastRelocationAttemptMs = 0L
            relocationAttempts = 0
        }
        roleRefresh.value++
    }

    fun onActivityStart(lifecycleScope: CoroutineScope) {
        roleRefresh.value++
        healthCheckJob = lifecycleScope.launch {
            while (true) {
                delay(SECOND_SCREEN_HEALTH_CHECK_MS)
                roleRefresh.value++
            }
        }
    }

    /**
     * The live companion Presentation lives on its OWN Display and keeps
     * rendering regardless of the host Activity's own foreground state
     * (it is a WindowManager window, not something tied to that
     * Activity's visibility) -- it only needs tearing down when whatever
     * just took over foreground IS that same display, which would
     * otherwise sit underneath the Presentation's window.
     *
     * This used to dismiss the Presentation on EVERY onStop
     * unconditionally, then reassert droidtop's idle cover Activity there
     * unless a launch had parked onto that same display -- meaning a game
     * launched onto a DIFFERENT display (e.g. "Games launch on: Same
     * display as the shell", the common case) still tore the companion
     * down and re-launched SecondaryDisplayActivity in its place, racing
     * that unrelated cross-display dismiss+startActivity pair against the
     * just-launched game's own surface/EGL setup at the exact moment it
     * matters most. Root-caused live (rig, p1-dt-n64-black-screen-hang):
     * launching an N64 ROM (RetroArch, built-in display, matching the
     * shell's own) both fell the companion back to the bare system
     * SecondaryDisplayLauncher (this reassertion racing and losing) and
     * hung the game's own surface for 45s+ into an ANR -- not a
     * RetroArch/mupen64plus_next defect, since the same core launched
     * directly from RetroArch's own UI (bypassing this path entirely)
     * does not reproduce it.
     *
     * @param modeDeparted whether the host Activity itself is leaving the
     * mode it was showing (Home to Standard/Alternative, a mode switch)
     * rather than merely losing foreground to a launched game.
     */
    fun onActivityStop(modeDeparted: Boolean) {
        val presentation = secondScreenPresentation
        val presentationDisplayId = presentation?.display?.displayId
        val parked = host.parkedDisplayId()
        if (presentation != null && presentationDisplayId != null &&
            (presentationDisplayId == parked || modeDeparted)
        ) {
            runCatching {
                context.startActivity(
                    Intent(context, SecondaryDisplayActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeBasic().setLaunchDisplayId(presentationDisplayId).toBundle(),
                )
            }.onFailure {
                Log.w(TAG, "Idle cover on display $presentationDisplayId refused at onStop", it)
            }
            presentation.dismiss()
            secondScreenPresentation = null
        }
        healthCheckJob?.cancel()
        healthCheckJob = null
        host.setDualScreenBroken(false)
    }

    fun onActivityDestroy() {
        secondScreenPresentation?.dismiss()
        secondScreenPresentation = null
    }

    /**
     * The mirroring fix (docs/SPEC.md section 4c): before a launch is
     * dispatched, droidtop places its idle surface explicitly on any
     * secondary display the launch would otherwise leave empty.
     */
    fun coverVacatedDisplays(launchTargetDisplayId: Int?) {
        val outputs = displayOutputs.currentOutputsSnapshot()
        val secondaryIds = outputs
            .filter { it.kind == DisplayOutputKind.SECOND_SCREEN && it.androidDisplayId !in mirroredIds }
            .map { it.androidDisplayId }
            // The companion's screen first (ScreenRoles).
            .sortedBy { if (it == companionDisplayId) 0 else 1 }
        DualScreenOrchestration
            .displaysNeedingIdleCover(
                secondaryDisplayIds = secondaryIds,
                launchTargetDisplayId = launchTargetDisplayId,
                shellDisplayId = host.currentDisplayId(),
                parkedDisplayId = host.parkedDisplayId(),
            )
            .forEach { displayId -> startIdleCover(displayId) }
    }

    private fun startIdleCover(displayId: Int) {
        runCatching {
            context.startActivity(
                Intent(context, SecondaryDisplayActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle(),
            )
        }.onFailure {
            Log.w(TAG, "Idle cover on display $displayId refused", it)
        }
    }

    /** Starts the collect loop; call from lifecycleScope.launch { orchestrator.observe() }. */
    suspend fun observe() {
        combine(
            displayOutputs.observe(),
            roleRefresh,
            DisplayArrangement.refresh,
            dev.droidtop.runtime.ScreenNaming.changes,
        ) { outputs, _, arrangement, _ -> outputs to arrangement }
            .collectLatest { (outputs, arrangementSeq) ->
                if (arrangementSeq != lastArrangementSeq) {
                    lastArrangementSeq = arrangementSeq
                    host.clearParkedDisplayId()
                    giveScreensBack()
                    lastRelocationAttemptMs = 0L
                    relocationAttempts = 0
                }
                val displayIds = outputs.map { it.androidDisplayId }.toSet()
                if (displayIds != lastDisplayIds) {
                    lastDisplayIds = displayIds
                    lastRelocationAttemptMs = 0L
                    relocationAttempts = 0
                }

                val currentDisplay = host.currentDisplayId()
                lastShellDisplayId = currentDisplay
                val disconnectedShellDestination = DualScreenOrchestration
                    .disconnectedShellDestination(currentDisplay, displayIds)
                if (disconnectedShellDestination != null) {
                    host.clearParkedDisplayId()
                    host.relaunchOnDisplay(disconnectedShellDestination)
                    return@collectLatest
                }

                // What each screen is for, as the person answered (docs/SPEC.md 4c, ScreenRoles; Droidtop/tracker#776): no
                // screen is assumed better. The one screen paired with the built-in one is the main screen when the
                // menus are elsewhere, else the companion's; a screen nobody answered for gets nothing of droidtop's.
                val arrangement = withContext(Dispatchers.IO) { ScreenRoles.arrangement(context, outputs) }
                companionDisplayId = arrangement.companionId
                val pairedId = if (arrangement.mainId != Display.DEFAULT_DISPLAY) arrangement.mainId else arrangement.companionId
                val second = outputs.firstOrNull { it.androidDisplayId == pairedId && it.kind == DisplayOutputKind.SECOND_SCREEN }
                val mainElsewhere = second != null && second.androidDisplayId == arrangement.mainId
                val showCompanion = arrangement.companionId != null
                mirroredIds = arrangement.mirrored
                if (DualScreenOrchestration.shouldDismissCompanion(showCompanion, host.companionVisible())) {
                    host.stopCompanion()
                }
                val mode = host.activityMode()
                val gaming = mode == Mode.GAMING
                val desktop = mode == Mode.DESKTOP

                // A user-launched app on the second screen is parked too: independent
                // screens, nothing here is moved off it on its own (tracker#243).
                val userApp = DualScreenOrchestration.userAppDisplayId(
                    shellDisplayId = currentDisplay,
                    secondDisplayId = second?.androidDisplayId,
                    shellStarted = host.shellStarted(),
                    coverCoveredDisplayId = SecondaryDisplayActivity.coveredDisplayId,
                )
                // Only the idle cover's state is stale once the shell is in front on the second
                // screen. The companion cover belongs to the OTHER screen and must survive this
                // pass: clearing it here let the companion replace an app the user started on its
                // screen while the shell was in front (tracker#274).
                if (DualScreenOrchestration.shellIsOnSecond(currentDisplay, second?.androidDisplayId) && host.shellStarted()) {
                    SecondaryDisplayActivity.clearCovered()
                }
                val parked = host.parkedDisplayId() ?: userApp
                // The companion's own screen (the built-in one while the shell is
                // on the add-on) is the same: an app in front of it is not
                // covered again by the companion, and the shell is not re-fronted
                // over it, so the pad stays with that app (tracker#265).
                val appOnCompanionScreen = CompanionScreenGuard.appInFrontOfCompanion(
                    shellDisplayId = currentDisplay,
                    companionDisplayId = Display.DEFAULT_DISPLAY,
                    parkedDisplayId = host.parkedDisplayId(),
                    companionCoveredDisplayId = CompanionCover.displayId,
                )
                val secondAvailable = second != null && second.androidDisplayId != parked
                // Choosing the Main screen is the user asking for the screens back.
                if (lastMainId != null && lastMainId != arrangement.mainId) giveScreensBack()
                lastMainId = arrangement.mainId
                val wantShellOnSecond = (gaming || desktop) && secondAvailable && mainElsewhere
                val relocationGaveUp = currentDisplay != second?.androidDisplayId &&
                    DualScreenOrchestration.relocationHasFailed(relocationAttempts)
                // Where the shell IS, from its own display: parking changes
                // wantShellOnSecond but never moves the shell (tracker#162).
                val shellOnSecondNow = DualScreenOrchestration.shellIsOnSecond(currentDisplay, second?.androidDisplayId)
                val move = DualScreenOrchestration.shellMove(
                    shellDisplayId = currentDisplay,
                    secondDisplayId = second?.androidDisplayId,
                    shellModeEligible = gaming || desktop,
                    mainScreenWantsSecond = mainElsewhere,
                    secondParked = !secondAvailable,
                    relocationFailed = relocationGaveUp,
                )
                val shellOnSecond = shellOnSecondNow || move == DualScreenOrchestration.ShellMove.TO_SECOND

                publishLaunchTargeting(
                    outputs, currentDisplay, second?.androidDisplayId, gaming, shellOnSecondNow,
                    mainId = arrangement.mainId, companionId = arrangement.companionId,
                )

                showCompanionPresentation(
                    if (showCompanion) DualScreenOrchestration.companionPresentationDisplayId(
                        shellDisplayId = currentDisplay,
                        secondDisplayId = second?.androidDisplayId,
                        secondParked = !secondAvailable,
                        move = move,
                    ) else null,
                )

                if (currentDisplay == second?.androidDisplayId &&
                    SecondScreenPlan.wantsSecond(move, second?.androidDisplayId, shellOnSecondNow, wantShellOnSecond)
                ) {
                    relocationAttempts = 0
                }
                val now = android.os.SystemClock.elapsedRealtime()
                val step = SecondScreenPlan.relocation(
                    move = move,
                    currentDisplay = currentDisplay,
                    secondId = second?.androidDisplayId,
                    shellOnSecond = shellOnSecondNow,
                    wantShellOnSecond = wantShellOnSecond,
                    companionVisible = host.companionVisible(),
                    appOnCompanionScreen = appOnCompanionScreen,
                    cooledDown = now - lastRelocationAttemptMs > RELOCATION_COOLDOWN_MS,
                )
                relocate(step, second?.androidDisplayId, now)

                host.setDualScreenBroken(
                    DualScreenOrchestration.secondScreenNeedsReinit(
                        secondDisplayId = second?.androidDisplayId,
                        parkedDisplayId = parked,
                        shellOnSecond = shellOnSecond,
                        presentationDisplayId = secondScreenPresentation?.display?.displayId,
                        idleCoverDisplayId = SecondaryDisplayActivity.resumedDisplayId,
                    ),
                )
            }
    }

    /**
     * Where a game opens and, under Ask, which screens are offered (docs/SPEC.md 4c), published for the launch path,
     * with every screen's name kept for the rows that name a screen.
     */
    private suspend fun publishLaunchTargeting(
        outputs: List<dev.droidtop.runtime.DisplayOutput>,
        currentDisplay: Int,
        secondId: Int?,
        gaming: Boolean,
        shellOnSecond: Boolean,
        mainId: Int,
        companionId: Int?,
    ) {
        val launchTarget = if (gaming && secondId != null) DisplayRolePrefs.gameLaunchTarget(context) else null
        val targetDisplayId = SecondScreenPlan.gameLaunchDisplay(launchTarget, shellOnSecond, secondId)
        // Every screen's name (docs/SPEC.md 4c, "Screen names"), kept for the rows that name a screen.
        val screenNames = withContext(Dispatchers.IO) { dev.droidtop.runtime.ScreenNaming.names(context, outputs) }
        dev.droidtop.runtime.ScreenNaming.current = screenNames
        val askOptions = if (launchTarget == DisplayRolePrefs.GameLaunchTarget.ASK) {
            DualScreenOrchestration.chooserCandidates(
                outputs.map { it.androidDisplayId },
                screenNames,
                currentDisplay,
                secondId,
                mainDisplayId = mainId,
                companionDisplayId = companionId,
            )
        } else {
            null
        }
        host.publishLaunchTargeting(currentDisplay, secondId, targetDisplayId, askOptions)
    }

    /** Shows the companion as a Presentation on [displayId], moving it there if it is elsewhere; null takes it down. */
    private fun showCompanionPresentation(displayId: Int?) {
        if (displayId == null) {
            secondScreenPresentation?.dismiss()
            secondScreenPresentation = null
            return
        }
        if (secondScreenPresentation?.display?.displayId == displayId) return
        secondScreenPresentation?.dismiss()
        secondScreenPresentation = displayManager.getDisplay(displayId)?.let { SecondScreenPresentation(context, it).also { p -> p.show() } }
    }

    /** Takes [step] ([SecondScreenPlan.relocation]) at [now]; a refused move to the second screen stops further tries. */
    private fun relocate(step: SecondScreenPlan.Relocation, secondId: Int?, now: Long) {
        when (step) {
            SecondScreenPlan.Relocation.NONE -> Unit
            // The Main screen choice moved off the second screen: bring the shell home, under the same cooldown as
            // the other direction (tracker#163).
            SecondScreenPlan.Relocation.TO_BUILT_IN -> {
                lastRelocationAttemptMs = now
                runCatching { host.relaunchOnDisplay(Display.DEFAULT_DISPLAY) }
                    .onFailure { Log.w(TAG, "Relocation to the built-in display refused", it) }
            }
            SecondScreenPlan.Relocation.TO_SECOND, SecondScreenPlan.Relocation.TO_SECOND_WITH_COMPANION -> {
                val target = secondId ?: return
                lastRelocationAttemptMs = now
                relocationAttempts++
                runCatching {
                    if (step == SecondScreenPlan.Relocation.TO_SECOND_WITH_COMPANION) host.startCompanionOnBuiltIn()
                    host.relaunchOnDisplay(target)
                }.onFailure {
                    Log.w(TAG, "Relocation to display $target refused", it)
                    relocationAttempts = DualScreenOrchestration.MAX_RELOCATION_ATTEMPTS
                    roleRefresh.value++
                }
            }
            // Starting the companion makes it the top activity; it hands the pad straight back to the shell itself
            // (TouchOnlySurfaceFocus).
            SecondScreenPlan.Relocation.COMPANION_ON_BUILT_IN -> {
                lastRelocationAttemptMs = now
                host.startCompanionOnBuiltIn()
            }
        }
    }
}
