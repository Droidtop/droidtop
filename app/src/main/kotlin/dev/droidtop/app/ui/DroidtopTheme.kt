package dev.droidtop.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The one shared Material theme for droidtop's own chrome (Onboarding,
 * Console systems, the Desktop shell's panels, second-screen ambient
 * surfaces) -- every droidtop-owned Compose root wraps in this, so screens
 * take colors from [MaterialTheme.colorScheme] instead of hardcoding.
 *
 * The dark palette is the exact set of colors the chrome already used
 * everywhere as literals before this existed (#1A1A1A surfaces, #8AB4FF
 * primary blue, #CC8800 amber accents -- tallied across app/ and
 * shell-desktop/ before migrating), so dark mode is pixel-identical to
 * what shipped; the light palette is its real counterpart, new with this
 * theme. Follows the system dark/light setting.
 *
 * Deliberately NOT applied to the Handheld shell's ES-DE-themed surfaces:
 * a themed view owns its whole surface and takes every color from the
 * active ES-DE theme (docs/SPEC.md section 7f), not from Material.
 */
private val DarkColors = darkColorScheme(
    background = Color(0xFF161616),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = Color(0xFFB9B9B9),
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0B1220),
    tertiary = Color(0xFFCC8800),
    onTertiary = Color(0xFF161616),
    outline = Color(0xFF3A3A3A),
)

private val LightColors = lightColorScheme(
    background = Color(0xFFF5F5F3),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE9E9E6),
    onSurfaceVariant = Color(0xFF4C4C50),
    primary = Color(0xFF2F5DC8),
    onPrimary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF9A6A00),
    onTertiary = Color(0xFFFFFFFF),
    outline = Color(0xFFC9C9C5),
)

/**
 * [darkTheme] defaults to the system setting; screens that live inside the
 * Handheld shell's always-dark world (Console systems is the real case --
 * it opens from Handheld's own Settings tab and deliberately matches
 * GamepadShell's plain-black ground) pass `darkTheme = true` so they keep
 * that identity regardless of the system light/dark setting.
 */
@Composable
fun DroidtopTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
