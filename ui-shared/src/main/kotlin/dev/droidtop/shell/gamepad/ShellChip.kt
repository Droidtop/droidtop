package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.droidtop.ui.shared.Motion
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.input.onPad

/**
 * The shell's one chip and button: a pill that is focusable for the pad
 * and clickable for touch. [on] is a filter or toggle in effect: it is
 * filled with the accent and carries a check, so which filters are on reads
 * without moving onto them (UI pass 2026-09-24, L3). [primary] is the one
 * action a row of chips leads with (Launch, Save). Focus is the window's
 * one ring, as on every other piece of chrome.
 *
 * The look is Steam's buttons in the theme's colours (docs/SPEC.md 7k): a
 * quiet chip at rest turns solid when selected ([MenuTokens.Selected] with
 * its own ink, Steam's inversion); a filled one keeps its fill and gains
 * the ring, a shadow that deepens when selected and a one-shot sheen as the cursor arrives. [large] is the page's Play: the
 * theme's launch colour, 48dp tall and 160dp wide at least, crisp corners
 * and a slower stripe. [tab] is a tab or view pill on a strip (Steam's
 * tabs): the label small, bold and uppercase, padded 6 by 16; the chosen
 * one ([on]) sits on a quiet plate instead of the accent, and the one under
 * the cursor turns solid, as every quiet chip does.
 *
 * This replaces three private copies (the detail screen's action chip,
 * the recent filter and the PC surface's filter chip), each of which drew
 * focus its own way.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ShellChip(
    label: String,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    primary: Boolean = false,
    // The one big button of a page (the PC game page's Play).
    large: Boolean = false,
    // A tab or view pill on a strip (the PC Games view strip, a page's tabs).
    tab: Boolean = false,
    // A primary action that cannot be pressed right now is still drawn,
    // faded, so the page says what it would do and why not (design
    // language: "a disabled button that says why").
    enabled: Boolean = true,
    // Non-null from a screen that moves ONE cursor of its own through its
    // one `onPad` handler (the PC Games tab and page, docs/SPEC.md 7i): the
    // chip then takes no focus and no key of its own and draws the ring
    // when the caller says it is the selected one. Null is the chip
    // driving itself through Compose focus, as every other caller uses it.
    selected: Boolean? = null,
    // The chip's own menu on a long press, the one L2 opens on it (Droidtop/tracker#621); null for none.
    onLongClick: (() -> Unit)? = null,
    // False where the strip draws the chosen tab's plate itself, gliding between tabs ([ViewStrip]).
    plate: Boolean = true,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = if (large) Corners.Crisp else Corners.Pill
    val press = if (onLongClick != null) Modifier.padRow(onClick = onClick, onLongClick = onLongClick) else Modifier.padRow(onClick = onClick)
    // A chosen tab is a place on the strip, not a filter in effect: it keeps the quiet look.
    val filled = (on && !tab) || primary
    val isSelected = selected ?: focused
    val ring = isSelected && PadModality.showsFocus
    val labelColor = when {
        !enabled -> MenuTokens.OnSurfaceDisabled
        // The launch fill is held to contrast against the text ink (GamingThemeMapping).
        large && filled -> MenuTokens.OnSurface
        filled || ring -> MenuTokens.OnSelected
        else -> MenuTokens.OnSurface
    }
    Text(
        when {
            tab -> label.labelCase()
            on -> "\u2713 $label"
            else -> label
        },
        color = labelColor,
        style = when {
            large -> MaterialTheme.typography.titleMedium
            tab -> TypeRole.tabLabel
            else -> MaterialTheme.typography.labelLarge
        },
        fontWeight = if (large || tab) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        // A chip held to a width (the strip's filter pill) shortens its own text, never wraps.
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = modifier
            .then(
                if (selected == null) {
                    Modifier
                        // Ahead of the focus targets, not after them: see [GameCard].
                        .onPad { press ->
                            if (press.action == GamepadAction.A) {
                                onClick()
                                true
                            } else {
                                false
                            }
                        }
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .then(press)
                } else {
                    // The caller's cursor is the selection; a tap is still the press ([padRow]).
                    press
                },
            )
            .then(
                if (filled) {
                    val fill = when {
                        !enabled -> MenuTokens.LaunchDisabled
                        large -> if (ring) MenuTokens.LaunchFocused else MenuTokens.Launch
                        ring -> MenuTokens.Selected
                        else -> MenuTokens.Accent
                    }
                    Modifier
                        .primaryLift(ring && enabled, shape)
                        .background(fill, shape)
                        .shine(ring && enabled, play = large)
                        .focusRing(ring, shape)
                } else {
                    Modifier.selectionFrame(
                        isSelected,
                        shape,
                        rest = when {
                            !tab -> MenuTokens.Surface
                            on && plate -> MenuTokens.SurfaceSelected
                            else -> Color.Transparent
                        },
                        selectedFill = MenuTokens.Selected,
                    )
                },
            )
            .focusMarquee(isSelected)
            .then(if (large) Modifier.heightIn(min = 48.dp).widthIn(min = 160.dp) else Modifier)
            .padding(
                horizontal = if (large) 24.dp else 16.dp,
                vertical = when {
                    large -> 14.dp
                    tab -> 6.dp
                    else -> 8.dp
                },
            ),
    )
}

/**
 * The primary action's lift: a black shadow that deepens while the cursor
 * is on it ([Elevation]), read in the layer phase only.
 */
private fun Modifier.primaryLift(selected: Boolean, shape: Shape): Modifier = composed {
    val p = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (selected) Motion.lift<Float>() else Motion.release<Float>(),
        label = "primary lift",
    )
    graphicsLayer {
        val rest = Elevation.PrimaryRest.toPx()
        shadowElevation = rest + (Elevation.PrimaryFocused.toPx() - rest) * p.value
        this.shape = shape
        clip = false
    }
}
