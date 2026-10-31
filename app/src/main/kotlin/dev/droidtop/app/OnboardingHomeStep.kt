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
internal fun HomeStep(
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
            supporting = null,
            selected = choice == HomeImplementation.NONE,
            onClick = { onSelect(HomeImplementation.NONE, false, null) },
        )
        SelectableRow(
            title = "droidtop",
            supporting = "Home opens $mode. Android asks you to confirm.",
            selected = choice == HomeImplementation.DROIDTOP && !showsHomeScreen,
            onClick = { onSelect(HomeImplementation.DROIDTOP, false, null) },
        )
        SelectableRow(
            title = "droidtop's home screen",
            supporting = "Its own app drawer and widgets",
            selected = choice == HomeImplementation.DROIDTOP && showsHomeScreen,
            onClick = { onSelect(HomeImplementation.DROIDTOP, true, null) },
        )
        launchers?.forEach { launcher ->
            SelectableRow(
                title = launcher.label,
                supporting = null,
                selected = choice == HomeImplementation.ALTERNATIVE && alternative == launcher.component,
                icon = launcher.icon,
                onClick = { onSelect(HomeImplementation.ALTERNATIVE, true, launcher.component) },
            )
        }
    }
}

internal class InstalledLauncher(val component: ComponentName, val label: String, val icon: android.graphics.drawable.Drawable?)

/**
 * Every other installed home activity, with its application label (never a
 * class name, never a label that names nothing: the host launcher's
 * activity label on the rig was the single word "Home") and its icon.
 * Android's own placeholder home (Settings' FallbackHome, priority -1000)
 * answers the HOME query but is not a launcher.
 */
internal fun installedLaunchers(context: Context): List<InstalledLauncher> {
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
