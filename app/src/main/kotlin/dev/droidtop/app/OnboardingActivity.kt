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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
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
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.HomeRolePrefs
import kotlinx.coroutines.Dispatchers
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val startStep = intent.getStringExtra(EXTRA_START_STEP)
            ?.let { name -> OnboardingStep.entries.firstOrNull { it.name == name } }
        setContent {
            // Onboarding is dark, like the shell it hands over to. It used
            // to follow the system setting, so a device in light mode got
            // a white first run that dropped into an always-dark Gaming
            // shell at the end of it (SPEC 7b).
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                OnboardingScreen(
                    startStep = startStep,
                    isReEntry = startStep != null,
                    onDone = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_START_STEP = "dev.droidtop.app.EXTRA_START_STEP"
    }
}

private enum class OnboardingStep {
    WELCOME, HOME_CHOICE, STANDARD_SETUP, ALTERNATIVE_SETUP,
    CONFIGURE_MORE, DESKTOP_SETUP, STORAGE_PERMISSION, GAMES_FOLDERS,
    PORTRAIT_THEME, KEYBOARD, DEFAULT_MODE_CHOICE, WHAT_NEXT,
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
private fun plannedSteps(
    home: HomeRolePrefs.HomeImplementation?,
    configureDesktop: Boolean,
    configureGaming: Boolean,
    storageGranted: Boolean,
    portraitThemeSwap: String?,
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
        // Skipped outright when the permission is already held: the step
        // used to re-ask for something droidtop already had.
        if (!storageGranted) add(OnboardingStep.STORAGE_PERMISSION)
        add(OnboardingStep.GAMES_FOLDERS)
        if (portraitThemeSwap != null) add(OnboardingStep.PORTRAIT_THEME)
    }
    add(OnboardingStep.KEYBOARD)
    add(OnboardingStep.DEFAULT_MODE_CHOICE)
    add(OnboardingStep.WHAT_NEXT)
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
private fun OnboardingScreen(startStep: OnboardingStep?, isReEntry: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var step by remember { mutableStateOf(startStep ?: OnboardingStep.WELCOME) }
    // The path actually taken, which is what Back walks: a person who
    // answered "a launcher I already have" goes back to that list, not to
    // a step the plan says comes before this one on paper.
    val history = remember { mutableStateListOf<OnboardingStep>() }

    var homeChoice by remember { mutableStateOf<HomeRolePrefs.HomeImplementation?>(null) }
    var configureDesktop by remember { mutableStateOf(false) }
    var configureGaming by remember { mutableStateOf(false) }
    var desktopImageChosen by remember { mutableStateOf(false) }
    var desktopCapable by remember { mutableStateOf(false) }
    var unresolvedFolderWarning by remember { mutableStateOf(false) }
    var pathEntry by remember { mutableStateOf("") }
    var pathError by remember { mutableStateOf<String?>(null) }
    var storageAccessGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    var storageDenied by remember { mutableStateOf(false) }
    var storagePermanentlyDenied by remember { mutableStateOf(false) }
    var chosenMode by remember { mutableStateOf<dev.droidtop.library.settings.Mode?>(null) }
    var confirmLeaving by remember { mutableStateOf(false) }
    var structureReport by remember { mutableStateOf<String?>(null) }

    var rootsVersion by remember { mutableStateOf(0) }
    val roots = remember(rootsVersion) { GamesRootPrefs.gamesRootPaths(context) }
    // What each root turned out to hold. Filled in off the main thread as
    // roots appear; a root with no report yet shows "Looking…" rather
    // than a number it has not counted.
    val rootReports = remember { mutableStateMapOf<String, GamesRootReport.Report>() }

    LaunchedEffect(rootsVersion, roots) {
        roots.forEach { path ->
            if (!rootReports.containsKey(path)) {
                rootReports[path] = GamesRootReport.of(context, path)
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

    // Portrait devices: the theme droidtop would otherwise default to
    // (DEcaffe) ships no vertical variant, so ES-DE's aspect-ratio
    // selection can only stretch its closest landscape layout over the
    // screen (EsDeAspectRatio.select). Non-null = this device is
    // portrait AND droidtop is defaulting to a different theme because
    // of it; the step below says so rather than quietly swapping.
    val portraitThemeSwap: String? = remember {
        if (!ThemeAssets.isPortraitScreen(context)) {
            null
        } else {
            val chosen = ThemeAssets.activeThemeName(context)
            val landscapeDefault = ThemeAssets.discoverThemes(context)
                .firstOrNull { it.name == "decaffe-es-de" }?.name
            if (chosen != null && chosen != landscapeDefault) chosen else null
        }
    }

    val plan = plannedSteps(homeChoice, configureDesktop, configureGaming, storageAccessGranted, portraitThemeSwap)

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
        val next = if (here >= 0 && here + 1 < plan.size) plan[here + 1] else OnboardingStep.WHAT_NEXT
        goTo(next)
    }

    fun finishOnboarding() {
        GamesRootPrefs.markOnboardingComplete(context)
        onDone()
    }

    val canGoBack = !isReEntry && history.isNotEmpty()

    // System Back is the same control as the scaffold's Back, and leaving
    // is a deliberate act: one Back press on the first step used to drop
    // the whole flow to the system home with nothing saved.
    BackHandler(enabled = true) {
        when {
            isReEntry -> onDone()
            history.isNotEmpty() -> step = history.removeAt(history.lastIndex)
            else -> confirmLeaving = true
        }
    }

    if (confirmLeaving) {
        AlertDialog(
            onDismissRequest = { confirmLeaving = false },
            title = { Text("Leave setup?") },
            text = { Text("droidtop will ask again next time it starts. Nothing you have set so far is lost.") },
            confirmButton = { TextButton(onClick = { confirmLeaving = false; onDone() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeaving = false }) { Text("Keep setting up") } },
        )
    }

    val progress = if (isReEntry) null else (plan.indexOf(step).takeIf { it >= 0 }?.plus(1) ?: plan.size) to plan.size
    val back: (() -> Unit)? = if (canGoBack) ({ step = history.removeAt(history.lastIndex) }) else null

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
                    rootReports[path] = GamesRootReport.of(context, path)
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
            onContinue = { advanceFrom(OnboardingStep.GAMES_FOLDERS) },
        )

        OnboardingStep.PORTRAIT_THEME -> PortraitThemeStep(
            progress, back,
            themeName = portraitThemeSwap.orEmpty(),
            onKeep = {
                // Write the resolved default down as a real choice, so
                // rotating the device later does not silently move the
                // theme under the person.
                portraitThemeSwap?.let { LibraryThemePrefs.set(context, it) }
                advanceFrom(OnboardingStep.PORTRAIT_THEME)
            },
            onUseLandscapeTheme = {
                LibraryThemePrefs.set(context, "decaffe-es-de")
                advanceFrom(OnboardingStep.PORTRAIT_THEME)
            },
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
            gamingConfigured = configureGaming,
            gamesFound = rootReports.values.sumOf { it.total },
            storageGranted = storageAccessGranted,
            onFinish = {
                val mode = chosenMode ?: dev.droidtop.library.settings.Mode.GAMING
                finishOnboarding()
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        setClassName(context.packageName, "dev.droidtop.app.MainActivity")
                        putExtra(BackButtonMenu.EXTRA_MODE, mode.id)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            },
        )
    }
}

private fun shouldShowStorageRationale(context: Context): Boolean {
    val activity = context as? android.app.Activity ?: return true
    return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, LEGACY_STORAGE_PERMISSION)
}

// ---------------------------------------------------------------------
// The frame every step renders into
// ---------------------------------------------------------------------

/** One action in the scaffold's action area. */
private data class StepAction(
    val label: String,
    val enabled: Boolean = true,
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
    progress: Pair<Int, Int>?,
    onBack: (() -> Unit)?,
    primary: StepAction?,
    secondary: StepAction? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val window = currentShellWindow()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The action area is docked at the bottom, so it is the first
            // thing a keyboard covers: typing a games-folder path put Next
            // underneath the IME with no way to reach it (phone AVD,
            // 2026-09-11). imePadding lifts the whole frame instead, which
            // is why the activity fits its own insets rather than letting
            // the decor do it.
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = window.edgePadding),
    ) {
        // --- progress and Back -------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.Lg, bottom = Space.Md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.Md),
        ) {
            if (onBack != null) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = window.minTouchTarget),
                ) { Text("Back", style = TypeRole.button) }
            }
            if (progress != null) {
                Text(
                    "Step ${progress.first} of ${progress.second}",
                    color = MenuTokens.OnSurfaceMuted,
                    style = TypeRole.sectionLabel,
                )
            }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.first.toFloat() / progress.second.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = MenuTokens.Accent,
                trackColor = MenuTokens.Surface,
            )
        }

        // --- content ------------------------------------------------
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
            content()
        }

        // --- the action area, docked --------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = Space.Xl),
            horizontalArrangement = Arrangement.spacedBy(Space.Md, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            secondary?.let {
                TextButton(
                    onClick = it.onClick,
                    enabled = it.enabled,
                    modifier = Modifier.heightIn(min = window.minTouchTarget),
                ) { Text(it.label, style = TypeRole.button) }
            }
            primary?.let {
                Button(
                    onClick = it.onClick,
                    enabled = it.enabled,
                    modifier = Modifier
                        .heightIn(min = window.minTouchTarget)
                        // On a phone the primary fills the row; at TV
                        // distance it stays a button on the right.
                        .then(if (window.portrait) Modifier.weight(1f) else Modifier),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MenuTokens.Accent,
                        contentColor = MenuTokens.OverlaySurface,
                    ),
                ) { Text(it.label, style = TypeRole.button) }
            }
        }
    }
}

/**
 * The ONE choice component (docs/SPEC.md 7b, "The one choice
 * component"): every question with mutually exclusive answers is a run of
 * these. Full width, at least the window's own minimum touch target, an
 * optional leading icon, a title, one supporting line, and a real
 * selected state — the shell's own menu row anatomy rather than a third
 * one invented here.
 *
 * What it replaces: three equal answers rendered as two filled buttons
 * and a text link, with no selection semantics at all.
 */
@Composable
private fun SelectableRow(
    title: String,
    supporting: String? = null,
    selected: Boolean = false,
    icon: android.graphics.drawable.Drawable? = null,
    trailing: (@Composable () -> Unit)? = null,
    // Null for a row that is information with its own action beside it (a
    // games folder and its Remove), rather than a choice to be made.
    onClick: (() -> Unit)? = null,
) {
    val window = currentShellWindow()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = window.minTouchTarget + Space.Sm)
            .background(
                if (selected) MenuTokens.SurfaceSelected else MenuTokens.Surface,
                MenuTokens.RowShape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.Lg, vertical = Space.Md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.Md),
    ) {
        icon?.let { drawable ->
            val bitmap = remember(drawable) {
                runCatching { drawable.toBitmap(width = 96, height = 96).asImageBitmap() }.getOrNull()
            }
            bitmap?.let { Image(bitmap = it, contentDescription = null, modifier = Modifier.size(Measure.rowIcon)) }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.Hair)) {
            Text(
                title,
                color = if (selected) MenuTokens.OnSurface else MenuTokens.OnSurface,
                style = TypeRole.rowTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.let {
                Text(it, color = MenuTokens.OnSurfaceMuted, style = TypeRole.supporting)
            }
        }
        if (selected && trailing == null) {
            Text("Selected", color = MenuTokens.Accent, style = TypeRole.supporting)
        }
        trailing?.invoke()
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
private fun WelcomeStep(progress: Pair<Int, Int>?, onBack: (() -> Unit)?, onContinue: () -> Unit) {
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
 */
@Composable
private fun DroidtopMark() {
    Row(
        modifier = Modifier.padding(top = Space.Md),
        horizontalArrangement = Arrangement.spacedBy(Space.Sm),
    ) {
        listOf("Android", "Gaming", "Desktop").forEach { label ->
            Box(
                modifier = Modifier
                    .background(MenuTokens.Surface, RoundedCornerShape(Space.Sm))
                    .padding(horizontal = Space.Lg, vertical = Space.Md),
            ) {
                Text(label, color = MenuTokens.Accent, style = TypeRole.rowTitle)
            }
        }
    }
}

@Composable
private fun HomeChoiceStep(
    progress: Pair<Int, Int>?,
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
        primary = StepAction("Next", enabled = selected != null, onClick = onContinue),
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
private fun StandardSetupStep(progress: Pair<Int, Int>?, onBack: (() -> Unit)?, onContinue: () -> Unit) {
    val context = LocalContext.current
    OnboardingScaffold(
        title = "droidtop's launcher",
        body = "It is a full launcher: icon packs, grid density, app drawer folders and " +
            "backup all live in Settings under Home screen, App drawer and Icons. You " +
            "can set it up now or leave it at its defaults and come back later.",
        progress = progress,
        onBack = onBack,
        // Next is the step's own advance and carries full weight; opening
        // the launcher's settings is the smaller action beside it, which
        // is the opposite of how this step used to be weighted.
        primary = StepAction("Next", onClick = onContinue),
        secondary = StepAction("Open launcher settings") {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(context.packageName, "com.android.launcher3.settings.SettingsActivity")
                putExtra(":settings:fragment", "app.murinelauncher.settings.SettingsHomeFragment")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        },
    )
}

@Composable
private fun AlternativeSetupStep(
    progress: Pair<Int, Int>?,
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
        primary = StepAction("Next", enabled = selected != null) { selected?.let(onPicked) },
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
    progress: Pair<Int, Int>?,
    onBack: (() -> Unit)?,
    desktopChecked: Boolean,
    gamingChecked: Boolean,
    onDesktopChanged: (Boolean) -> Unit,
    onGamingChanged: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    OnboardingScaffold(
        title = "Anything else to set up?",
        body = "Both modes stay reachable from droidtop's mode switcher whatever you " +
            "picked for your home screen. Leaving both off is a real answer: you can " +
            "set either of them up later from Settings.",
        progress = progress,
        onBack = onBack,
        primary = StepAction("Next", onClick = onContinue),
    ) {
        SelectableRow(
            title = "Gaming",
            supporting = "A game library with ES-DE themes. Needs storage access and your game folders.",
            selected = gamingChecked,
            onClick = { onGamingChanged(!gamingChecked) },
        )
        SelectableRow(
            title = "Desktop",
            supporting = "Wine and Linux containers. Needs root on this device, and a distro image to download.",
            selected = desktopChecked,
            onClick = { onDesktopChanged(!desktopChecked) },
        )
    }
}

@Composable
private fun DesktopSetupStep(
    progress: Pair<Int, Int>?,
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
        val rootAccess = withContext(Dispatchers.IO) { ContainerRuntimeFactory.rootAccess() }
        val result = withContext(Dispatchers.IO) {
            when (val runtime = ContainerRuntimeFactory.select(context)) {
                is DroidSpacesRuntime -> runtime.checkSystemRequirements()
                else -> null
            }
        }
        checkResult = result?.succeeded ?: false
        onCapabilityKnown(result?.succeeded == true)
        // A statement a person can act on, not a backend error string:
        // what was found, what it means, what to do about it.
        checkMessage = when {
            result == null ->
                "Desktop mode needs root on this device (Magisk, KernelSU or APatch). " +
                    "This device reports: ${rootAccess.description.lowercase()}. " +
                    "You can finish setup without it and turn Desktop on later."
            result.succeeded -> "Root access works. Desktop mode can run here."
            else ->
                "Root is present but the check did not pass, so Desktop mode cannot " +
                    "start yet. You can finish setup and come back to this in Settings."
        }
    }

    val capable = checkResult == true
    OnboardingScaffold(
        title = "Desktop setup",
        body = null,
        progress = progress,
        onBack = onBack,
        primary = StepAction(
            label = if (capable) "Next" else "Continue without Desktop",
            enabled = !capable || selectedId != null,
        ) {
            if (capable && selectedId != null) {
                DesktopSetupPrefs.setPreferredPrimaryImageId(context, selectedId)
            }
            onContinue(capable && selectedId != null)
        },
        secondary = if (capable) StepAction("Skip for now") { onContinue(false) } else null,
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
            StepSectionLabel("Distro and compositor")
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
    progress: Pair<Int, Int>?,
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
    progress: Pair<Int, Int>?,
    onBack: (() -> Unit)?,
    roots: Set<String>,
    reports: Map<String, GamesRootReport.Report>,
    unresolvedFolderWarning: Boolean,
    structureReport: String?,
    onAddFolder: () -> Unit,
    onRemoveRoot: (String) -> Unit,
    onGenerateStructure: (String) -> Unit,
    pathEntry: String,
    pathError: String?,
    onPathEntryChange: (String) -> Unit,
    onAddPath: () -> Unit,
    onContinue: () -> Unit,
) {
    val window = currentShellWindow()
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
                    supporting = report?.let { GamesRootReport.describe(it) } ?: "Looking at this folder.",
                    trailing = {
                        TextButton(
                            onClick = { onRemoveRoot(path) },
                            modifier = Modifier.heightIn(min = window.minTouchTarget),
                        ) { Text("Remove", color = MenuTokens.Danger, style = TypeRole.button) }
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
                    TextButton(
                        onClick = { onGenerateStructure(path) },
                        modifier = Modifier.heightIn(min = window.minTouchTarget),
                    ) { Text("Create the standard folder layout here", style = TypeRole.button) }
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
            placeholder = { Text("/mnt/windows/BstSharedFolder/Games") },
            isError = pathError != null,
            modifier = Modifier.fillMaxWidth().widthIn(max = Measure.bodyMaxWidth),
        )
        if (pathError != null) {
            Text(pathError, color = MenuTokens.Danger, style = TypeRole.supporting)
        }
        TextButton(
            onClick = onAddPath,
            enabled = pathEntry.isNotBlank(),
            modifier = Modifier.heightIn(min = window.minTouchTarget),
        ) { Text("Add this path", style = TypeRole.button) }
    }
}

/**
 * Portrait devices get a theme that was actually laid out for one.
 * Said out loud rather than swapped silently: the person is about to see
 * a different theme from the one the docs and the console screenshots
 * show, and the reason is a property of the theme, not a preference.
 */
@Composable
private fun PortraitThemeStep(
    progress: Pair<Int, Int>?,
    onBack: (() -> Unit)?,
    themeName: String,
    onKeep: () -> Unit,
    onUseLandscapeTheme: () -> Unit,
) {
    var keepPortrait by remember { mutableStateOf(true) }
    val portraitLabel = themeDisplayName(themeName)
    OnboardingScaffold(
        title = "A theme built for a tall screen",
        body = "This screen is taller than it is wide. droidtop's usual theme, " +
            "DEcaffe, only ships landscape layouts, so on a phone it is stretched " +
            "sideways to fit. You can change this any time in Settings.",
        progress = progress,
        onBack = onBack,
        primary = StepAction("Next") { if (keepPortrait) onKeep() else onUseLandscapeTheme() },
    ) {
        SelectableRow(
            title = portraitLabel,
            supporting = "Ships portrait layouts of its own. Recommended on this screen.",
            selected = keepPortrait,
            onClick = { keepPortrait = true },
        )
        SelectableRow(
            title = "DEcaffe",
            supporting = "Landscape only: its layout will be stretched sideways here.",
            selected = !keepPortrait,
            onClick = { keepPortrait = false },
        )
    }
}

/** A theme's display name, never its directory id, in a user-facing string. */
private fun themeDisplayName(directoryId: String): String =
    directoryId.removeSuffix("-es-de")
        .split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

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
    progress: Pair<Int, Int>?,
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
        primary = StepAction(if (active) "Next" else "Skip this", onClick = onContinue),
        secondary = when {
            active -> null
            !enabled -> StepAction("Turn it on in Android", onClick = onEnable)
            else -> StepAction("Switch to it", onClick = onPick)
        },
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
    progress: Pair<Int, Int>?,
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
            "This is what happens when droidtop starts. Everything else you set up " +
                "stays reachable from the mode switcher (long-press Back)."
        },
        progress = progress,
        onBack = onBack,
        primary = StepAction("Next") {
            onSelect(effective)
            onContinue()
        },
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
    progress: Pair<Int, Int>?,
    onBack: (() -> Unit)?,
    mode: dev.droidtop.library.settings.Mode,
    homeImplementation: HomeRolePrefs.HomeImplementation,
    desktopConfigured: Boolean,
    gamingConfigured: Boolean,
    gamesFound: Int,
    storageGranted: Boolean,
    onFinish: () -> Unit,
) {
    val done = buildList {
        when (homeImplementation) {
            HomeRolePrefs.HomeImplementation.STANDARD -> add("Home screen: droidtop's own launcher.")
            HomeRolePrefs.HomeImplementation.ALTERNATIVE -> add("Home screen: the launcher you picked.")
            HomeRolePrefs.HomeImplementation.NONE -> Unit
        }
        if (gamingConfigured) {
            add(
                if (gamesFound > 0) "Gaming: $gamesFound games found in your folders."
                else "Gaming: set up, with no games found yet.",
            )
        }
        if (desktopConfigured) add("Desktop: image chosen, downloaded the first time it starts.")
        add("Opens into ${mode.label}.")
    }
    val skipped = buildList {
        if (homeImplementation == HomeRolePrefs.HomeImplementation.NONE) {
            add("Home screen — Settings, Home screen.")
        }
        if (!gamingConfigured) add("Gaming — Settings, Gaming.")
        if (!desktopConfigured) add("Desktop — Settings, Desktop.")
        if (gamingConfigured && !storageGranted) add("Storage access — Settings, Game folders.")
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
        Spacer(modifier = Modifier.padding(top = Space.Sm))
        StepNote("Every one of these can be changed later; nothing here is final.")
    }
}
