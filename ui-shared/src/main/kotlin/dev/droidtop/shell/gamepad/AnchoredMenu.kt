package dev.droidtop.shell.gamepad

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.droidtop.shell.gamepad.input.HideSystemBarsInThisDialog
import kotlin.math.roundToInt

/**
 * Where an item is on the screen, in pixels ([left], [top], [right], [bottom]), so an [AnchoredMenu] can open beside
 * it. Screen pixels rather than a window's, because the menu is a window of its own and the two need not start at the
 * same corner. Recorded by [anchorFor].
 */
data class AnchorRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** The holder [anchorFor] records an item's bounds into; null until the item has been laid out. */
@Composable
fun rememberAnchor(): MutableState<AnchorRect?> = remember { mutableStateOf(null) }

/** Records this node's bounds on the screen into [state] whenever it moves, for an [AnchoredMenu] to open beside. */
@Composable
fun Modifier.anchorFor(state: MutableState<AnchorRect?>): Modifier {
    val view = LocalView.current
    return this.onGloballyPositioned { state.value = screenBounds(it, view) }
}

/** [coords]' bounds on the screen, [view] being any view of its window. */
private fun screenBounds(coords: LayoutCoordinates, view: View): AnchorRect {
    val at = IntArray(2)
    view.rootView.getLocationOnScreen(at)
    val r = coords.boundsInWindow()
    return AnchorRect(
        left = at[0] + r.left.roundToInt(),
        top = at[1] + r.top.roundToInt(),
        right = at[0] + r.right.roundToInt(),
        bottom = at[1] + r.bottom.roundToInt(),
    )
}

/**
 * The anchored menu of the one component set (docs/DESIGN-LANGUAGE.md, "Overlays and depth": "beside the item, up to
 * 8 rows, L2"; Droidtop/tracker#827): an item's own menu, opened beside the item rather than in the middle of the
 * screen. It is the [ActionSheet]'s body, the same rows and pad handling ([ActionSheetBody]): Up and Down move, A
 * chooses, B goes to [onBack], Select or L2 closes ([onDismiss]).
 *
 * Placement ([anchoredMenuPosition]): below [anchor] and start-aligned with it; above it when there is no room below;
 * held inside the window by the window's edge gutter. Its height is capped at about eight rows
 * ([ANCHORED_MENU_ROWS]) and it scrolls beyond them. A tap anywhere outside the panel closes it ([onDismiss]).
 *
 * With [anchor] null it is the centred [ActionSheet]. Inside an in-game overlay ([LocalInGameOverlay]) it is centred
 * too and the anchor is ignored: [ShellDialog] is a popup filling the overlay's window there, whose coordinates are
 * not the shell's.
 */
@Composable
fun AnchoredMenu(
    anchor: AnchorRect?,
    title: String,
    rows: List<ActionSheetItem>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onBack: () -> Unit = onDismiss,
    focus: MutableIntState = remember(rows.size) { mutableIntStateOf(0) },
    width: Dp = if (anchor == null) 480.dp else ANCHORED_MENU_WIDTH,
    focusLabel: String = title,
) {
    val window = LocalShellWindow.current
    val at = anchor?.takeIf { !LocalInGameOverlay.current }
    if (at == null) {
        ShellDialog(onDismissRequest = onBack) {
            HideSystemBarsInThisDialog()
            ActionSheetBody(
                title = title,
                rows = rows,
                onDismiss = onDismiss,
                subtitle = subtitle,
                onBack = onBack,
                focus = focus,
                focusLabel = focusLabel,
                modifier = Modifier.width(window.panelWidth(width)),
            )
        }
        return
    }
    ShellDialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        HideSystemBarsInThisDialog()
        val view = LocalView.current
        val density = LocalDensity.current
        val marginPx = with(density) { window.edgePadding.roundToPx() }
        val gapPx = with(density) { ANCHORED_MENU_GAP.roundToPx() }
        // About eight rows: the title, eight one-line rows at the press target with the spacing between them, and the
        // panel's own padding. A longer menu scrolls (MenuPanel).
        val maxHeight =
            MENU_TITLE_ROOM + (window.pressTarget + MenuTokens.RowSpacing) * ANCHORED_MENU_ROWS + MENU_PADDING_ROOM
        // Where this dialog's content starts on the screen, so the anchor can be read in its own pixels.
        var origin by remember { mutableStateOf(IntOffset.Zero) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = screenBounds(it, view).let { b -> IntOffset(b.left, b.top) } },
        ) {
            // Everything outside the panel: a tap there closes the menu.
            Box(Modifier.fillMaxSize().padRow(silent = true) { onDismiss() })
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    // One child to place, whatever the panel is made of.
                    Box {
                        ActionSheetBody(
                            title = title,
                            rows = rows,
                            onDismiss = onDismiss,
                            subtitle = subtitle,
                            onBack = onBack,
                            focus = focus,
                            focusLabel = focusLabel,
                            modifier = Modifier.width(window.panelWidth(width)).heightIn(max = maxHeight),
                        )
                    }
                },
            ) { measurables, constraints ->
                val panel = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
                val local = AnchorRect(at.left - origin.x, at.top - origin.y, at.right - origin.x, at.bottom - origin.y)
                val position = anchoredMenuPosition(
                    anchor = local,
                    panelWidth = panel.width,
                    panelHeight = panel.height,
                    windowWidth = constraints.maxWidth,
                    windowHeight = constraints.maxHeight,
                    margin = marginPx,
                    gap = gapPx,
                    rtl = layoutDirection == LayoutDirection.Rtl,
                )
                layout(constraints.maxWidth, constraints.maxHeight) { panel.place(position) }
            }
        }
    }
}

/**
 * Where an anchored menu's panel goes (its top-left corner) in a window [windowWidth] by [windowHeight], all in
 * pixels: start-aligned with [anchor] ([rtl] aligns the right edges), [gap] below it, or [gap] above it when it does
 * not fit below; when it fits on neither side, on the side with more room. Then held inside the window by [margin] on
 * every edge, so a panel near a corner moves in rather than off the screen; a panel larger than the room left starts
 * at the margin. Pure.
 */
internal fun anchoredMenuPosition(
    anchor: AnchorRect,
    panelWidth: Int,
    panelHeight: Int,
    windowWidth: Int,
    windowHeight: Int,
    margin: Int,
    gap: Int = 0,
    rtl: Boolean = false,
): IntOffset {
    val below = anchor.bottom + gap
    val above = anchor.top - gap - panelHeight
    val fitsBelow = below + panelHeight <= windowHeight - margin
    val fitsAbove = above >= margin
    val y = when {
        fitsBelow -> below
        fitsAbove -> above
        windowHeight - anchor.bottom >= anchor.top -> below
        else -> above
    }
    val x = if (rtl) anchor.right - panelWidth else anchor.left
    return IntOffset(
        x = holdInside(x, panelWidth, windowWidth, margin),
        y = holdInside(y, panelHeight, windowHeight, margin),
    )
}

/** [start] moved so [size] from it stays [margin] inside [extent]; [margin] when it cannot. */
private fun holdInside(start: Int, size: Int, extent: Int, margin: Int): Int =
    maxOf(margin, minOf(start, extent - margin - size))

/** How many rows an anchored menu shows before it scrolls (docs/DESIGN-LANGUAGE.md: "up to 8 rows"). */
internal const val ANCHORED_MENU_ROWS = 8

/** How wide an anchored menu is: narrower than a centred sheet, as it stands beside one item. */
private val ANCHORED_MENU_WIDTH = 360.dp

/** The space between the item and its menu. */
private val ANCHORED_MENU_GAP = 8.dp

/** The room a menu's title takes above its rows. */
private val MENU_TITLE_ROOM = 40.dp

/** The panel's own padding above and below its column ([MenuPanel]'s 20dp each side). */
private val MENU_PADDING_ROOM = 40.dp
