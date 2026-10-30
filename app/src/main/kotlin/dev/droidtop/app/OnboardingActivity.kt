package dev.droidtop.app

import androidx.compose.ui.res.stringResource
import dev.droidtop.shell.gamepad.groundBackground
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.input.ownPadButtons
import dev.droidtop.shell.gamepad.theme.ThemeBrowserScreen
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.droidtop.app.ui.PadButton
import dev.droidtop.app.ui.SelectableRow
import dev.droidtop.library.GamesRootReport
import dev.droidtop.library.consoles.EsDeFolderStructure
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemeDownloader
import dev.droidtop.library.theme.ThemeOffer
import dev.droidtop.library.theme.ThemePrefs as LibraryThemePrefs
import dev.droidtop.runtime.BundledImageRepositories
import dev.droidtop.runtime.ImageCatalogRole
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.linux.root.DroidSpacesRuntime
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Measure
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.library.controller.FaceLayout
import dev.droidtop.library.controller.FacePosition
import dev.droidtop.library.controller.FaceRole
import dev.droidtop.shell.gamepad.input.ControllerLayouts
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.ThemeSystemPreview
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.HomeRolePrefs
import dev.droidtop.shell.standard.HomeRolePrefs.HomeImplementation
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * droidtop's first-run flow (docs/SPEC.md section 7b, which is this
 * flow's specification). It onboards the DEVICE, not one mode, in the
 * fewest steps that still give a working setup: what droidtop opens
 * into, what the Home button does, then only the steps that choice
 * needs (the game folders and a look for Gaming, a Linux system and a
 * keyboard for Desktop, the controller when one is attached), and a
 * summary that opens the mode. Every step is one plain question with a
 * sensible answer already chosen, so pressing A straight through gives
 * a usable device (owner, 2026-10-01).
 *
 * Structurally it is ONE scaffold ([OnboardingScaffold]) that every step
 * renders into (progress, a working Back, a readable measure, a
 * bottom-docked action area) and ONE choice component ([SelectableRow])
 * for every question with mutually exclusive answers.
 *
 * Gated by [dev.droidtop.shell.standard.OnboardingGate] from every entry
 * point, so a person who never boots through the home screen still sees
 * this once.
 *
 * [EXTRA_START_STEP] supports re-entry from Settings: each step is
 * independently re-runnable later, so when it is set onboarding runs that
 * one step and finishes instead of continuing through the rest.
 */
class OnboardingActivity : AppCompatActivity() {
    /**
     * The run's own answers, held where a configuration change cannot
     * reach them (see [OnboardingRun]). Rotating the device on a late
     * step used to restart onboarding at step 1 with the answers gone.
     */
    private val onboardingRun: OnboardingRun by viewModels()

    /**
     * The front of the input pipeline for this window, as in the shell's
     * own activity (docs/SPEC.md 6e): the stick and hat move through the
     * steps like the D-pad, and the selection ring shows only while a pad or
     * keyboard is driving -- a person setting up with a finger sees no ring
     * (Droidtop/tracker#159).
     */
    private val padGate = dev.droidtop.shell.gamepad.input.PadGate(deliver = { event -> deliverKey(event) }, window = { window })

    private fun deliverKey(event: android.view.KeyEvent): Boolean = super.dispatchKeyEvent(event)

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = padGate.dispatchKey(event)

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean =
        padGate.dispatchMotion(event) || super.dispatchGenericMotionEvent(event)

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        padGate.noteTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onPause() {
        padGate.cancel()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // The ground runs under the bars; the theme would paint them grey
        // over it (rig, BlueStacks, dq-onboard-02).
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val startStep = OnboardingStep.byName(intent.getStringExtra(EXTRA_START_STEP))
        val firstRun = !GamesRootPrefs.isOnboardingComplete(this)
        // The face-button layout of the pad in use, resolved before the
        // first key is read.
        ControllerLayouts.attach(this)
        if (firstRun && startStep == null) {
            // Recents names what this task is. It said "Games", the label
            // of the drawer icon that had started it (rig, dq-coordinator-24).
            @Suppress("DEPRECATION")
            setTaskDescription(android.app.ActivityManager.TaskDescription("droidtop setup"))
        }
        // Seeded once per run, not once per Activity instance: the
        // starting step and the facts the PLAN is built from are facts
        // about the run, and re-reading them after a rotation is what made
        // the plan differ by orientation.
        onboardingRun.start(
            startStep,
            controllerAttached = ControllerLayouts.attachedControllers().isNotEmpty(),
            // A rerun, or one step opened from Settings, starts from the
            // setup as it is, so walking through it again changes nothing
            // that is not changed on the way.
            before = if (!firstRun || startStep != null) SetupBefore.read(this) else null,
            // A first run survives the process: becoming the Home app, a
            // kill from Recents, a crash. It resumes at the step it was
            // on with every answer it had (SPEC 7b); the rig lost all but
            // the folder list to one force-stop at step 5.
            saved = if (firstRun && startStep == null) OnboardingProgress.load(this) else null,
            persistent = firstRun && startStep == null,
        )
        setContent {
            // Onboarding follows the Appearance setting like every other screen, so a first run in light mode hands
            // over to a light shell (SPEC 7b, 7k "Light and dark").
            dev.droidtop.app.ui.DroidtopTheme(
                // Entered from Gaming (a step re-opened from its Settings)
                // the screens wear the Gaming theme; a first run is
                // still choosing one.
                gamingThemed = onboardingRun.startStep != null && dev.droidtop.app.ui.rememberGamingThemed(),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    dev.droidtop.shell.gamepad.LocalShellWindow provides currentShellWindow(),
                ) {
                    OnboardingScreen(
                        run = onboardingRun,
                        isReEntry = onboardingRun.startStep != null,
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_START_STEP = "dev.droidtop.app.EXTRA_START_STEP"
    }
}

/**
 * The setup as it stands when onboarding is rerun or one step is
 * re-entered from Settings: what the run's defaults are seeded from, so
 * the rows show what is already chosen rather than droidtop's defaults.
 */
internal data class SetupBefore(
    val enabled: Set<Mode>,
    val defaultMode: Mode?,
    val home: HomeImplementation,
    val alternative: ComponentName?,
) {
    companion object {
        fun read(context: Context) = SetupBefore(
            enabled = Mode.entries.filterTo(mutableSetOf()) { Modes.isEnabledInStorage(context, it) },
            defaultMode = Mode.byId(Modes.defaultMode(context)),
            home = HomeRolePrefs.activeHomeImplementation(context),
            alternative = HomeRolePrefs.alternativeTarget(context),
        )
    }
}

/**
 * ONE run of onboarding, and everything that run has answered.
 *
 * Held in a [androidx.lifecycle.ViewModel] rather than in the
 * composition, because a configuration change destroys the Activity and
 * everything remembered inside it. On the rig (build 546) rotating the
 * device on a late step came back at step 1: the walk position and the
 * history were gone, and the answers with them.
 *
 * So the plan is a property of the RUN, not of the Activity instance
 * drawing it: [start] seeds the run once and every later call is a
 * no-op, and everything the plan and the walk are built from lives here.
 * The things that are NOT answers (what a games root turned out to hold,
 * what the theme list is) are derived from the device and stay out of
 * it, except the two caches that would otherwise re-walk the whole
 * library on every rotation.
 */
internal class OnboardingRun : androidx.lifecycle.ViewModel() {
    private var started = false

    /** The step this run opened on; null for a full first-run walk. */
    var startStep: OnboardingStep? = null
        private set

    /**
     * Whether a controller was attached when the run started. Asked once,
     * because a plan that changes under the person's feet cannot say
     * where to go next; with none attached there is nothing to test or
     * map, and Settings, Input, Controller opens the same step later.
     */
    var controllerAttachedAtEntry: Boolean = true
        private set

    /**
     * Whether the pad's layout was already identified (by the device, or by the pad's family)
     * when the run settled its plan. An identified pad has nothing to ask: the step would only
     * confirm what droidtop already knows, so it is not in the plan and the last screen names
     * the result instead. The answer arrives a moment after the run starts (the resolver
     * reads its tables off the main thread), so it can change only on the first step, the one
     * place the plan's total may change.
     */
    val controllerIdentified = mutableStateOf(false)

    /** The controller step is planned only for an attached pad nothing could identify. */
    val controllerAsks: Boolean get() = controllerAttachedAtEntry && !controllerIdentified.value

    fun settleController(identified: Boolean) {
        if (history.isEmpty() && step.value == OnboardingStep.MODE) controllerIdentified.value = identified
    }

    fun start(
        startStep: OnboardingStep?,
        controllerAttached: Boolean = true,
        before: SetupBefore? = null,
        saved: org.json.JSONObject? = null,
        persistent: Boolean = false,
    ) {
        if (started) return
        started = true
        this.controllerAttachedAtEntry = controllerAttached
        this.startStep = startStep
        this.persistent = persistent
        step.value = startStep ?: OnboardingStep.MODE
        if (before != null) {
            val appModes = before.enabled.filter { it != Mode.LAUNCHER }
            opensInto.value = before.defaultMode?.takeIf { it != Mode.LAUNCHER && it in appModes }
                ?: appModes.firstOrNull() ?: Mode.GAMING
            alsoOther.value = appModes.size > 1
            homeChoice.value = before.home
            homeShowsHomeScreen.value = before.home != HomeImplementation.NONE && before.defaultMode == Mode.LAUNCHER
            homeAlternative.value = before.alternative
        }
        saved?.let(::restore)
    }

    /**
     * Whether this run is written down as it goes ([toJson]) so a new
     * process resumes it: a first run walked in full. A single step
     * re-entered from Settings, or a rerun of a finished setup, is not.
     */
    var persistent: Boolean = false
        private set

    /**
     * The run's ANSWERS, not what the device holds: the folder list,
     * storage access, the home role and the theme are real state already
     * and are read back from where they live. The plan is rebuilt from
     * these, so a resumed run presents the same steps it was presenting.
     */
    fun toJson(): String = org.json.JSONObject().apply {
        put("step", step.value.name)
        put("history", org.json.JSONArray(history.map { it.name }))
        put("opensInto", opensInto.value.id)
        put("also", alsoOther.value)
        put("home", homeChoice.value.name)
        put("homeShowsHomeScreen", homeShowsHomeScreen.value)
        homeAlternative.value?.let { put("homeAlternative", it.flattenToString()) }
        put("desktopImage", desktopImageChosen.value)
        put("desktopCapable", desktopCapable.value)
        put("controllerAtEntry", controllerAttachedAtEntry)
        put("controllerIdentified", controllerIdentified.value)
    }.toString()

    /**
     * Reads a saved run, including one written by the flow before the
     * steps were merged (2026-10-01): its step names map onto the merged
     * steps ([OnboardingStep.byName]), and its three mode answers
     * ("gaming", "desktop", "mode") become the one "opens into" answer
     * plus "also set up the other". A run in progress when the app
     * updated resumes rather than starting over.
     */
    private fun restore(saved: org.json.JSONObject) {
        step.value = OnboardingStep.byName(saved.optString("step")) ?: return
        history.clear()
        saved.optJSONArray("history")?.let { names ->
            for (i in 0 until names.length()) {
                OnboardingStep.byName(names.optString(i))?.let { if (it !in history && it != step.value) history.add(it) }
            }
        }
        val oldMode = Mode.byId(saved.optString("mode").ifEmpty { null })
        val oldGaming = saved.optBoolean("gaming")
        val oldDesktop = saved.optBoolean("desktop")
        opensInto.value = Mode.byId(saved.optString("opensInto").ifEmpty { null })
            ?: oldMode?.takeIf { it != Mode.LAUNCHER }
            ?: if (oldDesktop && !oldGaming) Mode.DESKTOP else Mode.GAMING
        alsoOther.value = saved.optBoolean("also", oldGaming && oldDesktop)
        homeChoice.value = HomeImplementation.entries.firstOrNull { it.name == saved.optString("home") }
            ?: HomeImplementation.NONE
        homeShowsHomeScreen.value = saved.optBoolean("homeShowsHomeScreen", oldMode == Mode.LAUNCHER)
        homeAlternative.value = saved.optString("homeAlternative").ifEmpty { null }?.let(ComponentName::unflattenFromString)
        desktopImageChosen.value = saved.optBoolean("desktopImage")
        desktopCapable.value = saved.optBoolean("desktopCapable")
        controllerAttachedAtEntry = saved.optBoolean("controllerAtEntry", controllerAttachedAtEntry)
        controllerIdentified.value = saved.optBoolean("controllerIdentified", controllerIdentified.value)
    }

    // Each answer is a MutableState the screen delegates to (`var step by
    // run.step`), so the step functions read as if these were
    // `remember`ed; the only thing that differs is WHERE they live.
    val step = mutableStateOf(OnboardingStep.MODE)

    /**
     * The path actually taken, which is what Back walks: a person goes
     * back to the step they came from, not to a step the plan says comes
     * before this one on paper.
     */
    val history = mutableStateListOf<OnboardingStep>()

    /** Which app-hosted mode droidtop opens into: Gaming or Desktop, never the home screen. */
    val opensInto = mutableStateOf(Mode.GAMING)

    /** Whether the other app-hosted mode is set up as well (SPEC 7b, "What droidtop opens into"). */
    val alsoOther = mutableStateOf(false)

    val configureGaming: Boolean get() = opensInto.value == Mode.GAMING || alsoOther.value
    val configureDesktop: Boolean get() = opensInto.value == Mode.DESKTOP || alsoOther.value

    /** The Home button's answer; "keep it as it is" is the default, so there is always one. */
    val homeChoice = mutableStateOf(HomeImplementation.NONE)

    /**
     * With droidtop holding Home, whether Home shows a home screen
     * (droidtop's own, or the launcher picked) rather than [opensInto].
     * This is the old "Opens into Android" answer, folded into the Home
     * step: it decides the default mode (SPEC 2c, "Home goes to the
     * default mode").
     */
    val homeShowsHomeScreen = mutableStateOf(false)

    /** The launcher Home forwards to when [homeChoice] is Alternative. */
    val homeAlternative = mutableStateOf<ComponentName?>(null)

    /** The mode written as the default when the run finishes. */
    val defaultMode: Mode
        get() = if (homeChoice.value != HomeImplementation.NONE && homeShowsHomeScreen.value) Mode.LAUNCHER else opensInto.value

    val desktopImageChosen = mutableStateOf(false)
    val desktopCapable = mutableStateOf(false)
    val unresolvedFolderWarning = mutableStateOf(false)
    val pathEntry = mutableStateOf("")
    val pathError = mutableStateOf<String?>(null)
    val storageAccessGranted = mutableStateOf(false)
    val storageDenied = mutableStateOf(false)
    val storagePermanentlyDenied = mutableStateOf(false)
    val confirmLeaving = mutableStateOf(false)
    val structureReport = mutableStateOf<String?>(null)
    val rootsVersion = mutableStateOf(0)

    /**
     * What each root turned out to hold. Kept with the run rather than
     * recomputed: walking a real games root takes minutes, and a
     * rotation is not a reason to walk it again.
     */
    val rootReports = mutableStateMapOf<String, GamesRootReport.Report>()
    val rootProgress = mutableStateMapOf<String, GamesRootReport.Progress>()
}

/**
 * The steps, after the merge of 2026-10-01 (owner: "some pages should be
 * merged"): the home-screen question and the launcher list are one step,
 * the storage permission and the folders are one step, and "what to set
 * up" and "what to open into" are one step that doubles as the welcome.
 */
internal enum class OnboardingStep {
    MODE, HOME, SCREENS, GAMES, APPEARANCE, DESKTOP_SETUP, KEYBOARD, CONTROLLER, DEVICE_SETUP, DONE;

    companion object {
        /**
         * A step by name, accepting the names the flow used before the
         * merge: a saved first run, or a Settings row built against them,
         * lands on the merged step that now holds that question.
         */
        fun byName(name: String?): OnboardingStep? = when (name) {
            null, "" -> null
            "WELCOME", "CONFIGURE_MORE", "DEFAULT_MODE_CHOICE" -> MODE
            "HOME_CHOICE", "STANDARD_SETUP", "ALTERNATIVE_SETUP" -> HOME
            "STORAGE_PERMISSION", "GAMES_FOLDERS" -> GAMES
            "WHAT_NEXT" -> DONE
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/**
 * The steps this run will actually present, given the answers so far.
 * Progress is stated against THIS list, so "Step 3 of 6" counts the real
 * steps (owner, 2026-10-01): a person who is not setting up Desktop is
 * never told there are steps left that they will not see. The total can
 * only change on the first step, where the answer that decides it is
 * given.
 *
 * It is also the pipeline itself: [OnboardingScreen] advances to the
 * next entry after the current one instead of carrying a second,
 * separate `when` that could disagree with the count.
 */
internal fun plannedSteps(
    opensInto: Mode,
    alsoOther: Boolean,
    controllerAsks: Boolean = true,
    // Two screens connected when the run started: then which one the menus go on is asked (Droidtop/tracker#598).
    twoScreens: Boolean = false,
    // Android settings droidtop can change itself are left to change, and a shell can do it (Droidtop/tracker#816).
    deviceSetup: Boolean = false,
): List<OnboardingStep> = buildList {
    val gaming = opensInto == Mode.GAMING || alsoOther
    val desktop = opensInto == Mode.DESKTOP || alsoOther
    add(OnboardingStep.MODE)
    add(OnboardingStep.HOME)
    // Detection can only guess which panel is which (SPEC 4c), so a person with two says, before any mode's own pages.
    if (twoScreens) add(OnboardingStep.SCREENS)
    // Each mode's own steps together, in the order its pages are needed.
    if (gaming) {
        add(OnboardingStep.GAMES)
        add(OnboardingStep.APPEARANCE)
    }
    if (desktop) {
        add(OnboardingStep.DESKTOP_SETUP)
        // The keyboard is Desktop's: its reason is terminals and Windows
        // programs. Asked of a Gaming-only run, it was a question about
        // software the person had just said they did not want (rig,
        // dq-coordinator-24, finding 10).
        add(OnboardingStep.KEYBOARD)
    }
    // The pad is how the shell itself is driven, whichever mode is set up. It is a step only when
    // there is a question: with none attached there is nothing to map, and a pad droidtop already
    // identified is a fact the last screen states (2026-10-03).
    if (controllerAsks) add(OnboardingStep.CONTROLLER)
    // Both modes': the HOME combos are Gaming's, the child-process limit Desktop's and Windows games' (SPEC "Device setup").
    if (deviceSetup) add(OnboardingStep.DEVICE_SETUP)
    add(OnboardingStep.DONE)
}

/** What the scaffold's progress line and bar show: this step's place in the plan. */
internal data class StepProgress(val number: Int, val count: Int)

/**
 * Which of the two app-hosted modes are ON once onboarding finishes
 * (docs/SPEC.md 7b, "What droidtop opens into"). The answer IS the
 * switch: a mode not set up is switched off, and a switched-off mode
 * runs no code (SPEC 2c, Rule 1). Written only at the end, so leaving
 * part-way changes no mode.
 *
 * Desktop is also left off when its capability check failed on this
 * device. The one exception is the mode onboarding opens into, which is
 * on even when nothing was set up for it, because a mode cannot be
 * opened while it is off.
 */
internal fun appModesOnAfterOnboarding(
    configureGaming: Boolean,
    configureDesktop: Boolean,
    desktopCapable: Boolean,
    opensInto: Mode,
): Set<Mode> = buildSet {
    if (configureGaming) add(Mode.GAMING)
    // Ticked, but the capability check said it cannot run here: switching
    // it on would only offer a mode that opens onto its own failure.
    if (configureDesktop && desktopCapable) add(Mode.DESKTOP)
    if (opensInto != Mode.LAUNCHER) add(opensInto)
}

/**
 * [GamesRootPrefs.resolveStoragePath] can compute a perfectly correct real
 * path and it still won't matter: Android 11+ blocks plain `java.io.File`
 * access outside the app's own sandbox unless the app holds "All files
 * access" (MANAGE_EXTERNAL_STORAGE), which a SAF folder grant alone does
 * NOT provide for File-based I/O (only for the ContentResolver/DocumentFile
 * APIs, which [dev.droidtop.library.GameEngineDetector] doesn't use). A
 * games folder that "resolves" here but then silently shows zero games
 * because reads are denied at the OS level was confirmed on a live device
 * with SD-card-stored games. droidtop isn't Play-Store-distributed, so
 * requesting this permission directly is legitimate here the same way it
 * is for file-manager and ROM-manager apps generally.
 */
internal fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, LEGACY_STORAGE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

/**
 * The pre-API-30 equivalent of "All files access". Below API 30 there is
 * no MANAGE_EXTERNAL_STORAGE and no
 * ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION settings activity at all;
 * firing that intent there throws ActivityNotFoundException and killed
 * the app on the Android 9 rig (2026-09-11). minSdk is 26, so the legacy
 * runtime permission is a real supported path: on API 26-29 it grants
 * exactly the plain java.io.File reads across shared storage that
 * GameEngineDetector needs.
 */
private const val LEGACY_STORAGE_PERMISSION = android.Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
internal fun OnboardingScreen(run: OnboardingRun, isReEntry: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Every answer this run has given lives in `run` (see [OnboardingRun]),
    // not in this composition: the Activity is destroyed and rebuilt on
    // every rotation, and a walk remembered inside it does not survive
    // that. These are aliases onto the same state.
    var step by run.step
    val history = run.history
    var opensInto by run.opensInto
    var alsoOther by run.alsoOther
    var homeChoice by run.homeChoice
    var homeShowsHomeScreen by run.homeShowsHomeScreen
    var homeAlternative by run.homeAlternative
    var desktopImageChosen by run.desktopImageChosen
    var desktopCapable by run.desktopCapable
    var unresolvedFolderWarning by run.unresolvedFolderWarning
    var pathEntry by run.pathEntry
    var pathError by run.pathError
    var storageAccessGranted by run.storageAccessGranted
    var storageDenied by run.storageDenied
    var storagePermanentlyDenied by run.storagePermanentlyDenied
    var confirmLeaving by run.confirmLeaving
    var structureReport by run.structureReport
    var rootsVersion by run.rootsVersion

    // Storage access is real state, read when the screen appears and again
    // whenever onboarding comes back to the front (Android's own screens
    // decide it, and ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION does not
    // reliably report grant or deny through its result code). The system
    // folder picker is another activity too: when it returns, the folder
    // list is read again whatever order its result and the resume arrive in
    // (the tester's list did not change after a pick).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        storageAccessGranted = hasStorageAccess(context)
        rootsVersion++
        onPauseOrDispose { }
    }

    val roots = remember(rootsVersion) { GamesRootPrefs.gamesRootPaths(context) }
    // What each root turned out to hold. Filled in off the main thread as
    // roots appear; a root with no report yet shows "Looking…" rather
    // than a number it has not counted.
    val rootReports = run.rootReports
    val rootProgress = run.rootProgress

    LaunchedEffect(rootsVersion, roots) {
        roots.forEach { path ->
            if (!rootReports.containsKey(path)) {
                rootReports[path] = GamesRootReport.of(context, path) { rootProgress[path] = it }
            }
        }
        rootReports.keys.toList().forEach { if (it !in roots) rootReports.remove(it) }
    }

    val requestStorageAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        storageAccessGranted = hasStorageAccess(context)
        storageDenied = !storageAccessGranted
    }

    // API 26-29: the legacy runtime permission is the whole mechanism.
    val requestLegacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageAccessGranted = granted
        storageDenied = !granted
        // Android stops showing the prompt once a person has said no
        // twice, and shouldShowRequestPermissionRationale is how an app
        // learns that. Saying so beats asking again and again.
        storagePermanentlyDenied = !granted && !shouldShowStorageRationale(context)
    }

    // Whether Android's Home opens droidtop, read again whenever onboarding
    // comes back to the front (Android's own screens decide it).
    var droidtopIsHome by remember { mutableStateOf(HomeRolePrefs.isDroidtopHome(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        droidtopIsHome = HomeRolePrefs.isDroidtopHome(context)
        onPauseOrDispose { }
    }
    val requestHome = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        droidtopIsHome = HomeRolePrefs.isDroidtopHome(context)
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resolved = GamesRootPrefs.resolveStoragePath(uri)
        if (resolved != null) {
            GamesRootPrefs.addGamesRoot(context, resolved)
            rootsVersion++
        }
        unresolvedFolderWarning = resolved == null
    }

    // ONE plan per run, built from the first step's answer and from
    // whether a controller was attached when the run started.
    // Read once per run, like the controller: a screen plugged in later is Settings > Displays' business.
    val twoScreens = remember {
        (context.getSystemService(android.hardware.display.DisplayManager::class.java)?.displays?.size ?: 1) > 1
    }
    // Read once per run, off the main thread: it asks the shell. Known well before the first step is answered.
    var deviceSetup by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { deviceSetup = withContext(Dispatchers.IO) { runCatching { deviceSetupAsks(context) }.getOrDefault(false) } }
    val plan = plannedSteps(opensInto, alsoOther, run.controllerAsks, twoScreens, deviceSetup)
    val padReady = ControllerLayouts.ready
    val padKnown = ControllerLayouts.layout.known
    LaunchedEffect(padReady, padKnown) { if (padReady) run.settleController(padKnown) }

    // Written down as it changes, so a new process resumes this run where
    // it was (OnboardingRun.persistent): becoming the Home app, a kill from
    // Recents or a crash no longer costs anyone their answers.
    if (run.persistent) {
        LaunchedEffect(run) {
            snapshotFlow { run.toJson() }.collect { OnboardingProgress.save(context, it) }
        }
    }

    fun goTo(next: OnboardingStep) {
        history.add(step)
        step = next
    }

    /** Advance along the plan, or finish outright on a re-entered single step. */
    fun advanceFrom(current: OnboardingStep) {
        if (isReEntry) {
            onDone()
            return
        }
        val here = plan.indexOf(current)
        val next = when {
            here >= 0 && here + 1 < plan.size -> plan[here + 1]
            // A step that is not in the plan at all is a bug, but it must
            // not cost the user the rest of their setup: continue with the
            // first planned step they have not seen yet, and only finish
            // when there genuinely is none.
            here < 0 -> plan.firstOrNull { it != current && it !in history } ?: OnboardingStep.DONE
            else -> OnboardingStep.DONE
        }
        goTo(next)
    }

    /**
     * The Home step's answer, applied: which of droidtop's HOME activities
     * is enabled, and Android asked for the role when droidtop does not
     * hold it. Android shows its own confirmation over the next step; the
     * summary says whether it took.
     */
    fun applyHomeChoice() {
        val choice = homeChoice
        if (choice == HomeImplementation.ALTERNATIVE) homeAlternative?.let { HomeRolePrefs.setAlternativeTarget(context, it) }
        HomeRolePrefs.setActiveHomeImplementation(context, choice)
        if (choice != HomeImplementation.NONE && !HomeRolePrefs.isDroidtopHome(context)) {
            requestHome.launch(HomeRolePrefs.homeRequestIntent(context))
        }
    }

    fun finishOnboarding() {
        // Written only here, at the end: a person who leaves part-way is
        // told nothing they set is lost, and nothing about the modes is
        // changed either.
        val on = appModesOnAfterOnboarding(run.configureGaming, run.configureDesktop, desktopCapable, opensInto)
        listOf(Mode.GAMING, Mode.DESKTOP).forEach { mode ->
            if (Modes.isEnabledInStorage(context, mode) != (mode in on)) {
                Modes.setEnabled(context, mode, mode in on)
            }
        }
        val default = run.defaultMode
        Modes.setDefaultMode(context, default)
        Modes.setLastMode(context, default)
        GamesRootPrefs.markOnboardingComplete(context)
        // The modes are on from here (Modes.reload reads the finished
        // setup), so ModeStartup starts what they own now, not at the
        // next process start.
        Modes.reload(context)
        onDone()
    }

    val canGoBack = !isReEntry && history.isNotEmpty()

    // System Back is the same control as the scaffold's Back, and leaving
    // is a deliberate act: one Back press on the first step used to drop
    // the whole flow to the system home with nothing saved. The pad's B is
    // the same control again (ownPadButtons, below).
    fun handleBack() {
        when {
            isReEntry -> onDone()
            history.isNotEmpty() -> step = history.removeAt(history.lastIndex)
            else -> confirmLeaving = true
        }
    }
    BackHandler(enabled = true) { handleBack() }

    if (confirmLeaving) {
        AlertDialog(
            onDismissRequest = { confirmLeaving = false },
            title = { Text(stringResource(R.string.onboarding_leave_setup)) },
            text = {
                Text(
                    "Your answers are kept. Open droidtop again to carry on from this step. " +
                        "Until then, Home shows your usual home screen.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeaving = false
                    dev.droidtop.shell.standard.OnboardingGate.leave()
                    onDone()
                }) { Text(stringResource(R.string.onboarding_leave)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeaving = false }) { Text(stringResource(R.string.onboarding_keep_setting_up)) } },
        )
    }

    val progress = if (isReEntry) null else StepProgress(number = plan.indexOf(step).coerceAtLeast(0) + 1, count = plan.size)
    val back: (() -> Unit)? = if (canGoBack) ({ step = history.removeAt(history.lastIndex) }) else null

    // The outermost node owns the pad (ownPadButtons): B is Back here as
    // everywhere else in droidtop, and a pad button nothing below wanted
    // is not handed to Android to turn into a key the step never asked for.
    Box(Modifier.fillMaxSize().ownPadButtons(::handleBack)) {
    when (step) {
        OnboardingStep.MODE -> ModeStep(
            progress, back,
            isReEntry = isReEntry,
            opensInto = opensInto,
            alsoOther = alsoOther,
            onOpensInto = { opensInto = it },
            onAlsoOther = { alsoOther = it },
            onContinue = { advanceFrom(OnboardingStep.MODE) },
        )

        OnboardingStep.SCREENS -> ScreensStep(
            progress, back,
            isReEntry = isReEntry,
            onContinue = { advanceFrom(OnboardingStep.SCREENS) },
        )

        OnboardingStep.HOME -> HomeStep(
            progress, back,
            isReEntry = isReEntry,
            opensInto = opensInto,
            choice = homeChoice,
            showsHomeScreen = homeShowsHomeScreen,
            alternative = homeAlternative,
            onSelect = { choice, showsHomeScreen, alternative ->
                homeChoice = choice
                homeShowsHomeScreen = showsHomeScreen
                homeAlternative = alternative
            },
            onContinue = {
                applyHomeChoice()
                advanceFrom(OnboardingStep.HOME)
            },
        )

        OnboardingStep.DESKTOP_SETUP -> DesktopSetupStep(
            progress, back,
            onCapabilityKnown = { desktopCapable = it },
            onContinue = { chose ->
                desktopImageChosen = chose
                advanceFrom(OnboardingStep.DESKTOP_SETUP)
            },
        )

        OnboardingStep.GAMES -> GamesStep(
            progress, back,
            isReEntry = isReEntry,
            storage = StorageState(
                granted = storageAccessGranted,
                legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
                denied = storageDenied,
                permanentlyDenied = storagePermanentlyDenied,
            ),
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    requestStorageAccess.launch(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                } else {
                    requestLegacyStorage.launch(LEGACY_STORAGE_PERMISSION)
                }
            },
            roots = roots,
            reports = rootReports,
            scanProgress = rootProgress,
            unresolvedFolderWarning = unresolvedFolderWarning,
            structureReport = structureReport,
            onAddFolder = { pickFolder.launch(null) },
            onRemoveRoot = { path ->
                GamesRootPrefs.removeGamesRoot(context, path)
                rootReports.remove(path)
                rootsVersion++
            },
            onGenerateStructure = { path ->
                scope.launch {
                    val created = EsDeFolderStructure.generate(context, File(path))
                    structureReport = EsDeFolderStructure.describe(created)
                    rootReports[path] = GamesRootReport.of(context, path) { rootProgress[path] = it }
                }
            },
            pathEntry = pathEntry,
            pathError = pathError,
            onPathEntryChange = {
                pathEntry = it
                pathError = null
            },
            onAddPath = {
                val error = GamesRootPrefs.addGamesRootByPath(context, pathEntry)
                pathError = error
                if (error == null) {
                    pathEntry = ""
                    unresolvedFolderWarning = false
                    rootsVersion++
                }
            },
            onAddSuggested = { path ->
                val error = GamesRootPrefs.addGamesRootByPath(context, path)
                pathError = error
                if (error == null) rootsVersion++
            },
            onContinue = { advanceFrom(OnboardingStep.GAMES) },
        )

        OnboardingStep.CONTROLLER -> ControllerStep(
            progress, back,
            isReEntry = isReEntry,
            onContinue = { advanceFrom(OnboardingStep.CONTROLLER) },
        )

        OnboardingStep.DEVICE_SETUP -> DeviceSetupStep(
            progress, back,
            isReEntry = isReEntry,
            onContinue = { advanceFrom(OnboardingStep.DEVICE_SETUP) },
        )

        OnboardingStep.APPEARANCE -> AppearanceStep(
            progress, back,
            isReEntry = isReEntry,
            onContinue = { advanceFrom(OnboardingStep.APPEARANCE) },
        )

        OnboardingStep.KEYBOARD -> KeyboardStep(
            progress, back,
            isReEntry = isReEntry,
            onEnable = { dev.droidtop.library.settings.Keyboards.openSystemSettings(context) },
            onPick = { dev.droidtop.library.settings.Keyboards.showPicker(context) },
            onContinue = { advanceFrom(OnboardingStep.KEYBOARD) },
        )

        OnboardingStep.DONE -> DoneStep(
            progress, back,
            opensInto = opensInto,
            defaultMode = run.defaultMode,
            homeChoice = homeChoice,
            homeHeld = droidtopIsHome,
            homeAlternativeLabel = homeAlternative?.let { launcherLabel(context, it) },
            gamingConfigured = run.configureGaming,
            desktopConfigured = run.configureDesktop && desktopImageChosen && desktopCapable,
            desktopOffered = run.configureDesktop,
            gamesFound = rootReports.values.sumOf { it.total },
            // A root whose count has not come back yet is still being
            // walked: the summary must say so rather than report the
            // zero it has not finished counting.
            gamesStillCounting = roots.any { it !in rootReports.keys },
            gamesSoFar = rootProgress.filterKeys { it !in rootReports.keys }.values.sumOf { it.gamesSoFar },
            noFolders = roots.isEmpty(),
            storageGranted = storageAccessGranted,
            controllerFacts = ControllerLayouts.layout.takeIf { run.controllerAttachedAtEntry && it.known }?.let(::controllerFacts),
            controllerAnswered = !run.controllerAttachedAtEntry || ControllerLayouts.isAnswered(),
            keyboardSkipped = run.configureDesktop && !dev.droidtop.library.settings.Keyboards.ownKeyboardActive(context),
            onAddGames = { goTo(OnboardingStep.GAMES) },
            onFinish = {
                val default = run.defaultMode
                val home = homeChoice
                finishOnboarding()
                // droidtop's one icon on the home screen it just set up, so
                // the way into Gaming or Desktop is in sight rather than in
                // a drawer or behind a gesture (SPEC 2c).
                if (home == HomeImplementation.STANDARD) HomeRolePrefs.placeDroidtopIcon(context)
                if (default == Mode.LAUNCHER) {
                    // The home screen is not a MainActivity shell. Sent
                    // there with "standard", MainActivity skipped it as
                    // not its own and opened Gaming.
                    BackButtonMenu.openHome(context, home)
                } else {
                    context.startActivity(
                        Intent(Intent.ACTION_MAIN).apply {
                            setClassName(context.packageName, "dev.droidtop.app.MainActivity")
                            putExtra(BackButtonMenu.EXTRA_MODE, default.id)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
            },
        )
    }
    }
}

internal fun shouldShowStorageRationale(context: Context): Boolean {
    val activity = context as? android.app.Activity ?: return true
    return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, LEGACY_STORAGE_PERMISSION)
}

/** An installed launcher's own application label; never a class name. */
internal fun launcherLabel(context: Context, component: ComponentName): String? {
    val pm = context.packageManager
    return runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(component.packageName, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

// ---------------------------------------------------------------------
// The frame every step renders into
// ---------------------------------------------------------------------

/**
 * The label of a step's ONE forward action (docs/SPEC.md 7b, "One way
 * forward"). A step never offers two ways forward at once, so this one
 * action has to say which it is: before the step has been answered it is
 * the SKIP and says so, afterwards it is Next, and a step opened on its
 * own from Settings has nothing after it so it is Done.
 *
 * Build 547 shipped a text "Skip" beside a filled "Next" on the
 * Controller step and a disabled "Next" beside "Skip for now" on the
 * Desktop one; in both, two actions moved forward and neither said which
 * one moved on without answering.
 */
/**
 * What onboarding's Desktop check says, in plain words. Pure.
 *
 * A device whose kernel refuses an app `ptrace(PTRACE_TRACEME)` cannot run proot at all (docs/SPEC.md 3, "Not every
 * Android lets an app trace its children"; the BlueStacks rigs, dq-desktop-04 and Droidtop/tracker#555), but the root
 * backend needs no tracing, so that case names the way that works here: Shizuku started as root. Any other failure
 * keeps the short sentence; the backend's own text goes to the log.
 */
internal fun desktopCheckMessage(succeeded: Boolean, rooted: Boolean, error: String): String = when {
    succeeded && rooted -> "Desktop can run here (rooted, full speed)."
    succeeded -> "Desktop can run here (not rooted, a little slower)."
    rooted -> "Desktop can't start yet. Try again later in Settings."
    error.contains("ptrace", ignoreCase = true) ->
        "Desktop can't run here without root: this device doesn't let apps run Linux programs the unrooted way. " +
            "With Shizuku started as root, it can."
    else -> "Desktop can't run on this device."
}

internal fun onboardingForwardLabel(reEntry: Boolean, answered: Boolean): String = when {
    reEntry -> "Done"
    answered -> "Next"
    else -> "Skip this step"
}
