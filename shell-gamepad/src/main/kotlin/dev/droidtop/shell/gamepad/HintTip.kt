package dev.droidtop.shell.gamepad

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay

/**
 * The app's one tooltip (docs/SPEC.md "Copy: labels and values, never prose"). Wraps [content] and shows
 * [text] in a small bubble below it: on a long-press (touch, gone after a few seconds), after the pointer
 * rests on it (mouse), or after focus has stayed on it or a child for a moment (pad). A null [text] draws
 * [content] alone, so a caller can pass the explanation only when there is one. Explanations never appear
 * as visible text on a screen; they live here.
 *
 * [shown] is for a caller whose selection is a cursor of its own rather than Compose focus (a settings
 * row, docs/SPEC.md "Settings layout"): when it is given, the bubble follows it alone, after the same
 * delay, and this wrapper adds no hover or long-press handling of its own, because the caller's row
 * already owns its touches (its long press opens the Info sheet).
 */
@Composable
fun HintTip(text: String?, modifier: Modifier = Modifier, shown: Boolean? = null, content: @Composable () -> Unit) {
    if (text == null) {
        Box(modifier = modifier) { content() }
        return
    }
    if (shown != null) {
        var settled by remember { mutableStateOf(false) }
        LaunchedEffect(shown) {
            settled = false
            if (shown) {
                delay(TIP_DELAY_MS)
                settled = true
            }
        }
        Box(modifier = modifier) {
            content()
            if (shown && settled) TipBubble(text)
        }
        return
    }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, focused) {
        settled = false
        if (hovered || focused) {
            delay(TIP_DELAY_MS)
            settled = true
        }
    }
    LaunchedEffect(touched) {
        if (touched) {
            delay(TIP_TOUCH_MS)
            touched = false
        }
    }
    Box(
        modifier = modifier
            .hoverable(source)
            .onFocusChanged { focused = it.hasFocus }
            .pointerInput(Unit) { detectTapGestures(onLongPress = { touched = true }) },
    ) {
        content()
        if (touched || (settled && (hovered || focused))) TipBubble(text)
    }
}

/**
 * The bubble itself, below the wrapped content. Popup(alignment = BottomStart) places a popup INSIDE its
 * anchor's bounds, bottom-aligned, so the bubble was drawn over the bottom of the row and its title
 * (Droidtop/tracker#366); [tipPosition] puts it under the anchor, or above when there is no room below.
 */
@Composable
private fun TipBubble(text: String) {
    Popup(popupPositionProvider = TipPositionProvider) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

private object TipPositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        tipPosition(anchorBounds, windowSize, popupContentSize)
}

/**
 * Where a tip bubble goes: its left edge on the anchor's, directly under it, kept inside the window sideways;
 * above the anchor when it would run off the bottom and there is room above. Pure, for the tests.
 */
internal fun tipPosition(anchor: IntRect, window: IntSize, bubble: IntSize): IntOffset {
    val x = anchor.left.coerceIn(0, (window.width - bubble.width).coerceAtLeast(0))
    val below = anchor.bottom
    val y = if (below + bubble.height <= window.height || anchor.top - bubble.height < 0) below else anchor.top - bubble.height
    return IntOffset(x, y)
}

private const val TIP_DELAY_MS = 600L
private const val TIP_TOUCH_MS = 3_000L
