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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.theme.EsDeThemeElement
import dev.droidtop.library.theme.EsDeThemeValue
import dev.droidtop.library.theme.EsDeThemeView
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a parsed [EsDeThemeView] as ONE coherent screen -- every
 * positioned element (background/video/info text/carousel/help/...)
 * composited together by real z-index, the theme itself driving the
 * layout. This replaces an earlier, real bug: [EsDeSystemListView] used
 * to be rendered separately, in droidtop's own hardcoded Column, ignoring
 * the theme's actual `pos`/`size`/`origin` for the carousel/grid/textlist
 * element entirely (DEcaffe positions it centered near the BOTTOM of the
 * screen; droidtop's own layout put system cards crowding the TOP,
 * nothing like the real theme). The primary list element is now just
 * another themed element type in this same dispatcher, positioned exactly
 * like every other one -- [items]/[firstItemFocus] are threaded through
 * only for that one case.
 */
@Composable
fun EsDeThemedView(
    view: EsDeThemeView,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
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
                // Real, live-rendered -- ES-DE's own "clock" type has no
                // "metadata" property at all (confirmed against its real
                // schema), unlike "datetime". "datetime" is a genuinely
                // different element: it's metadata-bound (release date,
                // last played, ...), and rendering it as a live clock was
                // a real bug -- a theme's own <datetime metadata=
                // "releasedate" format="%Y"> was showing today's actual
                // year, since droidtop's LibraryEntry doesn't model
                // release dates. Bucketed with badges/rating/gamelistinfo
                // below instead: parsed, not rendered, until real
                // per-game metadata exists to bind it to.
                "clock" -> EsDeThemedClock(element, viewWidth, viewHeight)
                // The real fix described above: positioned/sized exactly
                // like any other themed element, using the SAME EsDeCarousel/
                // EsDeGrid/EsDeTextList composables that already read the
                // theme's own itemSize/colors -- only the outer placement
                // was ever wrong.
                "carousel", "grid", "textlist" -> EsDeThemedListElement(
                    element, items, firstItemFocus, viewWidth, viewHeight,
                )
            }
        }
    }
}

@Composable
private fun EsDeThemedListElement(
    element: EsDeThemeElement,
    items: List<EsDeListItem>,
    firstItemFocus: FocusRequester?,
    viewWidth: Dp,
    viewHeight: Dp,
) {
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    EsDeSystemListView(
        element = element,
        items = items,
        firstItemFocus = firstItemFocus,
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY).size(width = width, height = height),
    )
}

@Composable
private fun EsDeThemedImage(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp) {
    val path = element.valueOrNull<EsDeThemeValue.Path>("path")?.resolved ?: return
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    val tint = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    AsyncImage(
        model = path,
        contentDescription = null,
        colorFilter = tint?.let { ColorFilter.tint(it) },
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
    val (width, height) = sizeOf(element, viewWidth, viewHeight)
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight, width, height)
    val tint = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) }
    AsyncImage(
        model = path,
        contentDescription = null,
        colorFilter = tint?.let { ColorFilter.tint(it) },
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
    val format = element.valueOrNull<EsDeThemeValue.Str>("format")?.value ?: "%H:%M"
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000)
        }
    }
    val formatted = remember(now, format) {
        runCatching {
            SimpleDateFormat(strftimeToJavaPattern(format), Locale.getDefault()).format(now)
        }.getOrDefault("")
    }
    val (offsetX, offsetY) = positionOf(element, viewWidth, viewHeight)
    val color = element.valueOrNull<EsDeThemeValue.Color>("color")?.let { colorOf(it) } ?: Color.White
    Text(
        text = formatted,
        color = color,
        modifier = Modifier.absoluteOffset(x = offsetX, y = offsetY),
    )
}

/**
 * `origin` is ES-DE's real anchor-point convention: a 0-1 fraction of the
 * element's OWN size that `pos` refers to, not always the top-left corner
 * (DEcaffe uses `origin="0.5 0.5"` -- center-anchoring -- throughout).
 * Defaults to "0 0" (top-left), matching ES-DE's own default when an
 * element omits the property.
 */
/**
 * ES-DE's real `datetime`/`clock` `format` property uses C strftime-style
 * `%`-specifiers (its own renderer calls through to strftime under the
 * hood), not Java's SimpleDateFormat letters -- feeding one straight into
 * SimpleDateFormat produced real garbage (e.g. a real theme's "%H:%M"-style
 * default rendered as literal "%2026", since SimpleDateFormat treats '%' as
 * a literal character and 'Y'/'H'/etc. as its OWN unrelated pattern
 * letters). Covers the common specifiers ES-DE's own themes actually use;
 * anything else passes through as a SimpleDateFormat literal (quoted), the
 * same honest-fallback approach used elsewhere in this renderer.
 */
private val STRFTIME_TO_JAVA = listOf(
    "%Y" to "yyyy", "%y" to "yy",
    "%m" to "MM", "%d" to "dd",
    "%H" to "HH", "%I" to "hh",
    "%M" to "mm", "%S" to "ss",
    "%p" to "a",
    "%A" to "EEEE", "%a" to "EEE",
    "%B" to "MMMM", "%b" to "MMM",
    "%%" to "%",
)

private fun strftimeToJavaPattern(format: String): String {
    val sb = StringBuilder()
    var i = 0
    outer@ while (i < format.length) {
        for ((strftime, java) in STRFTIME_TO_JAVA) {
            if (format.startsWith(strftime, i)) {
                sb.append(java)
                i += strftime.length
                continue@outer
            }
        }
        val c = format[i]
        if (c.isLetter()) sb.append('\'').append(c).append('\'') else sb.append(c)
        i++
    }
    return sb.toString()
}

private fun positionOf(
    element: EsDeThemeElement,
    viewWidth: Dp,
    viewHeight: Dp,
    width: Dp = 0.dp,
    height: Dp = 0.dp,
): kotlin.Pair<Dp, Dp> {
    val pos = element.valueOrNull<EsDeThemeValue.Pair>("pos") ?: EsDeThemeValue.Pair(0f, 0f)
    val origin = element.valueOrNull<EsDeThemeValue.Pair>("origin") ?: EsDeThemeValue.Pair(0f, 0f)
    val x = viewWidth * pos.x - width * origin.x
    val y = viewHeight * pos.y - height * origin.y
    return x to y
}

/**
 * Real, distinct ES-DE properties (confirmed against ImageComponent.cpp's
 * own setResize/setMaxSize): "size" stretches to an exact size, "maxSize"
 * scales down to fit WITHIN bounds preserving aspect ratio -- a real
 * theme can use only one (Art Book Next's own system-logo element has no
 * "size" at all, only "maxSize"). "size" wins if both are present,
 * matching ES-DE's own real precedence (its applyTheme sets one or the
 * other, "size" checked first). The maxSize case doesn't yet apply real
 * aspect-preserving containment at this layer -- AsyncImage's own
 * ContentScale.Fit (already applied at the call site) does that visually
 * within whatever box these bounds define, an honest approximation since
 * this parser doesn't decode the target image's real intrinsic size.
 */
private fun sizeOf(element: EsDeThemeElement, viewWidth: Dp, viewHeight: Dp): kotlin.Pair<Dp, Dp> {
    val size = element.valueOrNull<EsDeThemeValue.Pair>("size")
        ?: element.valueOrNull<EsDeThemeValue.Pair>("maxSize")
        ?: EsDeThemeValue.Pair(0.2f, 0.2f)
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
