package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.animateScrollBy
import dev.droidtop.ui.shared.Motion
import kotlinx.coroutines.launch
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.DeclareLayerHints
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.PadPress
import dev.droidtop.shell.gamepad.input.menuStep
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import dev.droidtop.shell.gamepad.theme.UiSound

/** What the footer says while a [MenuPanel] is open and has no hints of its own to give. */
private val PANEL_HINTS = listOf(HintBinding(GamepadAction.A, "Select"), HintBinding(GamepadAction.B, "Back"))

/**
 * A modal menu panel with focus and the pad handled ONCE, here.
 *
 * A Compose Dialog silently drops key events unless something inside it
 * actually holds focus -- a real bug that shipped in this shell before
 * this existed (the gamelist options overlay ignored every D-pad press
 * on a real device). Surfaces built on this cannot reintroduce it.
 *
 * The panel takes the pad through the input pipeline (docs/SPEC.md 6e):
 * the dialog it sits in gets the pipeline's front ([GatePadInThisDialog]),
 * and [onPad] gets each press once, on the press, with a held direction
 * coming round at the chrome cadence -- so every menu built on this can be
 * held down to run through it, which none could while they acted on the
 * key's release. In the PREVIEW pass, so a row a tap gave focus to cannot
 * take the press first. The system back key is the dialog's own dismiss.
 *
 * The panel is a layer on the footer's hint bar ([DeclareLayerHints]): while
 * it is open the bar shows [hints], not the screen's underneath. A panel
 * that draws a hint row of its own passes an empty list so the two never show.
 *
 * The look and motion are Steam's modal panel (docs/SPEC.md 7k, "Sheets"):
 * [title] in the heading role at the top, the page behind dimmed to the
 * theme's scrim strength (its window's own dim, so nothing is painted
 * outside the panel), the panel gliding in from a touch smaller and
 * transparent over [Motion.PanelInMs], and, while rows remain below the
 * fold, its last [MoreFadeDp] fading out over a down arrow (DroidDeck's
 * AnchoredMenu, ui/SettingsWidgets.kt at 9310d19), so a long sheet says it
 * goes on. The fade is a layer only while there is more to scroll to.
 */
@Composable
fun MenuPanel(
    modifier: Modifier = Modifier,
    focusLabel: String = "Menu",
    hints: List<HintBinding> = PANEL_HINTS,
    title: String? = null,
    onPad: (PadPress) -> Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    GatePadInThisDialog()
    DeclareLayerHints(hints)
    ModalScrim()
    // Every modal shows and hides with its own cue (Steam's modal sounds, docs/SPEC.md "Interface sounds").
    androidx.compose.runtime.DisposableEffect(Unit) {
        EsDeNavigationSounds.play(UiSound.MODAL_SHOW)
        onDispose { EsDeNavigationSounds.play(UiSound.MODAL_HIDE) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, focusLabel) }
    val shown = remember { androidx.compose.animation.core.Animatable(if (Motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.panelIn()) }
    val scroll = androidx.compose.foundation.rememberScrollState()
    // A D-pad press past the rows scrolls the panel by about two rows (#381).
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scrollStepPx = with(androidx.compose.ui.platform.LocalDensity.current) { PANEL_SCROLL_STEP.toPx() }
    // A dialog window does not bound its content to the screen: without
    // an explicit cap a long menu is centred and clipped at both edges
    // (title and last row cut, Droidtop/tracker#295) and its scroll never
    // starts. The cap is the screen less the edge margin each side.
    val window = LocalShellWindow.current
    // A panel is a window of its own (a Dialog), so it hosts its own sliding ring.
    FocusGlideHost(
        modifier
            .heightIn(max = maxOf(120.dp, window.heightDp.dp - window.edgePadding * 2))
            .graphicsLayer {
                val p = shown.value
                alpha = p
                scaleX = PANEL_OPEN_SCALE + (1f - PANEL_OPEN_SCALE) * p
                scaleY = scaleX
            }
            .clip(MenuTokens.OverlayShape)
            .background(MenuTokens.OverlaySurface),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .focusRequester(focus)
                    .focusable()
                    .onPad(preview = true) { press ->
                        MenuRowEnd.atEdge = false
                        val handled = onPad(press)
                        val direction = panelScrollDirection(press.action, MenuRowEnd.atEdge, scroll.canScrollForward, scroll.canScrollBackward)
                        if (direction != 0) {
                            scope.launch { scroll.animateScrollBy(direction * scrollStepPx) }
                            true
                        } else {
                            handled
                        }
                    }
                    .fadeWhileMoreBelow(scroll)
                    // A panel whose content can outgrow the screen must scroll:
                    // the jump-to-letter list reaches 27 rows on a library that
                    // spans the alphabet, which is taller than the display.
                    .verticalScroll(scroll)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(MenuTokens.RowSpacing),
            ) {
                title?.let { MenuPanelTitle(it) }
                content()
            }
            if (scroll.canScrollForward) MoreBelowArrow(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
        }
    }
}

/** How far one D-pad press past the rows scrolls a panel. */
private val PANEL_SCROLL_STEP = 96.dp

/** How small a sheet starts as it glides in: 96 percent of its size. */
private const val PANEL_OPEN_SCALE = 0.96f

/** How tall the fade at the foot of a long sheet is, in dp. */
internal const val MoreFadeDp = 36

/** A sheet's own name at its top: one heading style for every sheet. */
@Composable
internal fun MenuPanelTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MenuTokens.OnSurface,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Dims everything behind the dialog this is drawn in to the theme's scrim
 * strength (Steam's modal scrim is 80 percent; the scrim role carries the
 * same): the window's own dim, so a panel that wraps its content paints
 * nothing outside itself. Outside a dialog it does nothing.
 */
@Composable
internal fun ModalScrim() {
    val dialogWindow = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    val strength = MenuTokens.Scrim.alpha
    androidx.compose.runtime.SideEffect { dialogWindow?.setDimAmount(strength) }
}

/**
 * The foot of a scrolling column fading out while it can still scroll down,
 * so a cut row reads as "more below" rather than as the end. Drawn in the
 * draw phase; the column is composited offscreen only while the fade shows.
 */
internal fun Modifier.fadeWhileMoreBelow(scroll: androidx.compose.foundation.ScrollState): Modifier =
    this
        .graphicsLayer {
            compositingStrategy = if (scroll.canScrollForward) {
                androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            } else {
                androidx.compose.ui.graphics.CompositingStrategy.Auto
            }
        }
        .drawWithContent {
            drawContent()
            if (scroll.canScrollForward) {
                val fade = MoreFadeDp.dp.toPx().coerceAtMost(size.height / 3f)
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startY = size.height - fade,
                        endY = size.height,
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - fade),
                    size = androidx.compose.ui.geometry.Size(size.width, fade),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
        }

/**
 * The ends of a scrolling row fading out on the side it can still scroll to, so a tab or chip cut at the edge reads
 * as "more this way" (Droidtop/tracker#661): a row of tabs never ends mid-letter with nothing to say so. Drawn in the
 * draw phase; the row is composited offscreen only while a fade shows.
 */
fun Modifier.fadeWhileMoreSideways(state: androidx.compose.foundation.lazy.LazyListState): Modifier =
    this
        .graphicsLayer {
            compositingStrategy = if (state.canScrollForward || state.canScrollBackward) {
                androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            } else {
                androidx.compose.ui.graphics.CompositingStrategy.Auto
            }
        }
        .drawWithContent {
            drawContent()
            val fade = MoreFadeDp.dp.toPx().coerceAtMost(size.width / 4f)
            if (state.canScrollForward) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startX = size.width - fade,
                        endX = size.width,
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                    size = androidx.compose.ui.geometry.Size(fade, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
            if (state.canScrollBackward) {
                drawRect(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                    size = androidx.compose.ui.geometry.Size(fade, size.height),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            }
        }

/** The small down chevron under a sheet that goes on below the fold. */
@Composable
private fun MoreBelowArrow(modifier: Modifier = Modifier) {
    val ink = MenuTokens.OnSurfaceMuted
    androidx.compose.foundation.Canvas(modifier.size(20.dp)) {
        val w = 2.dp.toPx()
        val cx = size.width / 2f
        val y0 = size.height * 0.35f
        val y1 = size.height * 0.65f
        drawLine(ink, androidx.compose.ui.geometry.Offset(cx - size.width * 0.3f, y0), androidx.compose.ui.geometry.Offset(cx, y1), w, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(ink, androidx.compose.ui.geometry.Offset(cx + size.width * 0.3f, y0), androidx.compose.ui.geometry.Offset(cx, y1), w, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/**
 * Up or Down on a menu's cursor: ES-DE's menus stop at both ends
 * ([menuStep]); a move that happens plays the move cue, and a fresh press
 * at an end that goes nowhere plays the bump cue ([moveCue]).
 */
fun menuMove(index: Int, count: Int, press: PadPress): Int {
    val next = menuStep(index, count, if (press.action == GamepadAction.UP) -1 else 1)
    moveCue(next != index, press.repeat)
    // The panel this menu is in scrolls on a press that went nowhere (MenuPanel, Droidtop/tracker#381).
    MenuRowEnd.atEdge = next == index
    return next
}

/**
 * Whether the last cursor step ([menuMove]) went nowhere: the rows ended. [MenuPanel] reads it after its menu's
 * handler, to scroll content past the rows (an action's result) with the D-pad. Main thread only, like the pad.
 */
internal object MenuRowEnd {
    var atEdge: Boolean = false
}

/**
 * Which way a panel scrolls for a press (Droidtop/tracker#381): +1 down, -1 up, 0 not at all. Only Up or Down that
 * went nowhere among the rows ([atEdge]) scrolls, and only where there is more that way, so a menu whose rows all
 * fit keeps its bump and a long result below the rows is reached with the D-pad, not only a touch swipe.
 */
fun panelScrollDirection(action: GamepadAction, atEdge: Boolean, canForward: Boolean, canBackward: Boolean): Int = when {
    !atEdge -> 0
    action == GamepadAction.DOWN && canForward -> 1
    action == GamepadAction.UP && canBackward -> -1
    else -> 0
}

/**
 * The one sound rule for a cursor step (docs/SPEC.md "Interface sounds"): [moved], the move cue;
 * not moved, the bump cue (Steam's "a press that went nowhere"), but only for a fresh press, so a
 * direction held against an end does not keep bumping.
 */
fun moveCue(moved: Boolean, repeat: Boolean) {
    when {
        moved -> EsDeNavigationSounds.play(UiSound.MOVE)
        !repeat -> EsDeNavigationSounds.play(UiSound.BUMP)
    }
}
