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
 * DONE. Onboarding ends with a summary and one action into the chosen
 * mode (SPEC 7b, "All done"). With Gaming set up and no folder added it
 * also offers "Add your games", back to the one step that does it, so a
 * person never lands on an empty library with nothing to press
 * (Droidtop/tracker#172).
 */
@Composable
internal fun DoneStep(
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
    controllerFacts: String?,
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
        if (desktopConfigured) add("Desktop: Linux system chosen.")
        controllerFacts?.let { add("Controller: $it.") }
        add("droidtop opens into ${if (defaultMode == Mode.LAUNCHER) "its home screen" else defaultMode.label}.")
    }
    // Each line names the Settings section it lives in.
    val skipped = buildList {
        if (homeChoice != HomeImplementation.NONE && !homeHeld) {
            add("Home button (Home still opens another app): Settings")
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
            skipped.forEach { StepNote("• $it") }
        }
        if (later.isNotEmpty()) {
            StepSectionLabel("Not set up")
            later.forEach { StepNote("• $it") }
        }
    }
}
