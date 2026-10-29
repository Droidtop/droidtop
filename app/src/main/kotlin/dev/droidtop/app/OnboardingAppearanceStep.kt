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
 * AND the Appearance step is on screen ([stepShown]), so an explicit pick
 * always wins. Finishing anywhere else it is offered by [ThemeOffer] in
 * Gaming ("Your theme is ready: Use it / Later"), never swapped in under
 * a person who has moved on (Droidtop/tracker#532). The clone itself
 * still finishes and shows up as installed in Browse themes either way.
 */
internal object OnboardingThemeDownload {
    const val RECOMMENDED_THEME_DIR = "art-book-next-es-de"
    const val RECOMMENDED_THEME_NAME = "Art Book Next"

    enum class Status { NOT_STARTED, DOWNLOADING, SUCCEEDED, FAILED }

    private val scope = dev.droidtop.runtime.util.AppScopes.io
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
                // Made the active theme only while the person is looking at the Appearance step, where the row
                // changes with it; anywhere else it is offered ("Your theme is ready"), never swapped in under them.
                if (LibraryThemePrefs.get(appContext) == null) {
                    if (stepShown) LibraryThemePrefs.set(appContext, RECOMMENDED_THEME_DIR)
                    else ThemeOffer.offer(appContext, RECOMMENDED_THEME_DIR, RECOMMENDED_THEME_NAME)
                }
                mutableStatus.value = Status.SUCCEEDED
            } else {
                mutableStatus.value = Status.FAILED
            }
        }
    }

    /** Whether the Appearance step is on screen: the only place the download may choose the theme itself. */
    @Volatile
    var stepShown = false
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
internal fun AppearanceStep(
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
        Column(Modifier.fillMaxSize().groundBackground().systemBarsPadding()) {
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
    DisposableEffect(Unit) {
        OnboardingThemeDownload.stepShown = true
        onDispose { OnboardingThemeDownload.stepShown = false }
    }
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
        body = "Pick a look for your games.",
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (isReEntry) "Done" else "Next", onClick = onContinue),
        secondary = StepAction("Download more themes") { browsing = true },
        focusContentFirst = true,
    ) {
        val themes = catalog?.themes
        if (themes != null && themes.isEmpty()) {
            StepNote("No themes installed.")
        }
        // Shown only until Art Book Next has its own row below (either it
        // downloaded, in which case the catalog now lists it, or it never
        // will this run); not a progress bar to wait on, since Next works
        // regardless.
        val recommendedListed = themes?.any { it.name == OnboardingThemeDownload.RECOMMENDED_THEME_DIR } == true
        if (!recommendedListed && downloadStatus == OnboardingThemeDownload.Status.FAILED) {
            StepNote("Couldn't download Art Book Next. Check your connection.")
        }
        themes?.forEach { theme ->
            val hasVertical = theme.hasVertical
            SelectableRow(
                title = theme.displayName,
                supporting = (when {
                    hasVertical && portraitScreen -> "Wide and tall, recommended here"
                    hasVertical -> "Wide and tall"
                    portraitScreen -> "Wide only, stretched here"
                    else -> "Wide only"
                }) + (if (theme.bundled) " · Included" else " · Downloaded"),
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
        // PC games draw as their box art; whether each also says its name under it (owner, 2026-10-17: "A toggle
        // for 'show name under image' would be a good idea. Default to on, or ask in onboarding"; PC Games only,
        // Droidtop/tracker#578). Asked here, on, and changed later in PC Games' List options.
        var namesUnder by remember { mutableStateOf(true) }
        val namesScope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            namesUnder = withContext(Dispatchers.IO) { dev.droidtop.shell.gamepad.query.LibraryViewPrefs.namesUnderImages(context) }
        }
        StepSectionLabel("PC Games")
        listOf(true, false).forEach { on ->
            SelectableRow(
                title = if (on) "Show names under images" else "Images only",
                supporting = if (on) "Each game's name under its art" else "The name shows for the game you're on",
                selected = namesUnder == on,
                leading = { CapsuleNamePreview(on) },
                onClick = {
                    namesUnder = on
                    namesScope.launch(Dispatchers.IO) { dev.droidtop.shell.gamepad.query.LibraryViewPrefs.setNamesUnderImages(context, on) }
                },
            )
        }
    }
}

/** Two PC capsules as the PC Games tab draws them, with or without their names under the art: the choice, shown. */
@Composable
internal fun CapsuleNamePreview(namesUnder: Boolean) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(Space.Xs)) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.Hair)) {
                Box(
                    Modifier
                        .width(28.dp)
                        .height(42.dp)
                        .clip(dev.droidtop.shell.gamepad.Corners.Crisp)
                        .background(MenuTokens.Accent.copy(alpha = 0.55f)),
                )
                if (namesUnder) {
                    Box(Modifier.width(24.dp).height(4.dp).background(MenuTokens.Value))
                    Box(Modifier.width(16.dp).height(4.dp).background(MenuTokens.Value))
                }
            }
        }
    }
}

/** What the Appearance step lists, read once off the main thread. */
internal class AppearanceCatalog(val themes: List<Theme>, val defaultTheme: String?) {
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
