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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemePrefs as LibraryThemePrefs
import dev.droidtop.runtime.BundledImageRepositories
import dev.droidtop.runtime.ImageCatalogRole
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.linux.root.DroidSpacesRuntime
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.HomeRolePrefs
import dev.droidtop.shell.standard.ModePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * droidtop's real first-run flow — onboards the DEVICE, not one mode.
 * Real correction from an earlier draft: this used to force a single mode
 * choice and only ever configured Handheld's games folders. Per direction,
 * it now: (1) asks how the Android home screen itself should work (its own
 * Standard launcher / forward to a different installed launcher via
 * "Alternative" mode / neither), (2) lets the user independently choose to
 * also set up Desktop and/or Handheld, each with its own real setup step,
 * (3) asks which of everything actually configured should be the default
 * when droidtop is launched — configuring and defaulting are separate
 * questions, so a user shouldn't have to visit Settings right after first
 * run just to finish setting up a second mode they also want.
 *
 * Gated by [dev.droidtop.shell.standard.OnboardingGate] from both
 * `:shell-default`'s `LauncherApplication.onCreate()` AND `:app`'s own
 * `MainActivity.onCreate()` — a user who never boots through Standard
 * still needs to see this once.
 *
 * [EXTRA_START_STEP] supports re-entry from Settings (each mode's setup
 * step is independently re-runnable later, not onboarding-only, per
 * direction): when set, onboarding jumps straight to that one step and
 * `finish()`es right after it instead of continuing through the rest of
 * the pipeline.
 */
class OnboardingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startStep = intent.getStringExtra(EXTRA_START_STEP)
            ?.let { name -> OnboardingStep.entries.firstOrNull { it.name == name } }
        setContent {
            dev.droidtop.app.ui.DroidtopTheme {
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
    PORTRAIT_THEME, KEYBOARD, DEFAULT_MODE_CHOICE,
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
    var step by remember { mutableStateOf(startStep ?: OnboardingStep.WELCOME) }
    var configureDesktop by remember { mutableStateOf(false) }
    var configureHandheld by remember { mutableStateOf(false) }
    var unresolvedFolderWarning by remember { mutableStateOf(false) }
    var pathEntry by remember { mutableStateOf("") }
    var pathError by remember { mutableStateOf<String?>(null) }
    var storageAccessGranted by remember { mutableStateOf(hasStorageAccess(context)) }
    var rootsVersion by remember { mutableStateOf(0) }
    val roots = remember(rootsVersion) { GamesRootPrefs.gamesRootPaths(context) }

    val requestStorageAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION doesn't reliably
        // report grant/deny via its own result code -- re-checking the real
        // system state directly is the only trustworthy signal.
        storageAccessGranted = hasStorageAccess(context)
        if (storageAccessGranted) step = OnboardingStep.GAMES_FOLDERS
    }

    // API 26-29: the legacy runtime permission is the whole mechanism.
    val requestLegacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageAccessGranted = granted
        if (granted) step = OnboardingStep.GAMES_FOLDERS
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

    // Advances to the pipeline's next real step after [current] -- or
    // finishes outright when re-entering a single step from Settings.
    fun advanceFrom(current: OnboardingStep) {
        if (isReEntry) {
            onDone()
            return
        }
        step = when (current) {
            OnboardingStep.WELCOME -> OnboardingStep.HOME_CHOICE
            OnboardingStep.HOME_CHOICE -> OnboardingStep.CONFIGURE_MORE
            OnboardingStep.STANDARD_SETUP -> OnboardingStep.CONFIGURE_MORE
            OnboardingStep.ALTERNATIVE_SETUP -> OnboardingStep.CONFIGURE_MORE
            OnboardingStep.CONFIGURE_MORE ->
                if (configureDesktop) OnboardingStep.DESKTOP_SETUP
                else if (configureHandheld) OnboardingStep.STORAGE_PERMISSION
                // Still the keyboard step: it is the one offer that
                // matters whichever mode the user picked.
                else OnboardingStep.KEYBOARD
            OnboardingStep.DESKTOP_SETUP ->
                if (configureHandheld) OnboardingStep.STORAGE_PERMISSION else OnboardingStep.KEYBOARD
            OnboardingStep.STORAGE_PERMISSION -> OnboardingStep.GAMES_FOLDERS
            OnboardingStep.GAMES_FOLDERS ->
                if (portraitThemeSwap != null) OnboardingStep.PORTRAIT_THEME else OnboardingStep.KEYBOARD
            OnboardingStep.PORTRAIT_THEME -> OnboardingStep.KEYBOARD
            OnboardingStep.KEYBOARD -> OnboardingStep.DEFAULT_MODE_CHOICE
            OnboardingStep.DEFAULT_MODE_CHOICE -> OnboardingStep.DEFAULT_MODE_CHOICE
        }
    }

    fun finishOnboarding() {
        GamesRootPrefs.markOnboardingComplete(context)
        onDone()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            // A 48dp gutter is right at TV distance and eats a seventh
            // of a phone screen; the shell has one definition of this.
            modifier = Modifier
                .padding(dev.droidtop.shell.gamepad.currentShellWindow().edgePadding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(onContinue = { advanceFrom(OnboardingStep.WELCOME) })

                OnboardingStep.HOME_CHOICE -> HomeChoiceStep(
                    onStandard = { step = OnboardingStep.STANDARD_SETUP },
                    onAlternative = { step = OnboardingStep.ALTERNATIVE_SETUP },
                    onNeither = {
                        HomeRolePrefs.setActiveHomeImplementation(context, HomeRolePrefs.HomeImplementation.NONE)
                        advanceFrom(OnboardingStep.HOME_CHOICE)
                    },
                )

                OnboardingStep.STANDARD_SETUP -> StandardSetupStep(
                    onContinue = {
                        HomeRolePrefs.setActiveHomeImplementation(context, HomeRolePrefs.HomeImplementation.STANDARD)
                        advanceFrom(OnboardingStep.STANDARD_SETUP)
                    },
                )

                OnboardingStep.ALTERNATIVE_SETUP -> AlternativeSetupStep(
                    onPicked = { component ->
                        HomeRolePrefs.setAlternativeTarget(context, component)
                        HomeRolePrefs.setActiveHomeImplementation(context, HomeRolePrefs.HomeImplementation.ALTERNATIVE)
                        advanceFrom(OnboardingStep.ALTERNATIVE_SETUP)
                    },
                    onBack = { step = OnboardingStep.HOME_CHOICE },
                )

                OnboardingStep.CONFIGURE_MORE -> ConfigureMoreStep(
                    desktopChecked = configureDesktop,
                    handheldChecked = configureHandheld,
                    onDesktopChanged = { configureDesktop = it },
                    onHandheldChanged = { configureHandheld = it },
                    onContinue = { advanceFrom(OnboardingStep.CONFIGURE_MORE) },
                )

                OnboardingStep.DESKTOP_SETUP -> DesktopSetupStep(
                    onContinue = { advanceFrom(OnboardingStep.DESKTOP_SETUP) },
                )

                OnboardingStep.STORAGE_PERMISSION -> StoragePermissionStep(
                    legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.R,
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
                    onSkip = { advanceFrom(OnboardingStep.STORAGE_PERMISSION) },
                )

                OnboardingStep.GAMES_FOLDERS -> GamesFoldersStep(
                    roots = roots,
                    unresolvedFolderWarning = unresolvedFolderWarning,
                    onAddFolder = { pickFolder.launch(null) },
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
                    onDone = { advanceFrom(OnboardingStep.GAMES_FOLDERS) },
                )

                OnboardingStep.PORTRAIT_THEME -> PortraitThemeStep(
                    themeName = portraitThemeSwap.orEmpty(),
                    onKeep = {
                        // Write the resolved default down as a real
                        // choice, so rotating the device later does not
                        // silently move the theme under the user.
                        portraitThemeSwap?.let { LibraryThemePrefs.set(context, it) }
                        advanceFrom(OnboardingStep.PORTRAIT_THEME)
                    },
                    onUseLandscapeTheme = {
                        LibraryThemePrefs.set(context, "decaffe-es-de")
                        advanceFrom(OnboardingStep.PORTRAIT_THEME)
                    },
                )

                OnboardingStep.KEYBOARD -> KeyboardStep(
                    onEnable = {
                        dev.droidtop.library.settings.Keyboards.openSystemSettings(context)
                    },
                    onPick = {
                        dev.droidtop.library.settings.Keyboards.showPicker(context)
                    },
                    onContinue = { advanceFrom(OnboardingStep.KEYBOARD) },
                )

                OnboardingStep.DEFAULT_MODE_CHOICE -> DefaultModeChoiceStep(
                    homeImplementation = HomeRolePrefs.activeHomeImplementation(context),
                    desktopConfigured = configureDesktop,
                    handheldConfigured = configureHandheld,
                    onPicked = { mode ->
                        ModePrefs.setLastMode(context, mode)
                        finishOnboarding()
                    },
                )
            }
        }
    }
}

/**
 * Portrait devices get a theme that was actually laid out for one.
 * Said out loud rather than swapped silently: the user is about to see
 * a different theme from the one the docs and the console screenshots
 * show, and the reason is a property of the theme, not a preference.
 */
@Composable
private fun PortraitThemeStep(themeName: String, onKeep: () -> Unit, onUseLandscapeTheme: () -> Unit) {
    Text("A theme built for a tall screen", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium)
    Text(
        "This screen is taller than it is wide. droidtop's usual theme, DEcaffe, " +
            "only ships landscape layouts, so on a phone it would be stretched sideways " +
            "to fit. $themeName ships portrait layouts of its own, so that is what " +
            "droidtop will use here. You can change it any time in Settings, and " +
            "plugging into a TV or a landscape screen does not change this choice.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onKeep) { Text("Use $themeName") }
    TextButton(onClick = onUseLandscapeTheme) { Text("Use DEcaffe anyway") }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    Text("Welcome to droidtop", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineMedium)
    Text(
        "droidtop turns this device into a real desktop, a gamepad-driven " +
            "library, or your normal Android home screen -- you choose what " +
            "to set up, and you can change any of it later in Settings.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(onClick = onContinue) { Text("Get started") }
}

@Composable
private fun HomeChoiceStep(onStandard: () -> Unit, onAlternative: () -> Unit, onNeither: () -> Unit) {
    Text("Your Android home screen", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    Text(
        "How should the home screen work when you press Home?",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onStandard) { Text("Use droidtop's own launcher") }
    Button(onClick = onAlternative) { Text("Use a different launcher I already have") }
    TextButton(onClick = onNeither) { Text("Neither -- decide later") }
}

@Composable
private fun StandardSetupStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    Text("droidtop's launcher", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    Text(
        "It's a full-featured launcher -- icon packs, grid density, app " +
            "drawer folders, backup/restore, and more all live in Settings " +
            "under Home Screen, App Drawer, and Icons.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(context.packageName, "com.android.launcher3.settings.SettingsActivity")
            putExtra(":settings:fragment", "app.murinelauncher.settings.SettingsHomeFragment")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }) { Text("Customize now") }
    TextButton(onClick = onContinue) { Text("Continue") }
}

@Composable
private fun AlternativeSetupStep(onPicked: (ComponentName) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var launchers by remember { mutableStateOf<List<Pair<ComponentName, String>>?>(null) }

    LaunchedEffect(Unit) {
        launchers = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(homeIntent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .map { info -> ComponentName(info.activityInfo.packageName, info.activityInfo.name) to info.loadLabel(pm).toString() }
                .distinctBy { it.first }
        }
    }

    Text("Pick a launcher", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    Text(
        "droidtop will still handle switching between Desktop and Handheld " +
            "mode -- pressing Home will open whichever launcher you pick here.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    val current = launchers
    if (current == null) {
        Text("Looking for installed launchers…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (current.isEmpty()) {
        Text("No other launcher is installed on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onBack) { Text("Back") }
    } else {
        // A plain Column, not a LazyColumn: every onboarding step is
        // already inside one vertically scrolling Column, and a lazy list
        // nested in that is measured with an infinite maximum height,
        // which Compose throws on -- it took the app down on the Android
        // 9 rig the moment this list had an entry (2026-09-11). Laziness
        // buys nothing here anyway; a device has a handful of launchers.
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            current.forEach { (component, label) ->
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onPicked(component) },
                )
            }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun ConfigureMoreStep(
    desktopChecked: Boolean,
    handheldChecked: Boolean,
    onDesktopChanged: (Boolean) -> Unit,
    onHandheldChanged: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    Text("Anything else to set up?", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    Text(
        "Desktop (Wine/Linux containers) and Handheld (a gamepad-driven " +
            "library) both stay reachable from droidtop's mode switcher " +
            "regardless of what you picked for your home screen.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    LabeledCheckbox("Desktop", desktopChecked, onDesktopChanged)
    LabeledCheckbox("Handheld", handheldChecked, onHandheldChanged)
    Button(onClick = onContinue) { Text("Continue") }
}

@Composable
private fun LabeledCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun DesktopSetupStep(onContinue: () -> Unit) {
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
        // picks an image the user didn't (docs/SPEC.md §3a; the old
        // `?: repositories.firstOrNull()?.id` here was the UI half of the
        // same auto-pick spec violation the session service had).
        selectedId = DesktopSetupPrefs.preferredPrimaryImageId(context)
        // The root state is a reported value, not an exception and not a
        // guess from one failed command (see ContainerRuntimeFactory).
        val rootAccess = withContext(Dispatchers.IO) { ContainerRuntimeFactory.rootAccess() }
        val result = withContext(Dispatchers.IO) {
            // Same backend selection as the real session, not a second
            // hand-built runtime (see ContainerRuntimeFactory).
            when (val runtime = ContainerRuntimeFactory.select(context)) {
                is DroidSpacesRuntime -> runtime.checkSystemRequirements()
                else -> null
            }
        }
        checkResult = result?.succeeded ?: false
        checkMessage = when {
            result == null -> rootAccess.description + " (the no-root desktop backend isn't ready yet)"
            result.succeeded -> "Root access looks good."
            else -> result.stderr.ifBlank { result.stdout }
        }
    }

    Text("Desktop setup", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    when (checkResult) {
        null -> Text("Checking root access…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        true -> Text(checkMessage, color = MaterialTheme.colorScheme.primary)
        false -> Text(
            "Desktop mode needs root (Magisk/KernelSU/APatch): $checkMessage",
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    Text("Which distro + compositor should droidtop use?", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    // heightIn cap: an unconstrained LazyColumn inside the step Column
    // consumed ALL remaining height, pushing Continue/Skip off-screen
    // with no way to scroll to them -- confirmed live on-device (the
    // step was un-completable). Capped, a short list wraps tight and a
    // long one scrolls internally; the buttons always stay on screen.
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(vertical = 8.dp)) {
        items(repositories) { repo ->
            Box(modifier = Modifier.fillMaxWidth().clickable { selectedId = repo.id }) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = repo.id == selectedId, onClick = { selectedId = repo.id })
                    Text("${repo.os} + ${repo.desktopEnvironment}", color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
    // Continue stays disabled until the user actually chose an image --
    // saving a selection they never made would be the auto-pick violation
    // again, just via UI default. Skip is the honest "no choice yet" path
    // (the desktop session fails with guidance until one is made).
    Button(
        enabled = selectedId != null,
        onClick = {
            DesktopSetupPrefs.setPreferredPrimaryImageId(context, selectedId)
            onContinue()
        },
    ) { Text("Continue") }
    TextButton(onClick = onContinue) { Text("Skip for now") }
}

@Composable
private fun StoragePermissionStep(legacy: Boolean, onGrant: () -> Unit, onSkip: () -> Unit) {
    Text("One permission needed", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    Text(
        if (legacy) {
            "droidtop needs storage access to read game files directly " +
                "(including from an SD card) -- a folder picker alone isn't " +
                "enough for that. This version of Android asks for it as an " +
                "ordinary permission prompt."
        } else {
            "droidtop needs full storage access to read game files directly " +
                "(including from an SD card) -- a folder picker alone isn't " +
                "enough for that."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onGrant) { Text("Grant access") }
    TextButton(onClick = onSkip) { Text("Skip -- I'm not using this for games") }
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
    onEnable: () -> Unit,
    onPick: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Re-read on every recomposition: the user leaves for Android's
    // settings and comes back, and the step has to reflect what they did.
    val enabled = dev.droidtop.library.settings.Keyboards.ownKeyboardEnabled(context)
    val active = dev.droidtop.library.settings.Keyboards.ownKeyboardActive(context)

    Text("Keyboard", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    Text(
        dev.droidtop.library.settings.Keyboards.WHY,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    when {
        active -> Text(
            "Hacker's Keyboard is active.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        // An installed-but-not-enabled input method never appears in the
        // picker at all, so enabling has to come first.
        !enabled -> Button(onClick = onEnable) { Text("Turn it on") }
        else -> Button(onClick = onPick) { Text("Switch to it") }
    }
    TextButton(onClick = onContinue) {
        Text(if (active) "Continue" else "Not now -- use my current keyboard")
    }
}

@Composable
private fun GamesFoldersStep(
    roots: Set<String>,
    unresolvedFolderWarning: Boolean,
    onAddFolder: () -> Unit,
    pathEntry: String,
    pathError: String?,
    onPathEntryChange: (String) -> Unit,
    onAddPath: () -> Unit,
    onDone: () -> Unit,
) {
    Text("Game folders", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    Text(
        "Add every folder where your games live -- console ROMs (sorted " +
            "into per-system folders) and Ren'Py/RPG Maker/Kirikiri-style " +
            "engine games alike. An SD card and internal storage both " +
            "work, and you can add more, or change these, later in Settings.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (roots.isNotEmpty()) {
        // Same reason as the launcher list above: a lazy list inside the
        // step's scrolling Column is an infinite-height measure and a
        // crash. This one is how the crash was found -- adding the first
        // games root killed the app on the next frame, so the storage
        // step could be passed but the folders step could never be
        // finished.
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            roots.toList().forEach { path ->
                Text(path, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
    if (unresolvedFolderWarning) {
        Text(
            "That folder couldn't be used directly (this device's storage " +
                "or SD card layout doesn't match what droidtop expects yet, " +
                "or it's a cloud-backed folder).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = onAddFolder) { Text(if (roots.isEmpty()) "Add a folder" else "Add another folder") }

    // The picker can only offer what Android calls a storage volume, and
    // real libraries live outside that set: an emulator's host share
    // (BlueStacks mounts one at /mnt/windows/BstSharedFolder, unreachable
    // from the picker and unmirrored under /sdcard), a mount a rooted
    // device adds itself, a USB drive under /mnt. Typing the path is the
    // way in for all of those, and it is validated for real before it is
    // stored -- see GamesRootPrefs.addGamesRootByPath.
    Text(
        "Somewhere the picker can't reach -- an emulator's shared folder, " +
            "a mount you added yourself? Type its full path.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = pathEntry,
        onValueChange = onPathEntryChange,
        singleLine = true,
        label = { Text("Folder path") },
        placeholder = { Text("/mnt/windows/BstSharedFolder/Games") },
        isError = pathError != null,
        modifier = Modifier.fillMaxWidth(),
    )
    if (pathError != null) {
        Text(pathError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = onAddPath) { Text("Add this path") }

    TextButton(onClick = onDone) { Text(if (roots.isEmpty()) "Skip for now" else "Done") }
    Text(
        "Handheld's whole look is themeable (real ES-DE themes, bundled " +
            "and downloadable) -- pick one any time in Settings > Handheld.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Only modes the user actually configured this run are offered — picking
 * an unconfigured Desktop/Handheld as the default landed straight in that
 * mode's failure/empty screen after onboarding (a real dead end the
 * coherence review flagged). When nothing was configured at all, the one
 * honest option is Handheld (it works unconfigured, showing its own
 * empty-library guidance), labeled as such.
 */
@Composable
private fun DefaultModeChoiceStep(
    homeImplementation: HomeRolePrefs.HomeImplementation,
    desktopConfigured: Boolean,
    handheldConfigured: Boolean,
    onPicked: (String) -> Unit,
) {
    Text("Which should droidtop open into?", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
    Text(
        "This is what happens when you launch droidtop -- everything else " +
            "you set up stays reachable from the mode switcher (long-press Back).",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    val anyConfigured = homeImplementation != HomeRolePrefs.HomeImplementation.NONE || desktopConfigured || handheldConfigured
    if (homeImplementation != HomeRolePrefs.HomeImplementation.NONE) {
        Button(onClick = { onPicked(BackButtonMenu.MODE_STANDARD) }) { Text("My home screen") }
    }
    if (desktopConfigured) {
        Button(onClick = { onPicked(BackButtonMenu.MODE_DESKTOP) }) { Text("Desktop") }
    }
    if (handheldConfigured || !anyConfigured) {
        Button(onClick = { onPicked(BackButtonMenu.MODE_HANDHELD) }) {
            Text(if (handheldConfigured) "Handheld" else "Handheld (set up later in Settings)")
        }
    }
}
