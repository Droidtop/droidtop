package dev.droidtop.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.shell.gamepad.GamingTheme
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
 * [gamingThemed] is for the screens Gaming mode opens (onboarding re-entered
 * from Gaming's Settings, Console systems, the containers and store
 * screens): they take their colours and typefaces from the active ES-DE
 * theme exactly as the Gaming shell does, through [GamingTheme] (docs/SPEC.md
 * "Gaming theming"). Everything else keeps droidtop's own scheme.
 *
 * The Gaming shell's ES-DE-themed gamelist and carousel views are not drawn
 * through here at all: a themed view owns its whole surface (section 7f).
 */
@Composable
fun DroidtopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    gamingThemed: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (gamingThemed) {
        GamingTheme(content)
        return
    }
    MaterialTheme(
        colorScheme = if (darkTheme) ChromeColors.Dark else ChromeColors.Light,
        typography = DroidtopTypography,
        content = content,
    )
}

/** Whether droidtop was last in Gaming mode, which is when a screen it opens takes the Gaming theme. */
@Composable
fun rememberGamingThemed(): Boolean {
    val context = LocalContext.current
    return remember(context) { Modes.lastMode(context) == Mode.GAMING.id }
}
