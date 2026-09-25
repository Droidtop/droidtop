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
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import dev.droidtop.app.ui.PadButton
import dev.droidtop.app.ui.SelectableRow
import dev.droidtop.library.GamesRootReport
import dev.droidtop.library.consoles.EsDeFolderStructure
import dev.droidtop.library.theme.ThemeAssets
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * droidtop's first-run flow — it onboards the DEVICE, not one mode
 * (docs/SPEC.md section 7b, which is this flow's specification).
 *
 * It asks, in order: how the Android home screen behaves, what else to
 * set up, the permissions and folders those choices need, the input and
 * appearance they will be used through, and finally which of the things
 * actually configured droidtop opens into. Configuring a mode and
 * choosing the default are separate questions, so a person who wants two
 * modes never has to finish in Settings what first run started.
 *
 * Structurally it is ONE scaffold ([OnboardingScaffold]) that every step
 * renders into — progress, a working Back, a capped measure and a
 * bottom-docked action area — and ONE choice component
 * ([SelectableRow]) for every question with mutually exclusive answers.
 * Before this it was eleven independent screens with no progress, no back
 * stack, vertically centred content, full-bleed body text and three
 * different visual weights for three equal answers.
 *
 * Gated by [dev.droidtop.shell.standard.OnboardingGate] from both
 * `:shell-default`'s `LauncherApplication.onCreate()` AND `:app`'s own
 * `MainActivity.onCreate()` — a person who never boots through Standard
 * still needs to see this once.
 *
 * [EXTRA_START_STEP] supports re-entry from Settings: each step is
 * independently re-runnable later, so when it is set onboarding runs that
 * one step and finishes instead of continuing through the rest.
 */
class OnboardingActivity : AppCompatActivity() {
    /**
     * The run's own answers, held where a configuration change cannot
     * reach them (see [OnboardingRun]). Rotating the device on step 7
     * used to restart onboarding at step 1 with the mode choices gone.
     */
    private val onboardingRun: OnboardingRun by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // The ground runs under the bars; the theme would paint them grey
        // over it (rig, BlueStacks, dq-onboard-02).
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val startStep = intent.getStringExtra(EXTRA_START_STEP)
            ?.let { name -> OnboardingStep.entries.firstOrNull { it.name == name } }
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
        // Seeded once per run, not once per Activity instance: both the
        // starting step and the storage answer the PLAN is built from
        // are facts about the run, and re-reading them after a rotation
        // is what made the plan differ by orientation.
        onboardingRun.start(
            startStep,
            hasStorageAccess(this),
            // A rerun starts from the modes as they are, so walking through
            // it again changes nothing that is not changed on the way.
            modesOnBefore = if (!firstRun) {
                dev.droidtop.library.settings.Mode.entries
                    .filterTo(mutableSetOf()) { dev.droidtop.library.settings.Modes.isEnabledInStorage(this, it) }
            } else {
                null
            },
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
 * ONE run of onboarding, and everything that run has answered.
 *
 * Held in a [androidx.lifecycle.ViewModel] rather than in the
 * composition, because a configuration change destroys the Activity and
 * everything remembered inside it. On the rig (build 546) rotating the
 * device on "Step 7 of 10 -- Appearance" came back at "Step 1 of 7": the
 * walk position and the history were gone, the mode choices were gone
 * with them, and the COUNT changed too, because [plannedSteps] is built
 * from those answers and from whether the storage permission was held
 * when the run started -- which by then it was, so the permission step
 * dropped out of the plan as well.
 *
 * So the plan is a property of the RUN, not of the Activity instance
 * drawing it: [start] seeds the run once and every later call is a
 * no-op, and everything the plan and the walk are built from lives here.
 * The things that are NOT answers -- what a games root turned out to
 * hold, what the theme list is -- are derived from the device and stay
 * out of it, except the two caches that would otherwise re-walk the
 * whole library on every rotation.
 */
internal class OnboardingRun : androidx.lifecycle.ViewModel() {
    private var started = false

    /** The step this run opened on; null for a full first-run walk. */
    var startStep: OnboardingStep? = null
        private set

    /**
     * Whether "All files access" was already held when the RUN started.
     *
     * The plan asks this once, for the reason [plannedSteps] gives: a
     * plan that changes under the user's feet cannot say where to go
     * next. Asking it once per Activity instead of once per run is what
     * made "Step 7 of 10" become "Step 1 of 7".
     */
    var storageGrantedAtEntry: Boolean = false
        private set

    /**
     * [modesOnBefore] is null on a first run, where nothing is ticked
     * until the person ticks it; on a rerun it is the modes already on,
     * which start ticked.
     */
    fun start(
        startStep: OnboardingStep?,
        storageGranted: Boolean,
        modesOnBefore: Set<dev.droidtop.library.settings.Mode>? = null,
        saved: org.json.JSONObject? = null,
        persistent: Boolean = false,
    ) {
        if (started) return
        started = true
        this.startStep = startStep
        this.persistent = persistent
        this.storageGrantedAtEntry = storageGranted
        storageAccessGranted.value = storageGranted
        step.value = startStep ?: OnboardingStep.WELCOME
        if (modesOnBefore != null) {
            configureGaming.value = dev.droidtop.library.settings.Mode.GAMING in modesOnBefore
            configureDesktop.value = dev.droidtop.library.settings.Mode.DESKTOP in modesOnBefore
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
     * these, so a resumed run presents the same steps it was presenting,
     * storage question included.
     */
    fun toJson(): String = org.json.JSONObject().apply {
        put("step", step.value.name)
        put("history", org.json.JSONArray(history.map { it.name }))
        homeChoice.value?.let { put("home", it.name) }
        put("desktop", configureDesktop.value)
        put("gaming", configureGaming.value)
        put("desktopImage", desktopImageChosen.value)
        put("desktopCapable", desktopCapable.value)
        chosenMode.value?.let { put("mode", it.id) }
        put("storageAtEntry", storageGrantedAtEntry)
    }.toString()

    private fun restore(saved: org.json.JSONObject) {
        fun stepOf(name: String?) = OnboardingStep.entries.firstOrNull { it.name == name }
        step.value = stepOf(saved.optString("step")) ?: return
        history.clear()
        saved.optJSONArray("history")?.let { names ->
            for (i in 0 until names.length()) stepOf(names.optString(i))?.let(history::add)
        }
        homeChoice.value = HomeRolePrefs.HomeImplementation.entries
            .firstOrNull { it.name == saved.optString("home") }
        configureDesktop.value = saved.optBoolean("desktop")
        configureGaming.value = saved.optBoolean("gaming")
        desktopImageChosen.value = saved.optBoolean("desktopImage")
        desktopCapable.value = saved.optBoolean("desktopCapable")
        chosenMode.value = dev.droidtop.library.settings.Mode.byId(saved.optString("mode").ifEmpty { null })
        storageGrantedAtEntry = saved.optBoolean("storageAtEntry", storageGrantedAtEntry)
    }

    // Each answer is a MutableState the screen delegates to (`var step by
    // run.step`), so the step functions below read exactly as they did
    // when these were `remember`ed -- the only thing that changed is
    // WHERE they live.
    val step = mutableStateOf(OnboardingStep.WELCOME)

    /**
     * The path actually taken, which is what Back walks: a person who
     * answered "a launcher I already have" goes back to that list, not to
     * a step the plan says comes before this one on paper.
     */
    val history = mutableStateListOf<OnboardingStep>()

    val homeChoice = mutableStateOf<HomeRolePrefs.HomeImplementation?>(null)
    val configureDesktop = mutableStateOf(false)
    val configureGaming = mutableStateOf(false)
    val desktopImageChosen = mutableStateOf(false)
    val desktopCapable = mutableStateOf(false)
    val unresolvedFolderWarning = mutableStateOf(false)
    val pathEntry = mutableStateOf("")
    val pathError = mutableStateOf<String?>(null)
    val storageAccessGranted = mutableStateOf(false)
    val storageDenied = mutableStateOf(false)
    val storagePermanentlyDenied = mutableStateOf(false)
    val chosenMode = mutableStateOf<dev.droidtop.library.settings.Mode?>(null)
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

internal enum class OnboardingStep {
    WELCOME, HOME_CHOICE, STANDARD_SETUP, ALTERNATIVE_SETUP,
    CONFIGURE_MORE, DESKTOP_SETUP, STORAGE_PERMISSION, GAMES_FOLDERS,
    CONTROLLER, APPEARANCE, KEYBOARD, DEFAULT_MODE_CHOICE, WHAT_NEXT,
}

/**
 * The steps this run will actually present, given the answers so far.
 * Progress is stated against THIS list rather than against the enum, so
 * "step 4 of 7" means what it says: a person who is not setting up Gaming
 * is never told there are four steps left that they will not see.
 *
 * It is also the pipeline itself — [OnboardingScreen] advances to the
 * next entry after the current one instead of carrying a second, separate
 * `when` that could disagree with the count.
 */
internal fun plannedSteps(
    home: HomeRolePrefs.HomeImplementation?,
    configureDesktop: Boolean,
    configureGaming: Boolean,
    storageGranted: Boolean,
): List<OnboardingStep> = buildList {
    add(OnboardingStep.WELCOME)
    add(OnboardingStep.HOME_CHOICE)
    when (home) {
        HomeRolePrefs.HomeImplementation.STANDARD -> add(OnboardingStep.STANDARD_SETUP)
        HomeRolePrefs.HomeImplementation.ALTERNATIVE -> add(OnboardingStep.ALTERNATIVE_SETUP)
        else -> Unit
    }
    add(OnboardingStep.CONFIGURE_MORE)
    if (configureDesktop) add(OnboardingStep.DESKTOP_SETUP)
    if (configureGaming) {
        // Skipped outright when the permission was already held WHEN
        // ONBOARDING OPENED -- the step used to re-ask for something
        // droidtop already had. Deliberately not "is held now": granting
        // it on the step itself would otherwise take the step out of the
        // plan while the user is standing on it, and the run has nowhere
        // to go from a step that is no longer in its own pipeline. On the
        // Android 9 rig (build 531, fresh data) that ended the whole flow
        // at "Allow": progress jumped from "Step 4 of 8" to "Step 7 of 7"
        // and the next press landed on "You're set up" with the games
        // folders, theme and default-mode steps never shown, so a fresh
        // install finished with no games root at all.
        if (!storageGranted) add(OnboardingStep.STORAGE_PERMISSION)
        add(OnboardingStep.GAMES_FOLDERS)
    }
    // The input and the appearance they will be used through (docs/SPEC.md
    // 7b). The pad is asked about whatever modes are being set up -- it is
    // how the shell itself is driven -- while the theme is Gaming's, so a
    // person who is not setting Gaming up is not asked to pick one.
    add(OnboardingStep.CONTROLLER)
    if (configureGaming) add(OnboardingStep.APPEARANCE)
    // The keyboard is Desktop's: its reason is terminals and Windows
    // programs. Asked of a Gaming-only run, it was a question about
    // software the person had just said they did not want (rig,
    // dq-coordinator-24, finding 10).
    if (configureDesktop) add(OnboardingStep.KEYBOARD)
    add(OnboardingStep.DEFAULT_MODE_CHOICE)
    add(OnboardingStep.WHAT_NEXT)
}

/**
 * The parts onboarding is made of, which is what its progress counts
 * (docs/SPEC.md 7b, "Progress counts parts that never change"). Six, on
 * every run, whatever is answered: the steps inside a part come and go
 * with the answers (a launcher to pick, a storage prompt, Desktop's
 * image), the parts do not. A part a run has nothing to ask in is passed
 * over, and still counted.
 *
 * Counting STEPS against the plan was honest and still read as broken:
 * the total moved under the person as they answered, "Step 1 of 7", then
 * "2 of 8", then "4 of 10", and back to "of 7" after a restart (rig,
 * dq-coordinator-24, finding 9).
 */
internal enum class OnboardingPart(val label: String) {
    WELCOME("Welcome"),
    HOME("Home screen"),
    MODES("What to set up"),
    GAMES("Your games"),
    CONTROLS("Controls and look"),
    FINISH("Finish"),
}

internal val OnboardingStep.part: OnboardingPart
    get() = when (this) {
        OnboardingStep.WELCOME -> OnboardingPart.WELCOME
        OnboardingStep.HOME_CHOICE, OnboardingStep.STANDARD_SETUP, OnboardingStep.ALTERNATIVE_SETUP -> OnboardingPart.HOME
        OnboardingStep.CONFIGURE_MORE, OnboardingStep.DESKTOP_SETUP -> OnboardingPart.MODES
        OnboardingStep.STORAGE_PERMISSION, OnboardingStep.GAMES_FOLDERS -> OnboardingPart.GAMES
        OnboardingStep.CONTROLLER, OnboardingStep.APPEARANCE, OnboardingStep.KEYBOARD -> OnboardingPart.CONTROLS
        OnboardingStep.DEFAULT_MODE_CHOICE, OnboardingStep.WHAT_NEXT -> OnboardingPart.FINISH
    }

/** What the scaffold's progress line and bar show. */
internal data class StepProgress(val part: OnboardingPart) {
    val number: Int get() = part.ordinal + 1
    val count: Int get() = OnboardingPart.entries.size
}

/**
 * Which of the two app-hosted modes are ON once onboarding finishes
 * (docs/SPEC.md 7b, "Anything else to set up"). The tick IS the switch: a
 * mode left unticked is switched off, and a switched-off mode runs no
 * code (SPEC 2c, Rule 1). Before this, the answer only decided which
 * steps followed, and both modes stayed on whatever was ticked -- a
 * person who said "no Gaming" still had Gaming's notification listener,
 * its platforms warm-up and the Windows backbone running.
 *
 * Desktop is also left off when its capability check failed on this
 * device. The one exception is the mode onboarding opens into: with
 * nothing else set up that is Gaming, which explains what to add, and a
 * mode cannot be opened while it is off.
 */
internal fun appModesOnAfterOnboarding(
    configureGaming: Boolean,
    configureDesktop: Boolean,
    desktopCapable: Boolean,
    opensInto: dev.droidtop.library.settings.Mode,
): Set<dev.droidtop.library.settings.Mode> = buildSet {
    if (configureGaming) add(dev.droidtop.library.settings.Mode.GAMING)
    // Ticked, but the capability check said it cannot run here: switching
    // it on would only offer a mode that opens onto its own failure.
    if (configureDesktop && desktopCapable) add(dev.droidtop.library.settings.Mode.DESKTOP)
    if (opensInto != dev.droidtop.library.settings.Mode.LAUNCHER) add(opensInto)
}

/**
 * [GamesRootPrefs.resolveStoragePath] can compute a perfectly correct real
 * path and it still won't matter -- Android 11+ blocks plain `java.io.File`
 * access outside the app's own sandbox unless the app holds "All files
 * access" (MANAGE_EXTERNAL_STORAGE), which a SAF folder grant alone does
 * NOT provide for File-based I/O (only for the ContentResolver/DocumentFile
 * APIs, which [dev.droidtop.library.GameEngineDetector] doesn't use). Real
 * risk this closes: a games folder that "resolves" successfully here but
 * then silently shows zero games afterward because reads are being denied
 * at the OS level, not because nothing's there -- confirmed as a real,
 * not hypothetical, gap after a live device test showed SD-card-stored
 * games failing to resolve. droidtop isn't Play-Store-distributed, so
 * requesting this permission directly (rather than working around it) is
 * legitimate here the same way it's standard for file-manager and
 * ROM-manager apps generally.
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
 * runtime permission is a real supported path rather than a fallback for
 * an edge case: on API 26-29 it grants exactly the plain java.io.File
 * reads across shared storage that GameEngineDetector needs.
 */
private const val LEGACY_STORAGE_PERMISSION = android.Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun OnboardingScreen(run: OnboardingRun, isReEntry: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Every answer this run has given lives in `run` (see [OnboardingRun]),
    // not in this composition: the Activity is destroyed and rebuilt on
    // every rotation, and a walk remembered inside it does not survive
    // that. These are aliases onto the same state, so the steps below read
    // exactly as they did.
    var step by run.step
    val history = run.history
    var homeChoice by run.homeChoice
    var configureDesktop by run.configureDesktop
    var configureGaming by run.configureGaming
    var desktopImageChosen by run.desktopImageChosen
    var desktopCapable by run.desktopCapable
    var unresolvedFolderWarning by run.unresolvedFolderWarning
    var pathEntry by run.pathEntry
    var pathError by run.pathError
    var storageAccessGranted by run.storageAccessGranted
    var storageDenied by run.storageDenied
    var storagePermanentlyDenied by run.storagePermanentlyDenied
    var chosenMode by run.chosenMode
    var confirmLeaving by run.confirmLeaving
    var structureReport by run.structureReport
    var rootsVersion by run.rootsVersion

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
        // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION doesn't reliably
        // report grant/deny via its own result code -- re-checking the real
        // system state directly is the only trustworthy signal.
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


    // ONE plan per run. Its storage question is answered once, when the
    // run starts (see OnboardingRun.storageGrantedAtEntry), for the
    // reason plannedSteps gives: a plan that changes under the user's
    // feet cannot say where to go next -- and a plan rebuilt from a
    // fresh reading after every rotation is exactly that.
    val plan = plannedSteps(homeChoice, configureDesktop, configureGaming, run.storageGrantedAtEntry)

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

    /** Advance along the plan -- or finish outright on a re-entered single step. */
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
            here < 0 -> plan.firstOrNull { it != current && it !in history } ?: OnboardingStep.WHAT_NEXT
            else -> OnboardingStep.WHAT_NEXT
        }
        goTo(next)
    }

    fun finishOnboarding(opensInto: dev.droidtop.library.settings.Mode) {
        // Written only here, at the end: a person who leaves part-way is
        // told nothing they set is lost, and nothing about the modes is
        // changed either.
        val on = appModesOnAfterOnboarding(configureGaming, configureDesktop, desktopCapable, opensInto)
        listOf(dev.droidtop.library.settings.Mode.GAMING, dev.droidtop.library.settings.Mode.DESKTOP).forEach { mode ->
            if (dev.droidtop.library.settings.Modes.isEnabledInStorage(context, mode) != (mode in on)) {
                dev.droidtop.library.settings.Modes.setEnabled(context, mode, mode in on)
            }
        }
        GamesRootPrefs.markOnboardingComplete(context)
        // The modes are on from here (Modes.reload reads the finished
        // setup), so ModeStartup starts what they own now, not at the
        // next process start.
        dev.droidtop.library.settings.Modes.reload(context)
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
                    "Your answers are kept. Open droidtop again to carry on from this step; " +
                        "until then the Home button shows your home screen.",
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

    val progress = if (isReEntry) null else StepProgress(step.part)
    val back: (() -> Unit)? = if (canGoBack) ({ step = history.removeAt(history.lastIndex) }) else null

    // The outermost node owns the pad (ownPadButtons): B is Back here as
    // everywhere else in droidtop, and a pad button nothing below wanted
    // is not handed to Android to turn into a key the step never asked for.
    Box(Modifier.fillMaxSize().ownPadButtons(::handleBack)) {
    when (step) {
        OnboardingStep.WELCOME -> WelcomeStep(progress, back, onContinue = { advanceFrom(OnboardingStep.WELCOME) })

        OnboardingStep.HOME_CHOICE -> HomeChoiceStep(
            progress, back,
            selected = homeChoice,
            onSelect = { homeChoice = it },
            onContinue = {
                val chosen = homeChoice ?: HomeRolePrefs.HomeImplementation.NONE
                when (chosen) {
                    HomeRolePrefs.HomeImplementation.STANDARD -> goTo(OnboardingStep.STANDARD_SETUP)
                    HomeRolePrefs.HomeImplementation.ALTERNATIVE -> goTo(OnboardingStep.ALTERNATIVE_SETUP)
                    HomeRolePrefs.HomeImplementation.NONE -> {
                        HomeRolePrefs.setActiveHomeImplementation(context, HomeRolePrefs.HomeImplementation.NONE)
                        advanceFrom(OnboardingStep.HOME_CHOICE)
                    }
                }
            },
        )

        OnboardingStep.STANDARD_SETUP -> StandardSetupStep(
            progress, back,
            isHome = droidtopIsHome,
            onMakeHome = {
                HomeRolePrefs.setActiveHomeImplementation(context, HomeRolePrefs.HomeImplementation.STANDARD)
                requestHome.launch(HomeRolePrefs.homeRequestIntent(context))
            },
            onContinue = {
                HomeRolePrefs.setActiveHomeImplementation(context, HomeRolePrefs.HomeImplementation.STANDARD)
                advanceFrom(OnboardingStep.STANDARD_SETUP)
            },
        )

        OnboardingStep.ALTERNATIVE_SETUP -> AlternativeSetupStep(
            progress, back,
            onPicked = { component ->
                HomeRolePrefs.setAlternativeTarget(context, component)
                HomeRolePrefs.setActiveHomeImplementation(context, HomeRolePrefs.HomeImplementation.ALTERNATIVE)
                // droidtop forwards Home only while it IS Home: ask Android,
                // which shows its own confirmation over the next step.
                if (!HomeRolePrefs.isDroidtopHome(context)) {
                    requestHome.launch(HomeRolePrefs.homeRequestIntent(context))
                }
                advanceFrom(OnboardingStep.ALTERNATIVE_SETUP)
            },
        )

        OnboardingStep.CONFIGURE_MORE -> ConfigureMoreStep(
            progress, back,
            desktopChecked = configureDesktop,
            gamingChecked = configureGaming,
            onDesktopChanged = { configureDesktop = it },
            onGamingChanged = { configureGaming = it },
            onContinue = { advanceFrom(OnboardingStep.CONFIGURE_MORE) },
        )

        OnboardingStep.DESKTOP_SETUP -> DesktopSetupStep(
            progress, back,
            onCapabilityKnown = { desktopCapable = it },
            onContinue = { chose ->
                desktopImageChosen = chose
                advanceFrom(OnboardingStep.DESKTOP_SETUP)
            },
        )

        OnboardingStep.STORAGE_PERMISSION -> StoragePermissionStep(
            progress, back,
            legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
            denied = storageDenied,
            permanentlyDenied = storagePermanentlyDenied,
            granted = storageAccessGranted,
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
            onContinue = { advanceFrom(OnboardingStep.STORAGE_PERMISSION) },
        )

        OnboardingStep.GAMES_FOLDERS -> GamesFoldersStep(
            progress, back,
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
            onContinue = { advanceFrom(OnboardingStep.GAMES_FOLDERS) },
        )

        OnboardingStep.CONTROLLER -> ControllerStep(
            progress, back,
            isReEntry = isReEntry,
            onContinue = { advanceFrom(OnboardingStep.CONTROLLER) },
        )

        OnboardingStep.APPEARANCE -> AppearanceStep(
            progress, back,
            onContinue = { advanceFrom(OnboardingStep.APPEARANCE) },
        )

        OnboardingStep.KEYBOARD -> KeyboardStep(
            progress, back,
            onEnable = { dev.droidtop.library.settings.Keyboards.openSystemSettings(context) },
            onPick = { dev.droidtop.library.settings.Keyboards.showPicker(context) },
            onContinue = { advanceFrom(OnboardingStep.KEYBOARD) },
        )

        OnboardingStep.DEFAULT_MODE_CHOICE -> DefaultModeChoiceStep(
            progress, back,
            homeImplementation = homeChoice ?: HomeRolePrefs.activeHomeImplementation(context),
            desktopUsable = configureDesktop && desktopImageChosen && desktopCapable,
            gamingUsable = configureGaming,
            selected = chosenMode,
            onSelect = { chosenMode = it },
            onContinue = {
                val mode = chosenMode ?: dev.droidtop.library.settings.Mode.GAMING
                chosenMode = mode
                dev.droidtop.library.settings.Modes.setDefaultMode(context, mode)
                dev.droidtop.library.settings.Modes.setLastMode(context, mode)
                advanceFrom(OnboardingStep.DEFAULT_MODE_CHOICE)
            },
        )

        OnboardingStep.WHAT_NEXT -> WhatNextStep(
            progress, back,
            mode = chosenMode ?: dev.droidtop.library.settings.Mode.GAMING,
            homeImplementation = homeChoice ?: HomeRolePrefs.activeHomeImplementation(context),
            desktopConfigured = configureDesktop && desktopImageChosen,
            modesOn = appModesOnAfterOnboarding(
                configureGaming, configureDesktop, desktopCapable,
                chosenMode ?: dev.droidtop.library.settings.Mode.GAMING,
            ),
            gamingConfigured = configureGaming,
            gamesFound = rootReports.values.sumOf { it.total },
            // A root whose count has not come back yet is still being
            // walked: the summary must say so rather than report the
            // zero it has not finished counting.
            gamesStillCounting = roots.any { it !in rootReports.keys },
            gamesSoFar = rootProgress.filterKeys { it !in rootReports.keys }.values.sumOf { it.gamesSoFar },
            storageGranted = storageAccessGranted,
            controllerAnswered = ControllerPrefs.asked(context),
            keyboardSkipped = configureDesktop && !dev.droidtop.library.settings.Keyboards.ownKeyboardActive(context),
            homeHeld = droidtopIsHome,
            noFolders = roots.isEmpty(),
            onFinish = {
                val mode = chosenMode ?: dev.droidtop.library.settings.Mode.GAMING
                val home = homeChoice ?: HomeRolePrefs.activeHomeImplementation(context)
                finishOnboarding(mode)
                // droidtop's one icon on the home screen it just set up, so
                // the way into Gaming or Desktop is in sight rather than in
                // a drawer or behind a gesture (SPEC 2c).
                if (home == HomeRolePrefs.HomeImplementation.STANDARD) HomeRolePrefs.placeDroidtopIcon(context)
                if (mode == dev.droidtop.library.settings.Mode.LAUNCHER) {
                    // The home screen is not a MainActivity shell. Sent
                    // there with "standard", MainActivity skipped it as
                    // not its own and opened Gaming, so "Open Android"
                    // landed in the game library.
                    BackButtonMenu.openHome(context, home)
                } else {
                    context.startActivity(
                        Intent(Intent.ACTION_MAIN).apply {
                            setClassName(context.packageName, "dev.droidtop.app.MainActivity")
                            putExtra(BackButtonMenu.EXTRA_MODE, mode.id)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
                // The first-run tutorial, over the first frame of the mode
                // it teaches (SPEC 7b): started last, so it is on top.
                TutorialActivity.start(context)
            },
        )
    }
    }
}

private fun shouldShowStorageRationale(context: Context): Boolean {
    val activity = context as? android.app.Activity ?: return true
    return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, LEGACY_STORAGE_PERMISSION)
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
 * The other half of the same rule, for a step that cannot be skipped
 * (docs/SPEC.md 7b): it has NO forward action until it is answered, and
 * its own answer rows are the way on. A greyed "Next" is neither -- it is
 * an action that says "go on" while refusing to, and it leaves the step
 * with nothing that can be pressed at all (rig, build 548: step 2 of 7,
 * "Your Android home screen").
 *
 * Which steps those are is decided by whether skipping has a meaning.
 * Picking a home-screen behaviour has one row that MEANS "not now"
 * ("Neither, for now"), so a skip beside it would be a second way to say
 * the same thing and droidtop would be choosing it for the person;
 * picking WHICH launcher to hand Home to has no default at all. Both are
 * answered on the step or not at all.
 */
internal fun onboardingForwardLabelWhenAnswerRequired(answered: Boolean): String? =
    if (answered) "Next" else null

private fun onboardingForwardWhenAnswered(answered: Boolean, onContinue: () -> Unit): StepAction? =
    onboardingForwardLabelWhenAnswerRequired(answered)?.let { StepAction(it, onClick = onContinue) }

/**
 * One action in the scaffold's action area. It has no disabled state:
 * an onboarding step's actions are always actionable, and a step with
 * nothing to press yet shows nothing (see [onboardingForwardLabel] and
 * [onboardingForwardWhenAnswered]).
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
    // Where the pad's selection starts: the step's forward action, or --
    // on a step that asks something and has not been answered -- its first
    // answer. Nothing focused meant a pad press landed nowhere: A did
    // nothing on Welcome and D-pad Down went to the Back button
    // (dq-coordinator-24, finding 7).
    focusContentFirst: Boolean = false,
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
                if (primary != null && !focusContentFirst) primaryFocus.requestFocus() else contentFocus.requestFocus()
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
                        "Part ${progress.number} of ${progress.count}: ${progress.part.label}",
                        color = MenuTokens.OnSurfaceMuted,
                        style = TypeRole.sectionLabel,
                    )
                }
            }
            if (progress != null) PartsBar(progress)

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
 * One segment per part: filled for the parts behind and the one being
 * answered, a track for the ones ahead. Drawn rather than Material's
 * LinearProgressIndicator, whose end-of-track stop dot read as a stray
 * mark at the far right (dq-coordinator-24, finding 9).
 */
@Composable
private fun PartsBar(progress: StepProgress) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        OnboardingPart.entries.forEach { part ->
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = Space.Xs)
                    .background(
                        if (part.ordinal <= progress.part.ordinal) MenuTokens.Accent else MenuTokens.Surface,
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

@Composable
private fun WelcomeStep(progress: StepProgress?, onBack: (() -> Unit)?, onContinue: () -> Unit) {
    OnboardingScaffold(
        title = "Welcome to droidtop",
        body = "droidtop can turn this device into a desktop, a gamepad-driven game " +
            "library, or your ordinary Android home screen. You choose what to set " +
            "up, and every choice here is changeable later in Settings.",
        progress = progress,
        onBack = onBack,
        primary = StepAction("Get started", onClick = onContinue),
    ) {
        DroidtopMark()
    }
}

/**
 * The first screen of a launcher was pure text on black. This is
 * droidtop's own mark, drawn rather than shipped as another asset: the
 * three surfaces it puts on one device, in the shell's own accent.
 *
 * Prose, not a control. Welcome SAYS what droidtop can turn this device
 * into (docs/SPEC.md 7b); the choice of what to set up is its own step,
 * with the one choice component in it. Drawn as filled, accent-coloured
 * chips these three words looked exactly like a selector that ignored
 * every tap and could not be reached by the D-pad, because there was
 * nothing there to reach (rig, build 547). A shape that says "pick one"
 * belongs only where one can be picked.
 */
@Composable
private fun DroidtopMark() {
    Text(
        "Android  ·  Gaming  ·  Desktop",
        color = MenuTokens.Accent,
        style = TypeRole.rowTitle,
        modifier = Modifier.padding(top = Space.Md),
    )
}

@Composable
private fun HomeChoiceStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    selected: HomeRolePrefs.HomeImplementation?,
    onSelect: (HomeRolePrefs.HomeImplementation) -> Unit,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        title = "Your Android home screen",
        body = "How should the home screen behave when you press Home? You can change " +
            "this later in Settings.",
        progress = progress,
        onBack = onBack,
        // No way past this step until it is answered, and the three rows
        // below ARE the answer: one of them means "not now", so there is
        // nothing a skip could say that they do not (7b).
        primary = onboardingForwardWhenAnswered(selected != null, onContinue),
        focusContentFirst = true,
    ) {
        SelectableRow(
            title = "droidtop's own launcher",
            supporting = "Home screen, app drawer and widgets, from droidtop.",
            selected = selected == HomeRolePrefs.HomeImplementation.STANDARD,
            onClick = { onSelect(HomeRolePrefs.HomeImplementation.STANDARD) },
        )
        SelectableRow(
            title = "A launcher you already have",
            supporting = "droidtop holds the Home role and opens the launcher you pick.",
            selected = selected == HomeRolePrefs.HomeImplementation.ALTERNATIVE,
            onClick = { onSelect(HomeRolePrefs.HomeImplementation.ALTERNATIVE) },
        )
        SelectableRow(
            title = "Neither, for now",
            supporting = "droidtop claims no Home role; its icon opens it like any other app.",
            selected = selected == HomeRolePrefs.HomeImplementation.NONE,
            onClick = { onSelect(HomeRolePrefs.HomeImplementation.NONE) },
        )
    }
}

@Composable
private fun StandardSetupStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isHome: Boolean,
    onMakeHome: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    OnboardingScaffold(
        title = "droidtop's launcher",
        body = "It is a full launcher: icon packs, grid density, app drawer folders and " +
            "backup all live in Settings under Home screen, App drawer and Icons. You " +
            "can set it up now or leave it at its defaults and come back later.",
        progress = progress,
        onBack = onBack,
        // A hand-off step (SPEC 7b): Android, not droidtop, decides which
        // app Home opens, so making droidtop the Home app is the step's own
        // work and the forward action waits beside it as the skip until
        // Android says it took. Enabling droidtop's HOME activity alone
        // left Pixel Launcher as Home on Android 14 (rig, dq-onboard-01).
        primary = if (isHome) StepAction("Next", onClick = onContinue) else StepAction("Make droidtop the Home app", onClick = onMakeHome),
        secondary = if (isHome) null else StepAction("Skip this step", onClick = onContinue),
    ) {
        if (isHome) {
            StepNote("droidtop is the Home app: the Home button opens it.", accent = true)
        } else {
            StepNote(
                "Android asks you to confirm which app the Home button opens; droidtop cannot " +
                    "choose itself. Skip this and droidtop is not your home screen until you choose it " +
                    "in Settings, Global settings.",
            )
        }
        PadButton("Open launcher settings", {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(context.packageName, "com.android.launcher3.settings.SettingsActivity")
                putExtra(":settings:fragment", "app.murinelauncher.settings.SettingsHomeFragment")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        })
    }
}

@Composable
private fun AlternativeSetupStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    onPicked: (ComponentName) -> Unit,
) {
    val context = LocalContext.current
    var launchers by remember {
        mutableStateOf<List<Triple<ComponentName, String, android.graphics.drawable.Drawable?>>?>(null)
    }
    var selected by remember { mutableStateOf<ComponentName?>(null) }

    LaunchedEffect(Unit) {
        launchers = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(homeIntent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { info ->
                    Triple(
                        ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                        // The application's own label, never a class name
                        // and never a label that names nothing: the host
                        // launcher's activity label on the rig was the
                        // single word "Home".
                        runCatching { pm.getApplicationLabel(info.activityInfo.applicationInfo).toString() }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: info.loadLabel(pm).toString(),
                        runCatching { info.loadIcon(pm) }.getOrNull(),
                    )
                }
                .distinctBy { it.first }
        }
    }

    val current = launchers
    OnboardingScaffold(
        title = "Pick a launcher",
        body = "Pressing Home will open the launcher you pick here. droidtop still " +
            "handles switching between its own modes.",
        progress = progress,
        onBack = onBack,
        // Same rule as the step before it: the list is the answer, and
        // droidtop picks nobody's launcher for them (7b).
        primary = onboardingForwardWhenAnswered(selected != null) { selected?.let(onPicked) },
        focusContentFirst = true,
    ) {
        when {
            current == null -> StepNote("Looking for installed launchers.")
            current.isEmpty() -> StepNote(
                "No other launcher is installed on this device. Go back and pick " +
                    "droidtop's own launcher, or neither.",
            )
            else -> current.forEach { (component, label, icon) ->
                SelectableRow(
                    title = label,
                    supporting = component.packageName,
                    selected = selected == component,
                    icon = icon,
                    onClick = { selected = component },
                )
            }
        }
    }
}

@Composable
private fun ConfigureMoreStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    desktopChecked: Boolean,
    gamingChecked: Boolean,
    onDesktopChanged: (Boolean) -> Unit,
    onGamingChanged: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        title = "Anything else to set up?",
        body = "A mode you leave unticked is switched off and runs nothing until you " +
            "turn it on in Settings, under Global settings. Leaving both unticked is a " +
            "real answer; if nothing else is set up either, droidtop keeps Gaming on so " +
            "it has somewhere to open.",
        progress = progress,
        onBack = onBack,
        primary = StepAction("Next", onClick = onContinue),
        focusContentFirst = true,
    ) {
        // Plain words for what each one is: "ES-DE themes" and "a distro
        // image" meant nothing to a newcomer (dq-coordinator-24, finding 13).
        SelectableRow(
            title = "Gaming",
            supporting = "Your games in one library you drive with a controller or by touch, with a theme " +
                "to choose. Needs the folders your games are in.",
            selected = gamingChecked,
            onClick = { onGamingChanged(!gamingChecked) },
        )
        SelectableRow(
            title = "Desktop",
            supporting = "Run Linux and Windows programs in windows, like a PC. Downloads a Linux system " +
                "the first time it starts.",
            selected = desktopChecked,
            onClick = { onDesktopChanged(!desktopChecked) },
        )
    }
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
                // arm64 is the hard filter -- droidtop only targets ARM64
                // hardware; an amd64-only entry (e.g. official Arch) can't
                // run here regardless of anything else (docs/SPEC.md §3a).
                .filter { it.arm64Available }
                .filter { it.role == ImageCatalogRole.PRIMARY || it.role == ImageCatalogRole.BOTH }
        }
        // ONLY a previously-made real choice pre-selects -- droidtop never
        // picks an image the user didn't (docs/SPEC.md §3a).
        selectedId = DesktopSetupPrefs.preferredPrimaryImageId(context)
        // Whichever backend this device gets (root: droidspaces, otherwise
        // proot) answers for itself by running something real.
        val runtime = withContext(Dispatchers.IO) { ContainerRuntimeFactory.select(context) }
        val result = withContext(Dispatchers.IO) { runtime.checkSystemRequirements() }
        checkResult = result.succeeded
        onCapabilityKnown(result.succeeded)
        // A statement a person can act on, not a backend error string:
        // what was found, what it means, what to do about it.
        checkMessage = when {
            result.succeeded && runtime is DroidSpacesRuntime ->
                "Root access works. Desktop mode can run here, with real container isolation."
            result.succeeded ->
                "Desktop mode can run here. This device isn't rooted, so containers run " +
                    "through proot: nothing to grant, somewhat slower, and without real isolation."
            runtime is DroidSpacesRuntime ->
                "Root is present but the check did not pass, so Desktop mode cannot " +
                    "start yet. You can finish setup and come back to this in Settings."
            else ->
                "Desktop mode cannot run on this device: proot could not start a program here " +
                    "(${result.stderr.ifBlank { result.stdout }.trim()}). You can finish setup without it."
        }
    }

    val capable = checkResult == true
    OnboardingScaffold(
        title = "Desktop setup",
        body = null,
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
            null -> StepNote("Checking whether this device can run Desktop mode.")
            true -> StepNote(checkMessage, accent = true)
            false -> StepNote(checkMessage)
        }
        // The distro list is not offered under a gate that makes it
        // unusable: when the mode cannot run here, droidtop says so and
        // does not present a choice underneath it (SPEC 7b).
        if (capable) {
            StepSectionLabel("Linux system and desktop")
            repositories.forEach { repo ->
                SelectableRow(
                    title = repo.desktopEnvironment?.let { "${repo.os} with $it" } ?: repo.os,
                    supporting = (if (repo.officialSource) "The distro's own image" else "A community ARM64 rebuild") +
                        ", downloaded the first time Desktop mode starts.",
                    selected = repo.id == selectedId,
                    onClick = { selectedId = repo.id },
                )
            }
        }
    }
}

@Composable
private fun StoragePermissionStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    legacy: Boolean,
    denied: Boolean,
    permanentlyDenied: Boolean,
    granted: Boolean,
    onGrant: () -> Unit,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        title = "Reading your game files",
        // The rationale comes BEFORE the prompt, per Android's own
        // guidance and SPEC 7b: what droidtop reads and what it does not.
        body = if (legacy) {
            "droidtop reads the game folders you name in the next step, and nothing " +
                "else: it does not read your photos, messages or other apps' data. " +
                "Android asks for this as an ordinary permission prompt on this version."
        } else {
            "droidtop reads the game folders you name in the next step, and nothing " +
                "else: it does not read your photos, messages or other apps' data. " +
                "Android grants this one on its own Settings screen — droidtop cannot " +
                "grant it itself. Turn on \"Allow access to manage all files\" there, " +
                "then come back."
        },
        progress = progress,
        onBack = onBack,
        primary = if (granted) {
            StepAction("Next", onClick = onContinue)
        } else {
            StepAction(if (legacy) "Allow access" else "Open Android's settings", onClick = onGrant)
        },
        secondary = if (granted) null else StepAction("Continue without it", onClick = onContinue),
    ) {
        when {
            granted -> StepNote("Storage access is on. droidtop can read the folders you name.", accent = true)
            permanentlyDenied -> StepNote(
                "Android will not show the prompt again. Until it is granted from " +
                    "Android's own app settings, droidtop will find no games; " +
                    "everything else works.",
            )
            denied -> StepNote(
                "Without it droidtop will find no games in your folders. Nothing else " +
                    "in droidtop is affected, and you can grant it later from Settings.",
            )
            else -> Unit
        }
    }
}

@Composable
private fun GamesFoldersStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
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
    val window = currentShellWindow()
    val context = LocalContext.current
    // Looked for once per visit: a directory listing of /storage and
    // /mnt/windows, off the main thread.
    val found by produceState<List<String>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { suggestedGameFolders() }
    }
    // "Game folders" is the ONE name for this concept, everywhere in
    // droidtop -- it used to be "ROM folders" in Settings and "Game
    // folders" here, two names one navigation step apart.
    OnboardingScaffold(
        title = "Game folders",
        body = "Add every folder your games live in — console ROMs in per-system " +
            "folders, and Ren'Py, RPG Maker or Kirikiri games alike. You can add " +
            "more, or change these, later in Settings.",
        progress = progress,
        onBack = onBack,
        primary = StepAction("Next", onClick = onContinue),
        secondary = StepAction("Add a folder", onClick = onAddFolder),
    ) {
        if (roots.isEmpty()) {
            StepNote("No folders added yet. Add one, or continue with an empty library.")
        } else {
            StepSectionLabel("Folders droidtop will scan")
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
                // different folder", already the action beside Next.
                if (report != null && report.exists && report.empty) {
                    StepNote(
                        "Nothing was found here. Add a different folder, create the " +
                            "standard folder layout inside this one, or continue with " +
                            "an empty library.",
                    )
                    PadButton("Create the standard folder layout here", { onGenerateStructure(path) })
                }
            }
        }

        structureReport?.let { StepNote(it, accent = true) }

        if (unresolvedFolderWarning) {
            StepNote(
                "That folder could not be used directly. It may be cloud-backed, or " +
                    "on a storage layout droidtop cannot map to a path; type its path " +
                    "below instead.",
            )
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
                    supporting = "Add it to scan it for games.",
                    trailing = { PadButton("Add", { onAddSuggested(path) }) },
                )
            }
        }

        StepSectionLabel("Somewhere the picker cannot reach")
        // The picker can only offer what Android calls a storage volume,
        // and real libraries live outside that set: an emulator's host
        // share (BlueStacks mounts one at /mnt/windows/BstSharedFolder), a
        // mount a rooted device adds itself, a USB drive under /mnt.
        StepNote("An emulator's shared folder, a mount you added yourself, a USB drive. Type its full path.")
        OutlinedTextField(
            value = pathEntry,
            onValueChange = onPathEntryChange,
            singleLine = true,
            label = { Text("Folder path") },
            placeholder = { Text("A full path, starting with /") },
            isError = pathError != null,
            modifier = Modifier.fillMaxWidth().widthIn(max = Measure.bodyMaxWidth),
        )
        if (pathError != null) {
            Text(pathError, color = MenuTokens.Danger, style = TypeRole.supporting)
        }
        if (pathEntry.isNotBlank()) PadButton("Add this path", onAddPath)
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
 * CONTROLLER. Answerable, and skippable until it is answered.
 *
 * Two things a person cannot be expected to find in Settings, and one
 * thing droidtop cannot work out on its own:
 *
 * - WHICH pad is attached, by the name it reports. droidtop uses the one
 *   detector it already has ([ControllerPrefs.attachedControllers], which
 *   is also what the Quick Menu's status header asks) -- a second
 *   detection mechanism for onboarding would be a second answer.
 * - That the mapping WORKS, confirmed by one press rather than asserted.
 *   Android reports a pad's buttons by POSITION, so the press names the
 *   button by position too.
 * - Which face button confirms. This is the one question, because it is
 *   the one thing no detection can answer: KEYCODE_BUTTON_A is the bottom
 *   face button whatever is printed on it, so on a Nintendo-style pad the
 *   button that confirms is the one labelled B. Asked as a question, not
 *   left as a setting to discover.
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
    // Whether the question has an ANSWER, which is not the same as which
    // answer is marked: `swapConfirmCancel` has a default, so one row is
    // always marked Selected and a person who has never answered would
    // otherwise look at a step that says it is already decided.
    var answered by remember { mutableStateOf(ControllerPrefs.asked(context)) }
    var pressed by remember { mutableStateOf<String?>(null) }
    // Focus does NOT start in the test box. It used to, and inside the box
    // every button is a test press, B included: opened from Settings, the
    // screen read B as "the right face button" and did not go back, which is
    // what B means on every other screen (UI pass 2026-09-24, screenshots
    // 41-42). The box tests buttons only once a person moves into it, and
    // says so while it has focus; everywhere else on the step B is back.
    var testing by remember { mutableStateOf(false) }

    fun choose(value: Boolean) {
        swapped = value
        answered = true
        ControllerPrefs.setSwapConfirmCancel(context, value)
    }

    OnboardingScaffold(
        title = "Controller",
        body = when {
            // Re-entered from Settings > Input > Controller: there is no
            // run to skip ahead of and nowhere later to be sent to.
            isReEntry -> "Press a button to check droidtop is reading your controller, and tell it " +
                "which face button means yes."
            controllers.isEmpty() -> "No controller is attached right now. droidtop works by touch " +
                "either way, and this step is here again in Settings when you plug one in."
            else -> "Press a button to check droidtop is reading your controller, then tell it which " +
                "face button means yes. You can skip this and change it later in Settings."
        },
        progress = progress,
        onBack = onBack,
        // ONE forward action, at full weight (docs/SPEC.md 7b, "One way
        // forward"). Before the question has an answer that action IS the
        // skip, and says so; once it is answered it is Next; a step opened
        // on its own from Settings has nothing after it, so it is Done.
        // A "Skip" text action beside a filled "Next" was two ways forward
        // side by side, and neither of them said which one moved on
        // without answering (rig, build 547).
        primary = StepAction(onboardingForwardLabel(reEntry = isReEntry, answered = answered), onClick = onContinue),
    ) {
        StepSectionLabel("Attached")
        if (controllers.isEmpty()) {
            StepNote("Nothing reporting as a controller.")
        } else {
            controllers.forEach { controller -> SelectableRow(title = controller.name) }
        }

        StepSectionLabel("Check it reads")
        // A real key event, caught where it lands: the box takes focus and
        // reports the button by position. Nothing is remapped here -- this
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
                    !testing && pressed == null -> "Move here with the d-pad to test your buttons."
                    !testing -> "droidtop read $pressed. Move here again to test another."
                    pressed == null -> "Press any button, B too: droidtop names the one it read. The d-pad moves on."
                    else -> "droidtop read $pressed. The d-pad moves on."
                },
                color = if (pressed != null) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
                style = TypeRole.supporting,
            )
        }

        StepSectionLabel("Which button means yes")
        StepNote(
            "Android tells droidtop where a button is, not what is printed on it, so this is " +
                "the one thing it cannot work out for you.",
        )
        // Marked only once the question HAS an answer. The preference
        // behind it has a default, so drawing that default as "Selected"
        // would tell a person they had already chosen something they were
        // never asked about -- and would make the Skip beside it a skip of
        // a question that looks answered.
        SelectableRow(
            title = "The bottom button confirms",
            supporting = "A confirms and B goes back. Xbox-style pads and most Android controllers.",
            selected = answered && !swapped,
            onClick = { choose(false) },
        )
        SelectableRow(
            title = "The right button confirms",
            supporting = "B confirms and A goes back. Nintendo-style pads, where the bottom button is the one labelled B.",
            selected = answered && swapped,
            onClick = { choose(true) },
        )
    }
}

/**
 * APPEARANCE. Every theme droidtop has, each with a REAL render of itself
 * (docs/SPEC.md 7b): the theme's own system view, parsed by the one theme
 * parser and drawn by the one renderer the Gaming shell uses
 * ([ThemeSystemPreview]). Not a screenshot, not a swatch.
 *
 * The portrait rule is stated as a property of the THEMES, not as a swap
 * to accept: on a tall screen the theme that ships portrait layouts is
 * preselected, every row says which kind of layouts its theme ships, and
 * choosing a landscape-only theme anyway restates what that will look
 * like. The choice is written down as soon as it is made, so rotating the
 * device later never moves the theme under the person.
 */
@Composable
private fun AppearanceStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
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
        ThemeBrowserScreen(onDismiss = {
            browsing = false
            catalogVersion++
        })
        return
    }
    val stored = remember { LibraryThemePrefs.get(context) }
    var picked by remember { mutableStateOf<String?>(null) }
    val chosen = picked ?: stored ?: catalog?.defaultTheme

    OnboardingScaffold(
        title = "Appearance",
        body = if (portraitScreen) {
            "Gaming draws itself with a theme. This screen is taller than it is wide, so themes " +
                "that lay out a tall screen are marked; the others will be stretched sideways to " +
                "fit. Get more themes to download another."
        } else {
            "Gaming draws itself with a theme. Every one droidtop has is here, drawing itself; " +
                "Get more themes downloads another from the ES-DE community's list."
        },
        progress = progress,
        onBack = onBack,
        primary = StepAction("Next", onClick = onContinue),
        secondary = StepAction("Get more themes") { browsing = true },
        focusContentFirst = true,
    ) {
        val themes = catalog?.themes
        if (themes != null && themes.isEmpty()) {
            StepNote("No themes are installed. Gaming mode will use its own plain layout.")
        }
        themes?.forEach { theme ->
            val hasVertical = theme.hasVertical
            SelectableRow(
                title = theme.displayName,
                supporting = when {
                    hasVertical && portraitScreen -> "Lays out a tall screen of its own. Recommended here."
                    hasVertical -> "Lays out both a wide and a tall screen."
                    portraitScreen -> "Wide layouts only: it will be stretched sideways on this screen."
                    else -> "Wide layouts only."
                },
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
    class Theme(val name: String, val displayName: String, val hasVertical: Boolean)

    companion object {
        fun read(context: android.content.Context): AppearanceCatalog {
            val discovered = ThemeAssets.discoverThemes(context)
            return AppearanceCatalog(
                themes = discovered.map { theme ->
                    Theme(
                        name = theme.name,
                        displayName = ThemeAssets.displayName(context, theme),
                        hasVertical = ThemeAssets.hasVerticalVariant(context, theme),
                    )
                },
                defaultTheme = ThemeAssets.defaultThemeFor(context, discovered)?.name,
            )
        }
    }
}

/**
 * OPTIONAL step. droidtop runs fine without its own keyboard; what it
 * cannot do without one is drive a terminal or a Windows application,
 * because no stock phone keyboard has Ctrl, Alt, Esc, Tab, arrows or a
 * function row (docs/SPEC.md section 6a).
 *
 * droidtop cannot set the system input method itself -- that needs
 * WRITE_SECURE_SETTINGS, which a normal app is never granted -- so this
 * states the reason and opens Android's own screens. Declining is a real
 * answer, not a nag to be repeated.
 */
@Composable
private fun KeyboardStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
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
        // the primary is the step's own work -- it opens Android's screen
        // and comes back here -- and the ONE forward action waits beside
        // it as the skip until the hand-off has actually taken.
        primary = when {
            active -> StepAction("Next", onClick = onContinue)
            !enabled -> StepAction("Turn it on in Android", onClick = onEnable)
            else -> StepAction("Switch to it", onClick = onPick)
        },
        secondary = if (active) null else StepAction("Skip this step", onClick = onContinue),
    ) {
        when {
            active -> StepNote("Hacker's Keyboard is the active keyboard.", accent = true)
            enabled -> StepNote("Hacker's Keyboard is enabled but not active. Android's own picker switches to it.")
            else -> StepNote(
                "Android decides which keyboard is in use, so this opens Android's own " +
                    "screen. If that screen does not exist on this device, nothing will " +
                    "open and you can skip this step.",
            )
        }
    }
}

/**
 * Only modes whose setup actually produced something usable are offered
 * — the OUTCOME, not the tick-box. Picking a Desktop that was skipped,
 * or whose root check failed, landed straight in that mode's failure
 * screen; the gate used to be the checkbox, which is exactly the dead end
 * the step existed to prevent.
 */
@Composable
private fun DefaultModeChoiceStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    homeImplementation: HomeRolePrefs.HomeImplementation,
    desktopUsable: Boolean,
    gamingUsable: Boolean,
    selected: dev.droidtop.library.settings.Mode?,
    onSelect: (dev.droidtop.library.settings.Mode) -> Unit,
    onContinue: () -> Unit,
) {
    val modes = buildList {
        if (homeImplementation != HomeRolePrefs.HomeImplementation.NONE) {
            add(dev.droidtop.library.settings.Mode.LAUNCHER to "Your home screen, as you set it up a moment ago.")
        }
        if (gamingUsable) {
            add(dev.droidtop.library.settings.Mode.GAMING to "The game library, in the theme you chose.")
        }
        if (desktopUsable) {
            add(dev.droidtop.library.settings.Mode.DESKTOP to "The Linux desktop, with the image you chose.")
        }
        if (isEmpty()) {
            add(
                dev.droidtop.library.settings.Mode.GAMING to
                    "Nothing was set up yet, so droidtop opens the game library and " +
                        "explains what to add. Everything else is in Settings.",
            )
        }
    }
    val single = modes.size == 1
    val effective = selected ?: modes.first().first

    OnboardingScaffold(
        // When exactly one mode qualifies this is a confirmation, not a
        // question with one answer.
        title = if (single) "droidtop will open into ${modes.first().first.label}" else "Which should droidtop open into?",
        body = if (single) {
            "That is the only thing set up so far. Anything else you set up later can " +
                "become the default from Settings."
        } else {
            "This is where droidtop opens, and where the Home button takes you. Everything " +
                "else you set up is one step away in the mode switcher; the tutorial after this " +
                "shows where it is."
        },
        progress = progress,
        onBack = onBack,
        primary = StepAction("Next") {
            onSelect(effective)
            onContinue()
        },
        focusContentFirst = !single,
    ) {
        if (!single) {
            modes.forEach { (mode, supporting) ->
                SelectableRow(
                    title = mode.label,
                    supporting = supporting,
                    selected = effective == mode,
                    onClick = { onSelect(mode) },
                )
            }
        } else {
            StepNote(modes.first().second)
        }
    }
}

/**
 * Onboarding ends with a summary and one action into the chosen mode
 * (SPEC 7b, "What next"). It used to end on a question with one answer
 * that dropped the person at the system home with no statement of what
 * had just been set up or where the skipped parts live.
 */
@Composable
private fun WhatNextStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    mode: dev.droidtop.library.settings.Mode,
    homeImplementation: HomeRolePrefs.HomeImplementation,
    desktopConfigured: Boolean,
    modesOn: Set<dev.droidtop.library.settings.Mode>,
    gamingConfigured: Boolean,
    gamesFound: Int,
    gamesStillCounting: Boolean,
    gamesSoFar: Int,
    storageGranted: Boolean,
    controllerAnswered: Boolean,
    keyboardSkipped: Boolean,
    homeHeld: Boolean,
    noFolders: Boolean,
    onFinish: () -> Unit,
) {
    val done = buildList {
        when (homeImplementation) {
            HomeRolePrefs.HomeImplementation.STANDARD -> if (homeHeld) add("Home screen: droidtop's own launcher.")
            HomeRolePrefs.HomeImplementation.ALTERNATIVE -> if (homeHeld) add("Home screen: the launcher you picked.")
            HomeRolePrefs.HomeImplementation.NONE -> Unit
        }
        if (gamingConfigured) {
            // Three different facts, and the rig caught them collapsed
            // into one: a scan that is still walking a folder said
            // "no games found yet", which reads as a finished, empty
            // library (build 539). A count in flight says it is counting.
            val counted = gamesFound + gamesSoFar
            add(
                when {
                    gamesStillCounting && counted > 0 ->
                        "Gaming: still counting your folders - $counted " +
                            (if (counted == 1) "game" else "games") + " so far."
                    gamesStillCounting -> "Gaming: set up, still counting your folders."
                    gamesFound > 0 ->
                        "Gaming: $gamesFound " + (if (gamesFound == 1) "game" else "games") + " found in your folders."
                    noFolders -> "Gaming: set up, with no game folders added yet."
                    else -> "Gaming: set up, with no games found in your folders."
                },
            )
        }
        if (desktopConfigured) add("Desktop: image chosen, downloaded the first time it starts.")
        add("Opens into ${mode.label}.")
    }
    val skipped = buildList {
        if (homeImplementation == HomeRolePrefs.HomeImplementation.NONE) {
            add("Home screen — Settings, Global settings.")
        } else if (!homeHeld) {
            add("Home screen — Android's Home button still opens another app; Settings, Global settings.")
        }
        // Where each one lives, and whether it is on: an unticked mode is
        // switched off at the end of this step (appModesOnAfterOnboarding).
        when {
            gamingConfigured -> Unit
            dev.droidtop.library.settings.Mode.GAMING in modesOn ->
                add("Game folders — none yet; Settings, Game folders.")
            else -> add("Gaming — off; turn it on in Settings, Global settings.")
        }
        when {
            desktopConfigured -> Unit
            dev.droidtop.library.settings.Mode.DESKTOP in modesOn ->
                add("Desktop — on, with no image chosen yet; Settings, Desktop mode, Desktop setup.")
            else -> add("Desktop — off; turn it on in Settings, Global settings.")
        }
        if (gamingConfigured && !storageGranted) add("Storage access — Settings, Game folders.")
        // Skipped steps are skipped things too; the summary used to list
        // neither (dq-coordinator-24, finding 10).
        if (!controllerAnswered) add("Controller — Settings, Input, Controller.")
        if (keyboardSkipped) add("Keyboard — Settings, Input, Keyboard.")
    }
    // What a newcomer needs a minute from now and would otherwise have to
    // find (dq-coordinator-24, finding 8): each is asked where it is used,
    // as the permissions rule says (SPEC 7b), so here it is only named.
    val later = buildList {
        if (gamingConfigured) {
            add("Pictures and descriptions for your games — Settings, Library, Scraper.")
            add("Windows games need one download the first time — a Windows game's page offers it.")
            add("Notifications in the Quick Menu — its Notifications tab asks for access.")
        }
    }

    OnboardingScaffold(
        title = "You're set up",
        body = null,
        progress = progress,
        onBack = onBack,
        primary = StepAction("Open ${mode.label}", onClick = onFinish),
    ) {
        StepSectionLabel("What is set up")
        done.forEach { StepNote("• $it") }
        if (skipped.isNotEmpty()) {
            StepSectionLabel("Skipped, and where it lives")
            skipped.forEach { StepNote("• $it") }
        }
        if (later.isNotEmpty()) {
            StepSectionLabel("When you want them")
            later.forEach { StepNote("• $it") }
        }
        Spacer(modifier = Modifier.padding(top = Space.Sm))
        StepNote(
            "Every one of these can be changed later; nothing here is final. A short tour of " +
                "the controls and where things are follows.",
        )
    }
}
