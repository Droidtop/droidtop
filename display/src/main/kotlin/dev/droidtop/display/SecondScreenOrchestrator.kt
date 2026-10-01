package dev.droidtop.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.util.Log
import dev.droidtop.library.settings.Mode
import dev.droidtop.runtime.DisplayArrangement
import dev.droidtop.runtime.DisplayOutputKind
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.runtime.DualScreenOrchestration
import dev.droidtop.runtime.MainScreen
import dev.droidtop.runtime.MainScreenChoice
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
        secondDisplayId: Int?,
        targetDisplayId: Int?,
        askOptions: List<DualScreenOrchestration.ChooserCandidate>?,
    )

    /** Relaunches this same singleTask Activity, explicitly pinned to [displayId]. */
    fun relaunchOnDisplay(displayId: Int)

    /** Re-fronts this Activity on whatever display it is already on (no explicit display options). */
    fun bringSelfForward()

    /** Starts :app's CompanionActivity on the built-in display. Throws on refusal. */
    fun startCompanionOnBuiltIn()

    /** Finishes the companion when no second display remains. */
    fun stopCompanion()

    /** Whether a companion instance is currently started/visible (CompanionActivity.visible). */
    fun companionVisible(): Boolean

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

    private var lastDisplayIds: Set<Int> = emptySet()
    private var lastArrangementSeq: Int = -1
    private var secondScreenPresentation: SecondScreenPresentation? = null
    private var healthCheckJob: Job? = null

    companion object {
        @Volatile
        private var lastRelocationAttemptMs = 0L
        private const val RELOCATION_COOLDOWN_MS = 5000L
        private const val SECOND_SCREEN_HEALTH_CHECK_MS = 4000L

        @Volatile
        private var relocationAttempts = 0
    }

    fun refresh() {
        roleRefresh.value++
    }

    /**
     * Re-runs orchestration from scratch: drops the parked display and the
     * relocation cooldown so the next pass really acts rather than being
     * suppressed as a repeat attempt.
     */
    fun reinitialize() {
        host.clearParkedDisplayId()
        lastRelocationAttemptMs = 0L
        relocationAttempts = 0
        lastDisplayIds = emptySet()
        roleRefresh.value++
    }

    fun onConfigurationChanged() {
        lastRelocationAttemptMs = 0L
        relocationAttempts = 0
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
            .filter { it.kind == DisplayOutputKind.SECOND_SCREEN }
            .map { it.androidDisplayId }
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
        ) { outputs, _, arrangement -> outputs to arrangement }
            .collectLatest { (outputs, arrangementSeq) ->
                if (arrangementSeq != lastArrangementSeq) {
                    lastArrangementSeq = arrangementSeq
                    host.clearParkedDisplayId()
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
                val disconnectedShellDestination = DualScreenOrchestration
                    .disconnectedShellDestination(currentDisplay, displayIds)
                if (disconnectedShellDestination != null) {
                    host.clearParkedDisplayId()
                    host.relaunchOnDisplay(disconnectedShellDestination)
                    return@collectLatest
                }

                val second = outputs.firstOrNull { it.kind == DisplayOutputKind.SECOND_SCREEN }
                val showCompanion = DualScreenOrchestration.shouldShowSecondScreenCompanion(outputs.size)
                if (DualScreenOrchestration.shouldDismissCompanion(showCompanion, host.companionVisible())) {
                    host.stopCompanion()
                }
                val mode = host.activityMode()
                val gaming = mode == Mode.GAMING
                val desktop = mode == Mode.DESKTOP

                val parked = host.parkedDisplayId()
                val secondAvailable = second != null && second.androidDisplayId != parked
                val mainScreen = withContext(Dispatchers.IO) { MainScreen.choice(context) }
                val wantShellOnSecond = (gaming || desktop) && secondAvailable &&
                    mainScreen == MainScreenChoice.SECOND_WHEN_PRESENT
                val relocationGaveUp = currentDisplay != second?.androidDisplayId &&
                    DualScreenOrchestration.relocationHasFailed(relocationAttempts)
                // Where the shell IS, from its own display: parking changes
                // wantShellOnSecond but never moves the shell (tracker#162).
                val shellOnSecondNow = DualScreenOrchestration.shellIsOnSecond(currentDisplay, second?.androidDisplayId)
                val move = DualScreenOrchestration.shellMove(
                    shellDisplayId = currentDisplay,
                    secondDisplayId = second?.androidDisplayId,
                    shellModeEligible = gaming || desktop,
                    mainScreenWantsSecond = mainScreen == MainScreenChoice.SECOND_WHEN_PRESENT,
                    secondParked = !secondAvailable,
                    relocationFailed = relocationGaveUp,
                )
                val shellOnSecond = shellOnSecondNow || move == DualScreenOrchestration.ShellMove.TO_SECOND

                val launchTarget = if (gaming && second != null) DisplayRolePrefs.gameLaunchTarget(context) else null
                val targetDisplayId = when (launchTarget) {
                    null, DisplayRolePrefs.GameLaunchTarget.ASK, DisplayRolePrefs.GameLaunchTarget.BUILT_IN -> null
                    DisplayRolePrefs.GameLaunchTarget.FOLLOW_SHELL ->
                        if (shellOnSecondNow) second!!.androidDisplayId else null
                    DisplayRolePrefs.GameLaunchTarget.SECOND -> second!!.androidDisplayId
                }
                val askOptions = if (launchTarget == DisplayRolePrefs.GameLaunchTarget.ASK) {
                    DualScreenOrchestration.chooserCandidates(second!!.androidDisplayId, shellOnSecondNow)
                } else {
                    null
                }
                host.publishLaunchTargeting(second?.androidDisplayId, targetDisplayId, askOptions)

                val presentationDisplayId = if (showCompanion) DualScreenOrchestration.companionPresentationDisplayId(
                    shellDisplayId = currentDisplay,
                    secondDisplayId = second?.androidDisplayId,
                    secondParked = !secondAvailable,
                    move = move,
                ) else null
                if (presentationDisplayId != null) {
                    if (secondScreenPresentation?.display?.displayId != presentationDisplayId) {
                        secondScreenPresentation?.dismiss()
                        val display = displayManager.getDisplay(presentationDisplayId)
                        secondScreenPresentation = display?.let {
                            SecondScreenPresentation(context, it).also { p -> p.show() }
                        }
                    }
                } else {
                    secondScreenPresentation?.dismiss()
                    secondScreenPresentation = null
                }

                if (move == DualScreenOrchestration.ShellMove.TO_BUILT_IN) {
                    // The Main screen choice moved off the second screen: bring the
                    // shell home, under the same cooldown as the other direction
                    // (tracker#163).
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastRelocationAttemptMs > RELOCATION_COOLDOWN_MS) {
                        lastRelocationAttemptMs = now
                        runCatching { host.relaunchOnDisplay(Display.DEFAULT_DISPLAY) }
                            .onFailure { Log.w(TAG, "Relocation to the built-in display refused", it) }
                    }
                } else if (second != null && (move == DualScreenOrchestration.ShellMove.TO_SECOND || (shellOnSecondNow && wantShellOnSecond))) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (currentDisplay == second.androidDisplayId) {
                        relocationAttempts = 0
                    }
                    if (currentDisplay != second.androidDisplayId &&
                        now - lastRelocationAttemptMs > RELOCATION_COOLDOWN_MS
                    ) {
                        lastRelocationAttemptMs = now
                        relocationAttempts++
                        runCatching {
                            host.startCompanionOnBuiltIn()
                            host.relaunchOnDisplay(second.androidDisplayId)
                        }.onFailure {
                            Log.w(TAG, "Relocation to display ${second.androidDisplayId} refused", it)
                            relocationAttempts = DualScreenOrchestration.MAX_RELOCATION_ATTEMPTS
                            roleRefresh.value++
                        }
                    } else if (currentDisplay == second.androidDisplayId && !host.companionVisible() &&
                        now - lastRelocationAttemptMs > RELOCATION_COOLDOWN_MS
                    ) {
                        lastRelocationAttemptMs = now
                        host.startCompanionOnBuiltIn()
                        host.bringSelfForward()
                    }
                }

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
}
