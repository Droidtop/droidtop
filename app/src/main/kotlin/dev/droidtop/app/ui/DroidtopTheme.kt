package dev.droidtop.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import dev.droidtop.shell.gamepad.ChromeColors
import dev.droidtop.shell.gamepad.DroidtopTypography

/**
 * The one shared Material theme for droidtop's own chrome (onboarding,
 * Console systems, the Desktop shell's panels, the PC store and container
 * screens, second-screen ambient surfaces) -- every droidtop-owned Compose
 * root wraps in this, so screens take colours and type from
 * [MaterialTheme] instead of hardcoding either.
 *
 * Both halves come from the design system in `:shell-gamepad`
 * (`DesignTokens.kt`, docs/SPEC.md section 7k): [ChromeColors] is the
 * colour source for this surface family and [DroidtopTypography] is
 * droidtop's type scale, supplied here rather than inherited from the
 * platform default so that two screens in the same flow cannot size the
 * same job differently. Spacing comes from `Space` and the window-derived
 * measurements from `ShellWindow`.
 *
 * Deliberately NOT applied to the Gaming shell's ES-DE-themed surfaces:
 * a themed view owns its whole surface and takes every colour from the
 * active ES-DE theme (section 7f), not from Material.
 */
@Composable
fun DroidtopTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) ChromeColors.Dark else ChromeColors.Light,
        typography = DroidtopTypography,
        content = content,
    )
}
