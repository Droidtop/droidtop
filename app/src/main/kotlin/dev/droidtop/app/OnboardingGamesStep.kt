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

/** Where storage access stands, as the Your games step draws it. */
internal data class StorageState(
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
internal fun GamesStep(
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
                "droidtop reads only the folders you choose. Allow file access to look inside them."
            } else {
                "droidtop reads only the folders you choose. Allow file access, turn on " +
                    "\"Allow access to manage all files\", then come back."
            },
            progress = progress,
            onBack = onBack,
            primary = StepAction("Allow file access", onClick = onGrant),
            secondary = StepAction(if (isReEntry) "Done" else "Skip this step", onClick = onContinue),
        ) {
            when {
                storage.permanentlyDenied -> StepNote("Android won't ask again. Allow it in Android's settings for droidtop.")
                storage.denied -> StepNote("Without it, droidtop can't look in your folders.")
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
        body = "Add each folder your games are in.",
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
                        PadButton("Remove", { onRemoveRoot(path) }, color = MenuTokens.NoticeDanger)
                    },
                )
                // ES-DE's three concrete repairs when a folder yields
                // nothing, rather than an empty list and no route out
                // (SPEC 7b, "No games yet"). Choice one is "add a
                // different folder", already on this page.
                if (report != null && report.exists && report.empty) {
                    StepNote("No games found here.")
                    PadButton("Create the standard folder layout here", { onGenerateStructure(path) })
                }
            }
        }

        structureReport?.let { StepNote(it, accent = true) }

        if (unresolvedFolderWarning) {
            StepNote("droidtop can't read that folder directly. Type its path instead.")
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
            OutlinedTextField(
                value = pathEntry,
                onValueChange = onPathEntryChange,
                singleLine = true,
                label = { Text(stringResource(R.string.onboarding_folder_path)) },
                placeholder = { Text(stringResource(R.string.onboarding_a_full_path_starting_with)) },
                isError = pathError != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = Measure.bodyMaxWidth)
                    .onFocusChanged { pathFieldFocused = it.isFocused },
            )
            if (pathError != null) {
                Text(pathError, color = MenuTokens.NoticeDanger, style = TypeRole.supporting)
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
