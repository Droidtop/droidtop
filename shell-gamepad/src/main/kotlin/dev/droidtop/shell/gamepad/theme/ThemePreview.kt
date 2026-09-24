package dev.droidtop.shell.gamepad.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.shell.gamepad.MenuTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A REAL render of a theme, at thumbnail size and at the SCREEN'S OWN
 * SHAPE ([longEdge] gives the longer side; the shorter one follows the
 * display).
 *
 * Not a screenshot, not a picture shipped beside the theme, and not a
 * colour swatch someone chose: this is the theme's own `system` view,
 * parsed by the one theme parser and drawn by the one renderer
 * ([EsDeThemedView]) the Gaming shell itself uses, laid out into whatever
 * box it is given. What a person sees here is what the theme draws.
 *
 * It renders with NO list items, deliberately. A preview's job is to show
 * what the theme looks like -- its background, its colours, its own
 * static art and text -- and a carousel of systems this device has not
 * scanned yet would be invented content, which droidtop does not put in
 * front of a person as if it were their library.
 *
 * Cached by the loader: [ThemeAssets.loadTheme] keeps its parse, so
 * several previews on one screen, and a person moving up and down a list
 * of them, cost one parse per theme. The parse runs on
 * [Dispatchers.IO]; the frame stays an empty inset until it answers.
 */
@Composable
fun ThemeSystemPreview(themeId: String, longEdge: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Rotating the device changes both of the facts below -- which layout
    // the theme resolves to, and what shape this frame is -- so the
    // configuration is a key here, not just an ambient value.
    val configuration = LocalConfiguration.current
    // A parse is XML and file reads: done on IO, never in composition.
    // Until it answers the frame is an empty inset, not "No preview",
    // which would say the theme cannot be drawn.
    val parse by produceState<ParsedPreview?>(null, themeId, ThemePrefs.version, configuration) {
        value = null
        value = ParsedPreview(
            withContext(Dispatchers.IO) {
                ThemeAssets.discoverThemes(context)
                    .firstOrNull { it.name == themeId }
                    ?.let { ThemeAssets.loadTheme(context, it) }
            },
        )
    }
    // The frame is the screen's own shape, scaled down: the theme is
    // already parsed against the live screen (ThemeAssets.loadTheme reads
    // the display metrics), so on a phone held upright the layout inside
    // this frame is the theme's own portrait layout, and a 16:9 plate
    // would squash it into a shape its author never wrote.
    val size = remember(configuration, longEdge) {
        val metrics = context.resources.displayMetrics
        val (width, height) = esDePreviewFrame(
            longEdge = longEdge.value,
            screenWidth = metrics.widthPixels.toFloat(),
            screenHeight = metrics.heightPixels.toFloat(),
        )
        DpSize(width.dp, height.dp)
    }
    val loaded = parse
    val view = loaded?.theme?.views?.get("system")
    Box(
        modifier = modifier.size(size).background(MenuTokens.CardInset),
        contentAlignment = Alignment.Center,
    ) {
        if (loaded == null) {
            // Still parsing.
        } else if (view != null) {
            EsDeThemedView(
                view = view,
                items = emptyList(),
                firstItemFocus = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // A theme droidtop cannot parse says so rather than showing an
            // empty frame that reads as a theme with nothing in it.
            Text(
                "No preview",
                color = MenuTokens.Placeholder,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** A finished parse; [theme] null is a theme droidtop could not parse. */
private class ParsedPreview(val theme: dev.droidtop.library.theme.EsDeTheme?)

/**
 * The preview frame's two sides: [longEdge] along the screen's own long
 * axis, and the short side in the screen's own proportion. Pure, so the
 * rule the frame states -- "this is the theme drawing itself on THIS
 * screen" -- is testable without a device.
 *
 * A screen that reports no size at all (never seen on a device, possible
 * in a preview or a test harness) gets a square rather than a division by
 * zero.
 */
internal fun esDePreviewFrame(
    longEdge: Float,
    screenWidth: Float,
    screenHeight: Float,
): kotlin.Pair<Float, Float> = when {
    screenWidth <= 0f || screenHeight <= 0f -> longEdge to longEdge
    screenHeight > screenWidth -> longEdge * (screenWidth / screenHeight) to longEdge
    else -> longEdge to longEdge * (screenHeight / screenWidth)
}
