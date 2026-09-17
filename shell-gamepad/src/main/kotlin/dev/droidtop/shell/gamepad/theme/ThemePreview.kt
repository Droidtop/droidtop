package dev.droidtop.shell.gamepad.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.theme.ThemeAssets

/**
 * A REAL render of a theme, at thumbnail size.
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
 * of them, cost one parse per theme.
 */
@Composable
fun ThemeSystemPreview(themeId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val theme = remember(themeId, ThemePrefs.version) {
        ThemeAssets.discoverThemes(context)
            .firstOrNull { it.name == themeId }
            ?.let { ThemeAssets.loadTheme(context, it) }
    }
    val view = theme?.views?.get("system")
    Box(modifier = modifier.background(Color(0xFF101010)), contentAlignment = Alignment.Center) {
        if (view != null) {
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
                color = Color(0xFF6B7480),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
