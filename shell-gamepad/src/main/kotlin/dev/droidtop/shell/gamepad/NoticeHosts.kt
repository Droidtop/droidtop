package dev.droidtop.shell.gamepad

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import dev.droidtop.library.settings.ConfirmAnswer
import dev.droidtop.library.settings.NoticeDialogs
import dev.droidtop.library.settings.Notices
import dev.droidtop.ui.shared.Motion
import dev.droidtop.shell.gamepad.input.DeclareLayerHints
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.ui.shared.ConfirmDialogCard
import dev.droidtop.ui.shared.FlashCard
import kotlinx.coroutines.delay

/**
 * Where droidtop's own danger and confirm dialogs are drawn (docs/SPEC.md 7k, "Notices"): placed once in a
 * surface, it attaches as a host of [NoticeDialogs] while composed, and the host attached last draws the open
 * question. Steam's shape: centred, Cancel on the left with the focus, the risky button on the right. Left and
 * Right move between the two buttons, Up reaches Details, A presses, B (or a tap on the scrim) cancels, Y opens
 * Details. Anything but a deliberate press of the proceed button is Cancel, so a mashed A cancels.
 */
@Composable
fun NoticeDialogHost() {
    val token = remember { Any() }
    DisposableEffect(token) {
        NoticeDialogs.attachHost(token)
        onDispose { NoticeDialogs.detachHost(token) }
    }
    val hosts by NoticeDialogs.hostTokens.collectAsState()
    val request by NoticeDialogs.pending.collectAsState()
    val open = request ?: return
    if (hosts.lastOrNull() !== token) return
    // A question asked while the shell is not in front waits for it: its window would have no live token.
    if (!windowAllowed()) return
    NoticeDialogWindow(open)
}

/**
 * Whether a host may add a window now (Droidtop/tracker#410): only while its activity is at least started. A
 * notice's Popup or Dialog is driven by global state ([Notices], [NoticeDialogs]), so without this it could be
 * composed while the activity is stopping or gone, and adding its window then throws
 * `WindowManager$BadTokenException` (crash-267, console, v0.2.0-dev.1649).
 */
internal fun canAddWindow(state: androidx.lifecycle.Lifecycle.State): Boolean =
    state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

@Composable
private fun windowAllowed(): Boolean {
    val state by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return canAddWindow(state)
}

@Composable
private fun NoticeDialogWindow(request: NoticeDialogs.Request) {
    var focused by remember(request) { mutableStateOf(0) }
    var detailsOpen by remember(request) { mutableStateOf(false) }
    val hasDetails = !request.notice.details.isNullOrBlank()
    val cancel = { NoticeDialogs.answer(request, ConfirmAnswer.Cancel) }
    val proceed = { NoticeDialogs.answer(request, ConfirmAnswer.Proceed) }
    ShellDialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GatePadInThisDialog()
        val hints = remember(hasDetails) {
            listOfNotNull(
                HintBinding(GamepadAction.A, "Select"),
                HintBinding(GamepadAction.B, "Cancel"),
                HintBinding(GamepadAction.Y, "Info").takeIf { hasDetails },
            )
        }
        DeclareLayerHints(hints)
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { dialogWindow?.setDimAmount(SCRIM) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Notice dialog") }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                // A tap on the scrim is Cancel.
                .pointerInput(request) { detectTapGestures { cancel() } }
                .focusRequester(focus)
                .focusable()
                .onPad { press ->
                    when (press.action) {
                        GamepadAction.LEFT -> if (focused == 1) focused = 0
                        GamepadAction.RIGHT -> if (focused == 0) focused = 1
                        GamepadAction.UP -> if (hasDetails) focused = 2
                        GamepadAction.DOWN -> if (focused == 2) focused = 0
                        GamepadAction.A -> when (focused) {
                            0 -> cancel()
                            1 -> proceed()
                            else -> detailsOpen = !detailsOpen
                        }
                        GamepadAction.B -> cancel()
                        GamepadAction.Y -> if (hasDetails) detailsOpen = !detailsOpen
                        else -> return@onPad false
                    }
                    true
                },
        ) {
            // The card takes its own taps, so a tap on it is never the scrim's Cancel.
            Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) {
                ConfirmDialogCard(
                    request = request,
                    focused = focused,
                    detailsOpen = detailsOpen,
                    surface = MenuTokens.OverlaySurface,
                    onSurface = MenuTokens.OnSurface,
                    neutralButton = MenuTokens.SurfaceSelected,
                    onCancel = cancel,
                    onProceed = proceed,
                    onToggleDetails = { detailsOpen = !detailsOpen },
                    focusModifier = { shown -> Modifier.focusRing(shown && PadModality.showsFocus, Corners.Crisp) },
                )
            }
        }
    }
}

/**
 * Where [Notices.flash] is drawn (docs/SPEC.md 7k, "Notices"): a corner card at the bottom right, above the hint
 * row, in a window of its own that never takes focus, so the page's focus and its hints stay as they were. Each
 * flash stays for the longer of 4 s and the time Android's accessibility settings ask for, and never less than
 * 1.5 s; one at a time, oldest first. It is announced politely.
 */
@Composable
internal fun NoticeFlashHost() {
    // Attached while the shell is started: a flash raised while it is not (a plugin during a game) is a platform toast.
    val token = remember { Any() }
    androidx.lifecycle.compose.LifecycleStartEffect(token) {
        Notices.attachFlashHost(token)
        onStopOrDispose { Notices.detachFlashHost(token) }
    }
    val flashes by Notices.flashes.collectAsState()
    // A press on a card shows its details in place, over the page (Droidtop/tracker#643: "Details on press, through
    // facets in place (no redirects)"): the job's page, or Downloads for a counted card.
    var details by remember { mutableStateOf<dev.droidtop.library.settings.CatalogScreen?>(null) }
    details?.let { screen -> CatalogSheet(root = screen, onExit = { details = null }) }
    val flash = flashes.firstOrNull() ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    // A sheet or a menu is a window of its own above the shell's: a card drawn in the shell's window would sit under
    // it, so while one is open the flash is Android's own toast, which is drawn above every window.
    val layerOpen = dev.droidtop.shell.gamepad.input.LocalFocusedHints.current?.layerOpen == true
    // The card slides in from the edge and out again on the panel roles; with motion off it is simply there and gone.
    val shown = remember(flash.id) { androidx.compose.animation.core.Animatable(if (Motion.enabled) 0f else 1f) }
    LaunchedEffect(flash.id) {
        dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds.play(dev.droidtop.shell.gamepad.theme.UiSound.TOAST)
        if (!layerOpen) shown.animateTo(1f, Motion.panelIn())
    }
    // Keyed by the count too: a card that counts up stays its full time from the last count.
    LaunchedEffect(flash.id, flash.count) {
        if (layerOpen) {
            Notices.platformToast(context, flash)
            Notices.shown(flash)
            return@LaunchedEffect
        }
        delay(flashMillis(context))
        shown.animateTo(0f, Motion.panelOut())
        Notices.shown(flash)
    }
    if (layerOpen) return
    // Not while the shell is stopping or gone (#410): the flash is then marked shown by its timer only.
    if (!windowAllowed()) return
    val fontScale = LocalDensity.current.fontScale
    val offset = with(density) { IntOffset(-16.dp.roundToPx(), -(MenuTokens.HintBarRoom + 8.dp).roundToPx()) }
    val slide = with(density) { FLASH_SLIDE.toPx() }
    Popup(alignment = Alignment.BottomEnd, offset = offset) {
        FlashCard(
            text = flash.text,
            tone = flash.tone,
            source = flash.source,
            surface = MenuTokens.OverlaySurface,
            onSurface = MenuTokens.OnSurface,
            maxLines = if (fontScale >= LARGE_FONT_SCALE) 4 else 2,
            mark = flash.mark,
            count = flash.count,
            onPress = flash.opens?.let { open ->
                {
                    details = open()
                    Notices.shown(flash)
                }
            },
            modifier = Modifier
                .graphicsLayer {
                    alpha = shown.value
                    translationX = (1f - shown.value) * slide
                }
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** How long a flash stays: 4 s, or longer when Android's accessibility timeout asks for it, never under 1.5 s. */
internal fun flashMillis(context: android.content.Context): Long {
    val manager = context.getSystemService(AccessibilityManager::class.java)
    val recommended = if (android.os.Build.VERSION.SDK_INT >= 29 && manager != null) {
        manager.getRecommendedTimeoutMillis(FLASH_MS.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT).toLong()
    } else {
        FLASH_MS
    }
    return maxOf(FLASH_MS, recommended, FLASH_MIN_MS)
}

private const val SCRIM = 0.6f
private const val FLASH_MS = 4_000L
private const val FLASH_MIN_MS = 1_500L

/** How far a flash slides in from, toward the screen's edge. */
private val FLASH_SLIDE = 48.dp
private const val LARGE_FONT_SCALE = 1.3f
