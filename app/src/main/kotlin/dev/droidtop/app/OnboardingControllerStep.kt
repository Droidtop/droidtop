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
internal fun ControllerStep(
    progress: StepProgress?,
    onBack: (() -> Unit)?,
    isReEntry: Boolean,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val controllers = remember { ControllerLayouts.attachedControllers() }
    LaunchedEffect(Unit) { ControllerLayouts.attach(context) }
    // Read in composition: the layout is snapshot state, so this step redraws when it resolves or changes.
    val layout = ControllerLayouts.layout
    val ready = ControllerLayouts.ready
    var recapturing by remember { mutableStateOf(false) }
    val capturing = ready && controllers.isNotEmpty() && (recapturing || !layout.known)
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
            controllers.isEmpty() -> "No controller connected."
            !ready -> names
            layout.known -> "$names: ${controllerFacts(layout)}."
            else -> "$names: which button confirms?"
        },
        progress = progress,
        onBack = onBack,
        primary = StepAction(if (isReEntry) "Done" else if (layout.known || controllers.isEmpty()) "Next" else "Skip this step") {
            onContinue()
        },
        focusContentFirst = true,
    ) {
        if (capturing) {
            StepSectionLabel("Press the button labelled A")
            // A real key event, caught where it lands, read BEFORE the layout's swap: the press of the
            // button the person calls A is what tells droidtop how this pad reports it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = currentShellWindow().minTouchTarget + Space.Lg)
                    .background(MenuTokens.Surface, MenuTokens.RowShape)
                    .focusable()
                    .onKeyEvent { event ->
                        ControllerLayouts.handleCaptureKey(context, event.nativeKeyEvent) { recapturing = false }
                    }
                    .padding(Space.Lg),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Move here with the D-pad, then press the button labelled A.",
                    color = MenuTokens.OnSurfaceMuted,
                    style = TypeRole.supporting,
                )
            }
        } else if (ready && controllers.isNotEmpty()) {
            SelectableRow(
                title = "Not right? Press the button marked A",
                selected = false,
                onClick = { recapturing = true },
            )
        }

        // Whether HOME + R2, L2 and Start work over games, and the one press that makes them (Droidtop/tracker#740).
        StepSectionLabel("HOME button combos")
        val scope = rememberCoroutineScope()
        var chords by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
        var chordsTick by remember { mutableStateOf(0) }
        // Read again when the step comes back to the front: the person may have turned it on in Android's list.
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) chordsTick++
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(chordsTick) {
            chords = withContext(Dispatchers.IO) { dev.droidtop.runtime.keyboard.AccessibilityKeyboard.homeChords(context) }
        }
        chords?.let { (on, line) ->
            SelectableRow(
                title = if (on) "HOME button combos: on" else "HOME button combos: off",
                supporting = line,
                selected = false,
                onClick = if (on) null else ({
                    scope.launch {
                        withContext(Dispatchers.IO) { dev.droidtop.runtime.keyboard.AccessibilityKeyboard.turnOn(context) }
                        chordsTick++
                    }
                }),
            )
        }

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
                    !testing && pressed == null -> "Move here with the D-pad, then press any button."
                    !testing -> "Read: $pressed"
                    pressed == null -> "Press any button, B too."
                    else -> "Read: $pressed"
                },
                color = if (pressed != null) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
                style = TypeRole.supporting,
            )
        }
    }
}

/** What the resolver concluded, as the two facts a person acts on: "A (the bottom button) confirms and B goes back". */
internal fun controllerFacts(layout: FaceLayout): String {
    val where = if (layout.positionOf(FaceRole.CONFIRM) == FacePosition.RIGHT) "the right button" else "the bottom button"
    return "${layout.glyph(FaceRole.CONFIRM)} ($where) confirms and ${layout.glyph(FaceRole.CANCEL)} goes back"
}
