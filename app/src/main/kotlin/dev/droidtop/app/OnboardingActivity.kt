package dev.droidtop.app

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
import dev.droidtop.shell.gamepad.TouchHintBar
import dev.droidtop.shell.gamepad.input.GamepadAction
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.app.ui.PadButton
import dev.droidtop.app.ui.SelectableRow
import dev.droidtop.library.GamesRootReport
import dev.droidtop.library.consoles.EsDeFolderStructure
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemeDownloader
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
import dev.droidtop.shell.gamepad.input.ControllerPrefs
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.ThemeSystemPreview
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.HomeRolePrefs
import dev.droidtop.shell.standard.HomeRolePrefs.HomeImplementation
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val padGate = dev.droidtop.shell.gamepad.input.PadGate(deliver = { event -> deliverKey(event) })

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
        // The pad's face-button answer, if one was given, before the
        // first key is read.
        GamepadKeyMap.load(this)
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
            controllerAttached = ControllerPrefs.attachedControllers().isNotEmpty(),
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
            // Onboarding is dark, like the shell it hands over to. It used
            // to follow the system setting, so a device in light mode got
            // a white first run that dropped into an always-dark Gaming
            // shell at the end of it (SPEC 7b).
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
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
    MODE, HOME, GAMES, APPEARANCE, DESKTOP_SETUP, KEYBOARD, CONTROLLER, DONE;

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
    controllerAttached: Boolean = true,
): List<OnboardingStep> = buildList {
    val gaming = opensInto == Mode.GAMING || alsoOther
    val desktop = opensInto == Mode.DESKTOP || alsoOther
    add(OnboardingStep.MODE)
    add(OnboardingStep.HOME)
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
    // The pad is how the shell itself is driven, whichever mode is set up;
    // with none attached there is nothing to test or map.
    if (controllerAttached) add(OnboardingStep.CONTROLLER)
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
private fun hasStorageAccess(context: Context): Boolean =
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
private fun OnboardingScreen(run: OnboardingRun, isReEntry: Boolean, onDone: () -> Unit) {
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
    val plan = plannedSteps(opensInto, alsoOther, run.controllerAttachedAtEntry)

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
        // An Art Book Next download still running at this point keeps
        // running (OnboardingThemeDownload's own doc comment); only its
        // late auto-activation is disowned here, since the person has
        // already moved on to whatever theme resolved for Gaming mode.
        OnboardingThemeDownload.disownIfIncomplete()
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
            title = { Text("Leave setup?") },
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
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeaving = false }) { Text("Keep setting up") } },
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
            controllerAnswered = ControllerPrefs.asked(context),
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

private fun shouldShowStorageRationale(context: Context): Boolean {
    val activity = context as? android.app.Activity ?: return true
    return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, LEGACY_STORAGE_PERMISSION)
}

/** An installed launcher's own application label; never a class name. */
private fun launcherLabel(context: Context, component: ComponentName): String? {
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
internal fun onboardingForwardLabel(reEntry: Boolean, answered: Boolean): String = when {
    reEntry -> "Done"
    answered -> "Next"
    else -> "Skip this step"
}

/**
 * One action in the scaffold's action area. It has no disabled state:
 * an onboarding step's actions are always actionable.
 */
private data class StepAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The one scaffold (docs/SPEC.md 7b, "The frame every step renders into").
 *
 * Progress at the top with a working Back beside it; the title, a body
 * capped to a readable measure and the step's own content in a scrolling
 * middle; and the actions docked at the bottom, where a thumb is, with
 * the step's own advance at full weight. Nothing is vertically centred:
 * the old flow centred every step in the window, which left ~800px of
 * dead space above the title on a portrait screen and put the buttons in
 * the middle of it.
 */
@Composable
private fun OnboardingScaffold(
    title: String,
    body: String?,
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    primary: StepAction?,
    secondary: StepAction? = null,
    // Where the pad's selection starts: the step's forward action, or,
    // on a step that asks something, its first answer. Nothing focused
    // meant a pad press landed nowhere: A did nothing on Welcome and
    // D-pad Down went to the Back button (dq-coordinator-24, finding 7).
    focusContentFirst: Boolean = false,
    // A specific control that takes the pad's selection first, when the step
    // has a clearer first action than Next (Your games: "Add a folder").
    initialFocus: FocusRequester? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val window = currentShellWindow()
    val primaryFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var holdsFocus by remember { mutableStateOf(false) }
    // Keyed on the title: a new step is a new title, and focus moves to it.
    // A step's answers can arrive a moment after the step does (a list
    // read off the main thread), so the preferred target is asked again
    // until something holds the selection, and the forward action takes
    // it when nothing else can.
    LaunchedEffect(title) {
        repeat(FOCUS_ATTEMPTS) {
            runCatching {
                when {
                    initialFocus != null -> initialFocus.requestFocus()
                    primary != null && !focusContentFirst -> primaryFocus.requestFocus()
                    else -> contentFocus.requestFocus()
                }
            }
            delay(FOCUS_RETRY_MS)
            if (holdsFocus) return@LaunchedEffect
        }
        if (primary != null) runCatching { primaryFocus.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { holdsFocus = it.hasFocus }
            .background(MaterialTheme.colorScheme.background)
            // The action area is docked at the bottom, so it is the first
            // thing a keyboard covers: typing a games-folder path put Next
            // underneath the IME with no way to reach it (phone AVD,
            // 2026-09-11). imePadding lifts the whole frame instead, which
            // is why the activity fits its own insets rather than letting
            // the decor do it.
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = window.edgePadding),
        ) {
            // --- progress and Back ---------------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Space.Lg, bottom = Space.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.Md),
            ) {
                if (onBack != null) {
                    PadButton("Back", onBack)
                }
                if (progress != null) {
                    Text(
                        "Step ${progress.number} of ${progress.count}",
                        color = MenuTokens.OnSurfaceMuted,
                        style = TypeRole.sectionLabel,
                    )
                }
            }
            if (progress != null) StepsBar(progress)

            // --- content --------------------------------------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = Space.Xl, bottom = Space.Lg),
                verticalArrangement = Arrangement.spacedBy(Space.Md),
            ) {
                Text(title, color = MenuTokens.OnSurface, style = TypeRole.screenTitle)
                if (body != null) {
                    Text(
                        body,
                        color = MenuTokens.OnSurfaceMuted,
                        style = TypeRole.body,
                        modifier = Modifier.widthIn(max = Measure.bodyMaxWidth),
                    )
                }
                // A focus group, so asking it for focus hands the selection
                // to its first focusable answer.
                Column(
                    modifier = Modifier.fillMaxWidth().focusRequester(contentFocus).focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(Space.Md),
                    content = content,
                )
            }

            // --- the action area, docked ----------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.Lg),
                horizontalArrangement = Arrangement.spacedBy(Space.Md, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                secondary?.let { PadButton(it.label, it.onClick) }
                primary?.let {
                    PadButton(
                        it.label,
                        it.onClick,
                        filled = true,
                        // On a phone the primary fills the row; at TV
                        // distance it stays a button on the right.
                        modifier = Modifier
                            .focusRequester(primaryFocus)
                            .then(if (window.portrait) Modifier.weight(1f) else Modifier),
                    )
                }
            }
        }
        // The shell's hint row: the legend of what the pad's buttons do
        // here, and on a touch screen the buttons themselves.
        TouchHintBar(
            hints = listOf(
                GamepadAction.A to "Select",
                GamepadAction.B to "Back",
            ),
        )
    }
}

private const val FOCUS_ATTEMPTS = 10
private const val FOCUS_RETRY_MS = 100L

/**
 * One segment per step of the plan: filled for the steps behind and the
 * one being answered, a track for the ones ahead. Drawn rather than
 * Material's LinearProgressIndicator, whose end-of-track stop dot read as
 * a stray mark at the far right (dq-coordinator-24, finding 9).
 */
@Composable
private fun StepsBar(progress: StepProgress) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        repeat(progress.count) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = Space.Xs)
                    .background(
                        if (index < progress.number) MenuTokens.Accent else MenuTokens.Surface,
                        RoundedCornerShape(50),
                    ),
            )
        }
    }
}

/** A group marker inside a step, the same one the shell's menus use. */
@Composable
private fun StepSectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MenuTokens.SectionLabel,
        style = TypeRole.sectionLabel,
        modifier = Modifier.padding(top = Space.Md, bottom = Space.Xs),
    )
}

/** A quiet line of supporting prose inside a step's content. */
@Composable
private fun StepNote(text: String, accent: Boolean = false) {
    Text(
        text,
        color = if (accent) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
        style = TypeRole.supporting,
        modifier = Modifier.widthIn(max = Measure.bodyMaxWidth),
    )
}

// ---------------------------------------------------------------------
// The steps
// ---------------------------------------------------------------------

/**
 * MODE. The welcome and the first question in one (docs/SPEC.md 7b, "What
 * droidtop opens into"): which app-hosted mode droidtop opens into, Gaming
 * preselected, with the other mode offered as a second thing to set up.
 * This replaced three pages: a welcome with nothing to answer, "Anything
 * else to set up?" (two ticks) and "Which should droidtop open into?",
 * which asked about the same two modes twice (owner, 2026-10-01).
 */
@Composable
private fun ModeStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    opensInto: Mode,
    alsoOther: Boolean,
    onOpensInto: (Mode) -> Unit,
    onAlsoOther: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    val other = if (opensInto == Mode.GAMING) Mode.DESKTOP else Mode.GAMING
    OnboardingScaffold(
        title = if (isReEntry) "What droidtop opens into" else "Welcome to droidtop",
        // Two lines, so the "also" row below the two answers is on screen
        // on a 1080p landscape handheld: a three-line body put it under the
        // action area (emulator-5560, d01-welcome.png).
        body = "droidtop can be a games console or a desktop computer. Which should it open into? " +
            "You can change this later in Settings.",
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (isReEntry) "Done" else "Next", onClick = onContinue),
        focusContentFirst = true,
    ) {
        SelectableRow(
            title = "Gaming",
            supporting = "All your games in one place, made for a controller or touch.",
            selected = opensInto == Mode.GAMING,
            onClick = { onOpensInto(Mode.GAMING) },
        )
        SelectableRow(
            title = "Desktop",
            supporting = "A Linux desktop that runs PC programs in windows. It downloads a Linux system " +
                "the first time it starts.",
            selected = opensInto == Mode.DESKTOP,
            onClick = { onOpensInto(Mode.DESKTOP) },
        )
        // A title-only row: with a label above it and a line under it, it
        // was the one answer a touch user could not see without scrolling.
        SelectableRow(
            title = "Set up ${other.label} too",
            selected = alsoOther,
            onClick = { onAlsoOther(!alsoOther) },
        )
    }
}

/**
 * HOME. One list for everything the Home button can do (docs/SPEC.md 7b,
 * "The Home button"): leave it alone, make droidtop the Home app and go
 * straight to the chosen mode, make droidtop's own home screen the Home
 * screen, or keep an installed launcher with droidtop underneath it. It
 * replaced three pages: the behaviour question, a page about droidtop's
 * launcher with nothing to decide, and the launcher list.
 *
 * Making droidtop the Home app is Android's to confirm (SPEC 2c, "Holding
 * the role"): Next asks Android, whose own screen appears over the next
 * step, and the summary says whether it took.
 */
@Composable
private fun HomeStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    opensInto: Mode,
    choice: HomeImplementation,
    showsHomeScreen: Boolean,
    alternative: ComponentName?,
    onSelect: (HomeImplementation, showsHomeScreen: Boolean, alternative: ComponentName?) -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    // The installed launchers, read off the main thread once per visit.
    val launchers by produceState<List<InstalledLauncher>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { installedLaunchers(context) }
    }
    val mode = opensInto.label
    OnboardingScaffold(
        title = "The Home button",
        // One line: the rows are the answer, and three of them fit above
        // the action area on a 1080p landscape screen only with a one-line
        // body (emulator-5560, t02-home.png). The Android confirmation is
        // said on the rows that cause it.
        body = "What should open when you press Home?",
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (isReEntry) "Done" else "Next", onClick = onContinue),
        focusContentFirst = true,
    ) {
        SelectableRow(
            title = "Keep my home screen as it is",
            supporting = "Nothing changes. Open droidtop from its icon, like any other app.",
            selected = choice == HomeImplementation.NONE,
            onClick = { onSelect(HomeImplementation.NONE, false, null) },
        )
        SelectableRow(
            title = "droidtop",
            supporting = "Home takes you straight to $mode. Android asks you to confirm the change.",
            selected = choice == HomeImplementation.STANDARD && !showsHomeScreen,
            onClick = { onSelect(HomeImplementation.STANDARD, false, null) },
        )
        SelectableRow(
            title = "droidtop's home screen",
            supporting = "An app drawer and widgets, made by droidtop. $mode opens from its icon.",
            selected = choice == HomeImplementation.STANDARD && showsHomeScreen,
            onClick = { onSelect(HomeImplementation.STANDARD, true, null) },
        )
        launchers?.forEach { launcher ->
            SelectableRow(
                title = launcher.label,
                supporting = "Home opens ${launcher.label}, with droidtop underneath to switch to $mode and back.",
                selected = choice == HomeImplementation.ALTERNATIVE && alternative == launcher.component,
                icon = launcher.icon,
                onClick = { onSelect(HomeImplementation.ALTERNATIVE, true, launcher.component) },
            )
        }
    }
}

private class InstalledLauncher(val component: ComponentName, val label: String, val icon: android.graphics.drawable.Drawable?)

/**
 * Every other installed home activity, with its application label (never a
 * class name, never a label that names nothing: the host launcher's
 * activity label on the rig was the single word "Home") and its icon.
 * Android's own placeholder home (Settings' FallbackHome, priority -1000)
 * answers the HOME query but is not a launcher.
 */
private fun installedLaunchers(context: Context): List<InstalledLauncher> {
    val pm = context.packageManager
    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    return pm.queryIntentActivities(homeIntent, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .filter { it.priority > -1000 && !it.activityInfo.name.endsWith("FallbackHome") }
        .map { info ->
            InstalledLauncher(
                ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                runCatching { pm.getApplicationLabel(info.activityInfo.applicationInfo).toString() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: info.loadLabel(pm).toString(),
                runCatching { info.loadIcon(pm) }.getOrNull(),
            )
        }
        .distinctBy { it.component }
}

@Composable
private fun DesktopSetupStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    onCapabilityKnown: (Boolean) -> Unit,
    onContinue: (imageChosen: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var checkResult by remember { mutableStateOf<Boolean?>(null) }
    var checkMessage by remember { mutableStateOf("") }
    var repositories by remember { mutableStateOf<List<KnownImageRepository>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        repositories = withContext(Dispatchers.IO) {
            BundledImageRepositories.load(context).repositories
                // arm64 is the hard filter: droidtop only targets ARM64
                // hardware; an amd64-only entry (e.g. official Arch) can't
                // run here regardless of anything else (docs/SPEC.md §3a).
                .filter { it.arm64Available }
                .filter { it.role == ImageCatalogRole.PRIMARY || it.role == ImageCatalogRole.BOTH }
        }
        // ONLY a previously-made real choice pre-selects: droidtop never
        // picks an image the user didn't (docs/SPEC.md §3a).
        selectedId = DesktopSetupPrefs.preferredPrimaryImageId(context)
        // Whichever backend this device gets (root: droidspaces, otherwise
        // proot) answers for itself by running something real.
        val runtime = withContext(Dispatchers.IO) { ContainerRuntimeFactory.select(context) }
        val result = withContext(Dispatchers.IO) { runtime.checkSystemRequirements() }
        checkResult = result.succeeded
        // The person is told the outcome in plain words; the backend's own
        // error text goes to the log, where a failure can still be diagnosed.
        if (!result.succeeded) {
            android.util.Log.w("Onboarding", "Desktop check failed: ${result.stderr.ifBlank { result.stdout }.trim()}")
        }
        onCapabilityKnown(result.succeeded)
        checkMessage = when {
            result.succeeded && runtime is DroidSpacesRuntime ->
                "Desktop can run here. This device is rooted, so it runs at full speed, kept apart from the rest of your device."
            result.succeeded ->
                "Desktop can run here. This device isn't rooted, so it runs a little slower. There is nothing to allow."
            runtime is DroidSpacesRuntime ->
                "This device is rooted, but the test did not pass, so Desktop can't start yet. " +
                    "Finish setup now and try again later in Settings."
            else ->
                "Desktop can't run on this device: it could not start a test program. You can finish setup without it."
        }
    }

    val capable = checkResult == true
    OnboardingScaffold(
        title = "Desktop setup",
        body = "Desktop needs a Linux system to run. Pick one here; it downloads the first time Desktop starts.",
        progress = progress,
        onBack = onBack,
        // One forward action (docs/SPEC.md 7b): the skip, until an image
        // has actually been chosen. A disabled "Next" beside a "Skip for
        // now" was two ways forward with the working one greyed out.
        primary = StepAction(
            label = if (!capable) {
                // Nothing to answer and nothing to skip: the mode cannot
                // run here, and the step said so above.
                "Continue without Desktop"
            } else {
                onboardingForwardLabel(reEntry = false, answered = selectedId != null)
            },
        ) {
            if (capable && selectedId != null) {
                DesktopSetupPrefs.setPreferredPrimaryImageId(context, selectedId)
            }
            onContinue(capable && selectedId != null)
        },
    ) {
        when (checkResult) {
            null -> StepNote("Checking whether this device can run Desktop.")
            true -> StepNote(checkMessage, accent = true)
            false -> StepNote(checkMessage)
        }
        // The distro list is not offered under a gate that makes it
        // unusable: when the mode cannot run here, droidtop says so and
        // does not present a choice underneath it (SPEC 7b).
        if (capable) {
            StepSectionLabel("Linux systems")
            repositories.forEach { repo ->
                SelectableRow(
                    title = repo.desktopEnvironment?.let { "${repo.os} with $it" } ?: repo.os,
                    supporting = if (repo.officialSource) "Made by the distro itself." else "A community build for ARM.",
                    selected = repo.id == selectedId,
                    onClick = { selectedId = repo.id },
                )
            }
        }
    }
}

/** Where storage access stands, as the Your games step draws it. */
private data class StorageState(
    val granted: Boolean,
    val legacy: Boolean,
    val denied: Boolean,
    val permanentlyDenied: Boolean,
)

/**
 * GAMES. The storage permission and the game folders on one page
 * (docs/SPEC.md 7b, "Your games"; owner, 2026-10-01). The question is one
 * ("where are your games?"); the permission is the thing Android needs
 * before the folders can be read, so the page explains it, hands over to
 * Android, and on return turns into the folder list. It never claims
 * droidtop cannot read other data (all-files access is exactly the
 * ability to); it says what droidtop reads: the folders you choose.
 *
 * The permission half is skipped outright when the permission is already
 * held, since the page simply starts in its folders state; granting it on
 * the page itself no longer changes the plan, because the plan has one
 * step here whichever state it is in (the old flow's permission step fell
 * out of the plan while the person stood on it; rig, build 531).
 */
@Composable
private fun GamesStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    storage: StorageState,
    onGrant: () -> Unit,
    roots: Set<String>,
    reports: Map<String, GamesRootReport.Report>,
    scanProgress: Map<String, GamesRootReport.Progress>,
    unresolvedFolderWarning: Boolean,
    structureReport: String?,
    onAddFolder: () -> Unit,
    onRemoveRoot: (String) -> Unit,
    onGenerateStructure: (String) -> Unit,
    pathEntry: String,
    pathError: String?,
    onPathEntryChange: (String) -> Unit,
    onAddPath: () -> Unit,
    onAddSuggested: (String) -> Unit,
    onContinue: () -> Unit,
) {
    if (!storage.granted) {
        OnboardingScaffold(
            title = "Your games",
            // The reason comes BEFORE the prompt, per Android's own guidance
            // and SPEC 7b: what droidtop reads, and what the button does.
            body = if (storage.legacy) {
                "Where are your games? droidtop reads only the folders you choose. Before it can look " +
                    "inside them, Android asks you to allow file access. Tap Allow file access, and Android " +
                    "will ask you to confirm."
            } else {
                "Where are your games? droidtop reads only the folders you choose. Before it can look " +
                    "inside them, Android needs you to allow file access on its own settings screen: tap " +
                    "Allow file access, turn on \"Allow access to manage all files\", then come back here."
            },
            progress = progress,
            onBack = onBack,
            primary = StepAction("Allow file access", onClick = onGrant),
            secondary = StepAction(if (isReEntry) "Done" else "Skip this step", onClick = onContinue),
        ) {
            when {
                storage.permanentlyDenied -> StepNote(
                    "Android won't ask again. To allow it later, open Android's settings for droidtop, " +
                        "then come back to Game folders in Settings.",
                )
                storage.denied -> StepNote(
                    "Without it, droidtop can't look in your folders. Everything else still works, and " +
                        "you can allow it later under Game folders in Settings.",
                )
                else -> Unit
            }
        }
        return
    }

    // The path box keeps focus (and the keyboard) while the system picker is
    // up, and both were still there when it returned, over a list that had
    // not moved (tester, 2026-09-29): focus is let go before the picker opens.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var pathFieldFocused by remember { mutableStateOf(false) }
    val addFolderFocus = remember { FocusRequester() }
    // The typed path is the route for what the picker cannot reach. It is
    // behind one button, because a newcomer met a path field and a keyboard
    // before they met the picker (product review 2026-10-01, item 9).
    var typingPath by remember { mutableStateOf(pathEntry.isNotBlank()) }
    // Looked for once per visit: a directory listing of /storage and
    // /mnt/windows, off the main thread.
    val found by produceState<List<String>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { suggestedGameFolders() }
    }
    // "Game folders" is the ONE name for this concept, everywhere in
    // droidtop; it used to be "ROM folders" in Settings and "Game
    // folders" here, two names one navigation step apart.
    OnboardingScaffold(
        title = "Your games",
        body = "Where are your games? Add each folder they are in: on this device, on a memory card, " +
            "or on a USB drive. droidtop reads only the folders you choose, and you can change them " +
            "later in Settings, under Game folders.",
        progress = progress,
        onBack = onBack,
        primary = StepAction(onboardingForwardLabel(reEntry = isReEntry, answered = roots.isNotEmpty()), onClick = onContinue),
        initialFocus = addFolderFocus,
    ) {
        if (roots.isNotEmpty()) {
            StepSectionLabel("Your game folders")
            roots.toList().sorted().forEach { path ->
                val report = reports[path]
                SelectableRow(
                    title = path,
                    supporting = report?.let { GamesRootReport.describe(it) }
                        ?: GamesRootReport.describe(scanProgress[path]),
                    trailing = {
                        PadButton("Remove", { onRemoveRoot(path) }, color = MenuTokens.Danger)
                    },
                )
                // ES-DE's three concrete repairs when a folder yields
                // nothing, rather than an empty list and no route out
                // (SPEC 7b, "No games yet"). Choice one is "add a
                // different folder", already on this page.
                if (report != null && report.exists && report.empty) {
                    StepNote(
                        "No games found here. Add a different folder, or create the standard folder " +
                            "layout inside this one and put your games there.",
                    )
                    PadButton("Create the standard folder layout here", { onGenerateStructure(path) })
                }
            }
        }

        structureReport?.let { StepNote(it, accent = true) }

        if (unresolvedFolderWarning) {
            StepNote("droidtop can't read that folder directly. It may be stored in the cloud. Type its path instead.")
        }

        // Places the picker cannot offer, found on this device: a memory
        // card or USB drive under /storage, an emulator's shared folder
        // under /mnt/windows. A newcomer had to already know the share's
        // path to type it (dq-coordinator-24, finding 12).
        val suggestions = found.filter { it !in roots }
        if (suggestions.isNotEmpty()) {
            StepSectionLabel("Found on this device")
            suggestions.forEach { path ->
                SelectableRow(
                    title = path,
                    supporting = "Add it to look for games there.",
                    trailing = { PadButton("Add", { onAddSuggested(path) }) },
                )
            }
        }

        StepSectionLabel("Add a folder")
        PadButton(
            "Add a folder",
            {
                if (pathFieldFocused) focusManager.clearFocus()
                onAddFolder()
            },
            filled = true,
            modifier = Modifier.focusRequester(addFolderFocus),
        )
        // The picker can only offer what Android calls a storage volume,
        // and real libraries live outside that set: an emulator's host
        // share (BlueStacks mounts one at /mnt/windows/BstSharedFolder), a
        // mount a rooted device adds itself, a USB drive under /mnt.
        if (!typingPath) {
            PadButton("Can't find it? Type its path", { typingPath = true })
        } else {
            StepNote("Type the folder's full path, for example an emulator's shared folder or a USB drive.")
            OutlinedTextField(
                value = pathEntry,
                onValueChange = onPathEntryChange,
                singleLine = true,
                label = { Text("Folder path") },
                placeholder = { Text("A full path, starting with /") },
                isError = pathError != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = Measure.bodyMaxWidth)
                    .onFocusChanged { pathFieldFocused = it.isFocused },
            )
            if (pathError != null) {
                Text(pathError, color = MenuTokens.Danger, style = TypeRole.supporting)
            }
            if (pathEntry.isNotBlank()) PadButton("Add this path", onAddPath)
        }
    }
}

/**
 * Readable folders the system picker does not offer, found by listing the
 * two places Android and the emulators mount them: removable volumes under
 * /storage (not the emulated internal storage, which the picker reaches),
 * and host shares under /mnt/windows. What is listed is what is there;
 * nothing is named that this device does not have.
 */
internal fun suggestedGameFolders(): List<String> {
    fun readableDirs(parent: String, skip: Set<String> = emptySet()): List<File> =
        File(parent).listFiles()
            ?.filter { it.isDirectory && it.name !in skip && it.listFiles() != null }
            .orEmpty()
    return (readableDirs("/storage", skip = setOf("emulated", "self")) + readableDirs("/mnt/windows"))
        .map { it.absolutePath }
        .sorted()
}

/**
 * CONTROLLER. One question, with the common answer preselected: which
 * face button confirms. Android reports a pad's buttons by POSITION, so
 * KEYCODE_BUTTON_A is the bottom face button whatever is printed on it,
 * and on a Nintendo-style pad the button that confirms is the one
 * labelled B; no detection can answer this, so it is asked. The step also
 * names the attached pad (the shell's own detector,
 * [ControllerPrefs.attachedControllers], which the Quick Menu's status
 * header asks too) and lets one press confirm that the pad is read.
 *
 * Next commits whichever answer is marked, the default included, so the
 * question counts as asked and Settings does not raise it again.
 */
@Composable
private fun ControllerStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val controllers = remember { ControllerPrefs.attachedControllers() }
    var swapped by remember { mutableStateOf(ControllerPrefs.swapConfirmCancel(context)) }
    var pressed by remember { mutableStateOf<String?>(null) }
    // Focus does NOT start in the test box. It used to, and inside the box
    // every button is a test press, B included: opened from Settings, the
    // screen read B as "the right face button" and did not go back, which is
    // what B means on every other screen (UI pass 2026-09-24, screenshots
    // 41-42). The box tests buttons only once a person moves into it, and
    // says so while it has focus; everywhere else on the step B is back.
    var testing by remember { mutableStateOf(false) }

    val names = controllers.joinToString { it.name }
    OnboardingScaffold(
        title = "Your controller",
        body = when {
            controllers.isEmpty() -> "No controller is connected right now. You can carry on with touch and set " +
                "one up later in Settings, under Input."
            else -> "droidtop sees $names. Which of its buttons confirms a choice? You can change this later in Settings."
        },
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (isReEntry) "Done" else "Next") {
            ControllerPrefs.setSwapConfirmCancel(context, swapped)
            onContinue()
        },
        focusContentFirst = true,
    ) {
        SelectableRow(
            title = "The bottom button",
            supporting = "A confirms and B goes back. Xbox-style pads and most Android controllers.",
            selected = !swapped,
            onClick = { swapped = false },
        )
        SelectableRow(
            title = "The right button",
            supporting = "B confirms and A goes back. Nintendo-style pads, where the bottom button is labelled B.",
            selected = swapped,
            onClick = { swapped = true },
        )

        StepSectionLabel("Test your buttons")
        // A real key event, caught where it lands: the box takes focus and
        // reports the button by position. Nothing is remapped here; this
        // only answers "is droidtop seeing your pad at all".
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = currentShellWindow().minTouchTarget + Space.Lg)
                .background(MenuTokens.Surface, MenuTokens.RowShape)
                .onFocusChanged { testing = it.isFocused }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                    val name = GamepadKeyMap.positionName(event.key) ?: return@onKeyEvent false
                    pressed = name
                    true
                }
                .padding(Space.Lg),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                when {
                    !testing && pressed == null -> "Move here with the D-pad, then press any button to see droidtop read it."
                    !testing -> "droidtop read $pressed. Move here again to test another."
                    pressed == null -> "Press any button, B too, and droidtop names it. Use the D-pad to move on."
                    else -> "droidtop read $pressed. Use the D-pad to move on."
                },
                color = if (pressed != null) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
                style = TypeRole.supporting,
            )
        }
    }
}

/**
 * droidtop's recommended Gaming default, Art Book Next (docs/SPEC.md 7f
 * "Default theme: Art Book Next, downloaded during setup"; CC-BY-NC-SA,
 * github.com/anthonycaccese/art-book-next-es-de), downloaded the moment
 * the Appearance step is shown, through the same real [ThemeDownloader]
 * "Browse themes" uses, not a second download mechanism. Runs on its
 * own process-lifetime scope so leaving the step (Next, Back), and
 * finishing onboarding itself, never cancels it mid-clone (rig,
 * p1-rig-onboarding-artbooknext-never-downloads).
 *
 * A successful download writes [LibraryThemePrefs] directly, the exact
 * call a row's own tap makes, but ONLY when nothing has been chosen yet
 * AND [disownIfIncomplete] was not called, so an explicit pick before or
 * after the download finishes always wins, and a download that finishes
 * after the person has already moved on to Gaming mode never swaps the
 * active theme out from under them. The clone itself still finishes and
 * shows up as installed in Browse themes either way.
 */
private object OnboardingThemeDownload {
    const val RECOMMENDED_THEME_DIR = "art-book-next-es-de"

    enum class Status { NOT_STARTED, DOWNLOADING, SUCCEEDED, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(Status.NOT_STARTED)
    val status: StateFlow<Status> = mutableStatus
    private var job: Job? = null

    /** Idempotent: a second call from a re-entered or recomposed step is a no-op. */
    fun ensureStarted(context: Context) {
        if (job != null) return
        val appContext = context.applicationContext
        job = scope.launch {
            mutableStatus.value = Status.DOWNLOADING
            val userThemesDir = ThemeAssets.userThemesDir(appContext)
            // Same stall watchdog Browse themes uses: without it, a truly
            // dead connection left this stuck on DOWNLOADING forever.
            ThemeDownloader.withStallWatchdog { progress, isCancelled ->
                ThemeDownloader.syncThemesList(userThemesDir, progress, isCancelled)
            }
            val entry = ThemeDownloader.parseThemesList(userThemesDir)
                .firstOrNull { it.reponame == RECOMMENDED_THEME_DIR }
            val result = entry?.let {
                ThemeDownloader.withStallWatchdog { progress, isCancelled ->
                    ThemeDownloader.downloadOrUpdateTheme(userThemesDir, it, progress = progress, isCancelled = isCancelled)
                }
            }
            val ok = result != null && result.status in setOf(
                ThemeDownloader.ThemeSyncStatus.CLONED,
                ThemeDownloader.ThemeSyncStatus.UPDATED,
                ThemeDownloader.ThemeSyncStatus.UP_TO_DATE,
            )
            if (ok) {
                // Drops ThemeAssets' own discovery/parse caches, the same
                // signal a theme downloaded from Browse themes fires.
                LibraryThemePrefs.notifyThemesChanged()
                if (activateOnSuccess && LibraryThemePrefs.get(appContext) == null) {
                    LibraryThemePrefs.set(appContext, RECOMMENDED_THEME_DIR)
                }
                mutableStatus.value = Status.SUCCEEDED
            } else {
                mutableStatus.value = Status.FAILED
            }
        }
    }

    // Guards ONLY the auto-activation above, not the download itself.
    @Volatile
    private var activateOnSuccess = true

    /**
     * Called once, from `finishOnboarding`, when a download is still
     * running as setup finishes: the person has already moved on to
     * whatever theme resolved for Gaming mode, so this download finishing
     * later must not silently swap the active theme out from under them.
     * The clone keeps running and shows up as installed in Browse themes,
     * or as a real FAILED status there.
     */
    fun disownIfIncomplete() {
        if (mutableStatus.value != Status.DOWNLOADING) return
        activateOnSuccess = false
    }
}

/**
 * APPEARANCE. Every theme droidtop has, each with a REAL render of itself
 * (docs/SPEC.md 7b): the theme's own system view, parsed by the one theme
 * parser and drawn by the one renderer the Gaming shell uses
 * ([ThemeSystemPreview]). Not a screenshot, not a swatch. On a tall screen
 * the theme that ships portrait layouts is preselected and every row says
 * which kind of layouts its theme ships. The choice is written down as
 * soon as it is made, so rotating the device later never moves the theme
 * under the person.
 */
@Composable
private fun AppearanceStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val portraitScreen = remember { ThemeAssets.isPortraitScreen(context) }
    // The theme downloader, the same screen Settings' "Browse themes"
    // opens (docs/SPEC.md 7b, "Themes are chosen during setup"), drawn in
    // place of the step; a theme it downloads is in the list on return.
    var browsing by remember { mutableStateOf(false) }
    var catalogVersion by remember { mutableStateOf(0) }
    // Listing the themes and reading each one's capabilities.xml is asset
    // and file I/O: read off the main thread, and nothing is listed until
    // it is known (an empty list would read as "no themes installed").
    val catalog by produceState<AppearanceCatalog?>(initialValue = null, catalogVersion) {
        value = withContext(Dispatchers.IO) { AppearanceCatalog.read(context) }
    }
    if (browsing) {
        val closeBrowser: () -> Unit = {
            browsing = false
            catalogVersion++
        }
        // The same Back as every other onboarding page, top left; the
        // browser is shared with Settings and has none of its own.
        Column(Modifier.fillMaxSize().background(MenuTokens.Ground).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = currentShellWindow().edgePadding, vertical = Space.Lg)) {
                PadButton("Back", closeBrowser)
            }
            Box(Modifier.weight(1f)) { ThemeBrowserScreen(onDismiss = closeBrowser) }
        }
        return
    }
    // droidtop's recommended default (SPEC.md 7f) downloads itself the
    // moment this step is shown; see [OnboardingThemeDownload].
    LaunchedEffect(Unit) { OnboardingThemeDownload.ensureStarted(context) }
    val downloadStatus by OnboardingThemeDownload.status.collectAsState()
    val stored = remember { LibraryThemePrefs.get(context) }
    var picked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(downloadStatus) {
        if (downloadStatus == OnboardingThemeDownload.Status.SUCCEEDED) {
            // The freshly cloned theme is only in [discoverThemes]'s own
            // cache after this; rereads the catalog to pick it up.
            catalogVersion++
            // The object itself already wrote LibraryThemePrefs when
            // nothing had been chosen yet; mirrored here so the row
            // reflects it at once.
            if (picked == null && stored == null) picked = OnboardingThemeDownload.RECOMMENDED_THEME_DIR
        }
    }
    val chosen = picked ?: stored ?: catalog?.defaultTheme

    OnboardingScaffold(
        title = "Choose a look",
        body = if (portraitScreen) {
            "A theme sets how your games are shown. This screen is taller than it is wide, so themes made " +
                "for a tall screen say so; the others are stretched to fit. You can change this later in Settings."
        } else {
            "A theme sets how your games are shown. Pick one, or download more. You can change this later " +
                "in Settings."
        },
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (isReEntry) "Done" else "Next", onClick = onContinue),
        secondary = StepAction("Download more themes") { browsing = true },
        focusContentFirst = true,
    ) {
        val themes = catalog?.themes
        if (themes != null && themes.isEmpty()) {
            StepNote("No themes are installed. Your games will be shown in a plain layout.")
        }
        // Shown only until Art Book Next has its own row below (either it
        // downloaded, in which case the catalog now lists it, or it never
        // will this run); not a progress bar to wait on, since Next works
        // regardless.
        val recommendedListed = themes?.any { it.name == OnboardingThemeDownload.RECOMMENDED_THEME_DIR } == true
        if (!recommendedListed && downloadStatus == OnboardingThemeDownload.Status.FAILED) {
            StepNote(
                "Couldn't download Art Book Next, the recommended theme. Check your connection; " +
                    "Download more themes offers it again later.",
            )
        }
        themes?.forEach { theme ->
            val hasVertical = theme.hasVertical
            SelectableRow(
                title = theme.displayName,
                supporting = (when {
                    hasVertical && portraitScreen -> "Made for a tall screen too. Recommended here."
                    hasVertical -> "Made for wide and tall screens."
                    portraitScreen -> "Made for wide screens only. It will be stretched on this screen."
                    else -> "Made for wide screens."
                }) + (if (theme.bundled) " Included with droidtop." else " Downloaded."),
                selected = chosen == theme.name,
                leading = {
                    ThemeSystemPreview(
                        themeId = theme.name,
                        // The preview takes the screen's own shape from
                        // the display; this is only how big it is.
                        longEdge = Measure.themePreviewLongEdge,
                        modifier = Modifier.clip(MenuTokens.RowShape),
                    )
                },
                onClick = {
                    picked = theme.name
                    // Written down the moment it is chosen: a resolved
                    // default that stays unwritten moves under the person
                    // the first time they rotate the device.
                    LibraryThemePrefs.set(context, theme.name)
                },
            )
        }
    }
}

/** What the Appearance step lists, read once off the main thread. */
private class AppearanceCatalog(val themes: List<Theme>, val defaultTheme: String?) {
    class Theme(val name: String, val displayName: String, val hasVertical: Boolean, val bundled: Boolean)

    companion object {
        fun read(context: android.content.Context): AppearanceCatalog {
            val discovered = ThemeAssets.discoverThemes(context)
            return AppearanceCatalog(
                themes = discovered.map { theme ->
                    Theme(
                        name = theme.name,
                        displayName = ThemeAssets.displayName(context, theme),
                        hasVertical = ThemeAssets.hasVerticalVariant(context, theme),
                        bundled = theme.bundledAssetFolder != null,
                    )
                },
                defaultTheme = ThemeAssets.defaultThemeFor(context, discovered)?.name,
            )
        }
    }
}

/**
 * KEYBOARD. Desktop's step: droidtop runs fine without its own keyboard;
 * what it cannot do without one is drive a terminal or a Windows
 * application, because no stock phone keyboard has Ctrl, Alt, Esc, Tab,
 * arrows or a function row (docs/SPEC.md section 6a).
 *
 * droidtop cannot set the system input method itself (that needs
 * WRITE_SECURE_SETTINGS, which a normal app is never granted), so this
 * states the reason and opens Android's own screens. Declining is a real
 * answer, not a nag to be repeated.
 */
@Composable
private fun KeyboardStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    onEnable: () -> Unit,
    onPick: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    // Re-read on every recomposition: the person leaves for Android's
    // settings and comes back, and the step has to reflect what they did.
    val enabled = dev.droidtop.library.settings.Keyboards.ownKeyboardEnabled(context)
    val active = dev.droidtop.library.settings.Keyboards.ownKeyboardActive(context)

    OnboardingScaffold(
        title = "Keyboard",
        body = dev.droidtop.library.settings.Keyboards.WHY,
        progress = progress,
        onBack = onBack,
        // A hand-off step, shaped like the storage one (docs/SPEC.md 7b):
        // the primary is the step's own work (it opens Android's screen
        // and comes back here) and the ONE forward action waits beside it
        // as the skip until the hand-off has actually taken.
        primary = when {
            active -> StepAction(if (isReEntry) "Done" else "Next", onClick = onContinue)
            !enabled -> StepAction("Turn it on in Android", onClick = onEnable)
            else -> StepAction("Switch to it", onClick = onPick)
        },
        secondary = if (active) null else StepAction(if (isReEntry) "Done" else "Skip this step", onClick = onContinue),
    ) {
        when {
            active -> StepNote("Hacker's Keyboard is your keyboard now.", accent = true)
            enabled -> StepNote("Hacker's Keyboard is turned on but not in use yet. Switch to it to use it.")
            else -> StepNote(
                "Android decides which keyboard is used, so this opens Android's own screen. " +
                    "If nothing opens on this device, skip this step.",
            )
        }
    }
}

/**
 * DONE. Onboarding ends with a summary and one action into the chosen
 * mode (SPEC 7b, "All done"). With Gaming set up and no folder added it
 * also offers "Add your games", back to the one step that does it, so a
 * person never lands on an empty library with nothing to press
 * (Droidtop/tracker#172).
 */
@Composable
private fun DoneStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    opensInto: Mode,
    defaultMode: Mode,
    homeChoice: HomeImplementation,
    homeHeld: Boolean,
    homeAlternativeLabel: String?,
    gamingConfigured: Boolean,
    desktopConfigured: Boolean,
    desktopOffered: Boolean,
    gamesFound: Int,
    gamesStillCounting: Boolean,
    gamesSoFar: Int,
    noFolders: Boolean,
    storageGranted: Boolean,
    controllerAnswered: Boolean,
    keyboardSkipped: Boolean,
    onAddGames: () -> Unit,
    onFinish: () -> Unit,
) {
    fun games(n: Int) = "$n " + if (n == 1) "game" else "games"
    val done = buildList {
        if (homeChoice != HomeImplementation.NONE && homeHeld) {
            add(
                when {
                    homeChoice == HomeImplementation.ALTERNATIVE -> "Home button: opens ${homeAlternativeLabel ?: "the launcher you picked"}."
                    defaultMode == Mode.LAUNCHER -> "Home button: opens droidtop's home screen."
                    else -> "Home button: opens ${opensInto.label}."
                },
            )
        }
        if (gamingConfigured) {
            // Three different facts, and the rig caught them collapsed
            // into one: a scan that is still walking a folder said
            // "no games found yet", which reads as a finished, empty
            // library (build 539). A count in flight says it is counting.
            val counted = gamesFound + gamesSoFar
            add(
                when {
                    gamesStillCounting && counted > 0 -> "Gaming: still counting your folders, ${games(counted)} so far."
                    gamesStillCounting -> "Gaming: still counting your folders."
                    gamesFound > 0 -> "Gaming: ${games(gamesFound)} found."
                    noFolders -> "Gaming: no game folders added yet."
                    else -> "Gaming: no games found in your folders yet."
                },
            )
        }
        if (desktopConfigured) add("Desktop: Linux system chosen. It downloads the first time Desktop starts.")
        add("droidtop opens into ${if (defaultMode == Mode.LAUNCHER) "its home screen" else defaultMode.label}.")
    }
    // Each line names the Settings section it lives in.
    val skipped = buildList {
        if (homeChoice != HomeImplementation.NONE && !homeHeld) {
            add("Home button (Home still opens another app): Global settings")
        }
        if (gamingConfigured && !storageGranted) add("File access for your game folders: Game folders")
        if (desktopOffered && !desktopConfigured) add("Desktop (no Linux system chosen yet): Desktop")
        if (!controllerAnswered) add("Controller: Input")
        if (keyboardSkipped) add("Keyboard: Input")
    }
    // What a newcomer needs a minute from now and would otherwise have to
    // find (dq-coordinator-24, finding 8): each is asked where it is used,
    // as the permissions rule says (SPEC 7b), so here it is only named.
    val later = buildList {
        if (gamingConfigured) {
            add("Pictures and descriptions for your games: Library, Scraper")
            add("Windows games: open one, and its page offers the one-time download Windows games need")
        }
    }

    OnboardingScaffold(
        title = "All done!",
        body = null,
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (defaultMode == Mode.LAUNCHER) "Open my home screen" else "Open ${defaultMode.label}", onClick = onFinish),
        secondary = if (gamingConfigured && noFolders) StepAction("Add your games", onClick = onAddGames) else null,
    ) {
        StepSectionLabel("What's set up")
        done.forEach { StepNote("• $it") }
        if (skipped.isNotEmpty()) {
            StepSectionLabel("Skipped")
            StepNote("Find these later in Settings.")
            skipped.forEach { StepNote("• $it") }
        }
        if (later.isNotEmpty()) {
            StepSectionLabel("Not set up")
            StepNote("Set these up when you want them.")
            later.forEach { StepNote("• $it") }
        }
        Spacer(modifier = Modifier.padding(top = Space.Sm))
        StepNote("All settings can be changed later.")
    }
}
