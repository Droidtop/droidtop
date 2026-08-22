package dev.droidtop.shell.gamepad.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.library.theme.EsDeThemeView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the subset of a parsed [EsDeThemeView] this module actually
 * covers -- `image` and `text` elements, positioned via their real
 * NORMALIZED_PAIR `pos`/`size` (0-1 fractions of the view's own bounds,
 * same convention ES-DE itself uses). `carousel` elements are parsed into
 * the data model (see [EsDeTheme]) but not rendered here -- a carousel is
 * a real scrolling/focus component, not a statically positioned element,
 * and belongs wired into whatever's actually driving Games' own system
 * list (shell-gamepad's GamepadShell) rather than a generic renderer here.
 *
 * Not wired into any real shell UI yet -- this is the rendering primitive;
 * loading an actual theme pack and using it in shell-gamepad's Games tab
 * is the next real step, not attempted in this pass.
 */
@Composable
fun EsDeThemedView(view: EsDeThemeView, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val viewWidth = maxWidth
        val viewHeight = maxHeight
        view.elements.values.sortedBy { zIndexOf(it) }.forEach { element ->
            when (element.type) {
                "image" -> EsDeThemedImage(element, viewWidth, viewHeight)
                "text" -> EsDeThemedText(element, viewWidth, viewHeight)
                // Real, honest fallback: no video/GIF playback engine
                // wired up (real, separate work) -- shows the element's
                // own real default/poster PATH property as a static image
                // instead of silently rendering nothing.
                "video", "animation" -> EsDeThemedFallbackImage(element, viewWidth, viewHeight)
                // Real, live-rendered -- doesn't need per-game metadata,
                // unlike badges/rating/gamelistinfo (parsed, not rendered
                // here -- see EsDeTheme's own doc comment for why).
                "datetime", "clock" -> EsDeThemedClock(element, viewWidth, viewHeight)
            }
        }
    }
}

@Composable
private fun EsDeThemedImage(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    val path = element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved ?: return
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    AsyncImage(
        model = path,
        contentDescription = null,
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .size(width = width, height = height),
    )
}

@Composable
private fun EsDeThemedText(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    val text = element.valueOrNull<EsDeThemeValue.Str>("text")?.value ?: return
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    Text(
        text = text,
        color = color,
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY),
    )
}

/**
 * Real fallback for `video`/`animation` elements: their own `default`/
 * `defaultImage`/`path` PATH property (whichever is present), shown as a
 * plain static image. Real ES-DE plays these as actual video/GIF content;
 * this pass doesn't build a media-playback engine, so a static poster is
 * the honest alternative to rendering nothing at all.
 */
@Composable
private fun EsDeThemedFallbackImage(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    val path = element.valueOrNull<EsDeThemeValue.Path>("default")?.resolved
        ?: element.valueOrNull<EsDeThemeValue.Path>("defaultImage")?.resolved
        ?: element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved
        ?: return
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    AsyncImage(
        model = path,
        contentDescription = null,
        modifier = Modifier
            .absoluteOffset(x = offsetX, y = offsetY)
            .size(width = width, height = height),
    )
}

/**
 * Real, live-updating `datetime`/`clock` rendering -- current wall-clock
 * time, formatted via the element's own real `format` property when
 * present (ES-DE's own strftime-style format string; falls back to a
 * plain default). Ticks every second via a real Compose
 * LaunchedEffect/delay loop, not a one-shot render.
 */
@Composable
private fun EsDeThemedClock(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    val format = element.valueOrNull<EsDeThemeValue.Str>("format")?.value ?: "HH:mm"
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val formatted = remember(now, format) {
        runCatching { SimpleDateFormat(format, Locale.getDefault()).format(now) }.getOrDefault("")
    }
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    Text(
        text = formatted,
        color = color,
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY),
    )
}

private fun positionOf(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp): kotlin.Pair<Dp, Dp> {
    val pos = element.valueOrNull<EsDeThemeValue.Pair>("pos") ?: EsDeThemeValue.Pair(0f, 0f)
    return viewWidth * pos.x to viewHeight * pos.y
}

private fun sizeOf(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp): kotlin.Pair<Dp, Dp> {
    val size = element.valueOrNull<EsDeThemeValue.Pair>("size") ?: EsDeThemeValue.Pair(0.2f, 0.2f)
    return viewWidth * size.x to viewHeight * size.y
}

private fun zIndexOf(element: EsDeThemeElement): Float =
    element.valueOrNull<EsDeThemeValue.FloatValue>("zIndex")?.value ?: 0f

/** [EsDeThemeValue.Color.argbLikeRgba] is packed RRGGBBAA (same layout as ES-DE's own getHexColor) -- Compose's Color wants ARGB, so the channels need reordering, not just a straight reinterpret. */
private fun colorOf(value: EsDeThemeValue.Color): Color {
    val rgba = value.argbLikeRgba
    val r = (rgba shr 24) and 0xFF
    val g = (rgba shr 16) and 0xFF
    val b = (rgba shr 8) and 0xFF
    val a = rgba and 0xFF
    return Color(red = r.toInt(), green = g.toInt(), blue = b.toInt(), alpha = a.toInt())
}
