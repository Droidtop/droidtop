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

@Composable
internal fun DesktopSetupStep(
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
        checkMessage = desktopCheckMessage(result.succeeded, rooted = runtime is DroidSpacesRuntime, error = result.stderr)
    }

    val capable = checkResult == true
    OnboardingScaffold(
        title = "Desktop setup",
        body = "Choose a Linux system.",
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
            null -> StepNote("Checking this device.")
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
                    supporting = if (repo.officialSource) "Official" else "Community build",
                    selected = repo.id == selectedId,
                    onClick = { selectedId = repo.id },
                )
            }
        }
    }
}
