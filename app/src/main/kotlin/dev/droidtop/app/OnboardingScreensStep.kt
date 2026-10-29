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
 * CONTROLLER. droidtop works out which of the pad's buttons confirms
 * instead of asking (docs/SPEC.md 7b, "Console and controller detection"):
 * a known console's own pad from the console table with its layout toggle
 * read live, an external pad from SDL's vendor and product ids, and, only
 * when neither knows, a capture: "press the button labelled A". The step
 * names the attached pad (the shell's own detector,
 * [ControllerLayouts.attachedControllers], which the Quick Menu's status
 * header asks too), says what was concluded and from what, and keeps the
 * button test.
 *
 * Skipping is not an answer: with an unknown pad the forward action reads
 * "Skip this step" until a press has been captured, and a skipped step is
 * listed on the last screen with where to find it again (Quick Menu,
 * "Controller buttons").
 */
/**
 * SCREENS (Droidtop/tracker#598, #776): with two screens connected, which one droidtop's menus are on. No screen is
 * assumed better (docs/SPEC.md 4c, "What each screen is for"): nothing is preselected until the person picks, and Next
 * without a pick leaves the menus where droidtop started. Each screen is listed by its name, built-in or external, and
 * size. Picking another screen answers it Main screen; picking the screen droidtop started on answers the other one
 * Companion. Either way that screen is not asked about again ([ScreenRoles], the same answers Settings > Displays and
 * the Quick Menu's Swap screens change).
 */
@Composable
internal fun ScreensStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val screens by produceState<List<Pair<dev.droidtop.runtime.ScreenFacts, String>>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val outputs = dev.droidtop.runtime.DisplayOutputRepository(context).currentOutputsSnapshot()
            val names = dev.droidtop.runtime.ScreenNaming.names(context, outputs)
            outputs.map { output ->
                val facts = dev.droidtop.runtime.ScreenNaming.facts(output)
                val kind = if (facts.screenClass == dev.droidtop.runtime.ScreenClass.BUILT_IN) "built-in" else "external"
                facts to "${names[facts.displayId] ?: output.name}, $kind, ${facts.nativeWidthPx}x${facts.nativeHeightPx}"
            }
        }
    }
    var picked by remember { mutableStateOf<Int?>(null) }
    OnboardingScaffold(
        title = "Your screens",
        body = "Which screen should droidtop's menus be on? The other one gets the companion.",
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (isReEntry) "Done" else "Next") {
            val choice = picked
            val all = screens.map { it.first }
            if (choice != null) {
                // A preferences write that outlives this step, which closes as Next is pressed.
                kotlin.concurrent.thread(name = "screen-roles") {
                    val home = android.view.Display.DEFAULT_DISPLAY
                    if (choice != home) {
                        all.firstOrNull { it.displayId == choice }?.let { dev.droidtop.runtime.ScreenRoles.set(context, it.uniqueId, dev.droidtop.runtime.ScreenRole.MAIN) }
                    } else {
                        all.firstOrNull { it.displayId != home }?.let { dev.droidtop.runtime.ScreenRoles.set(context, it.uniqueId, dev.droidtop.runtime.ScreenRole.COMPANION) }
                    }
                }
            }
            onContinue()
        },
        focusContentFirst = true,
    ) {
        screens.forEach { (facts, label) ->
            SelectableRow(
                title = label,
                supporting = if (facts.displayId == android.view.Display.DEFAULT_DISPLAY) "Where droidtop is now" else "Plugged in or the second panel",
                selected = picked == facts.displayId,
                onClick = { picked = facts.displayId },
            )
        }
        StepNote("Settings > Displays changes it later, and the Quick Menu can swap them.")
    }
}
