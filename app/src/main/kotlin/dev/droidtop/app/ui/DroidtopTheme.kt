package dev.droidtop.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.shell.gamepad.GamingTheme
import dev.droidtop.shell.gamepad.rememberLightAppearance
import dev.droidtop.ui.shared.LocalNoticePalette
import dev.droidtop.shell.gamepad.DroidtopTypography
import dev.droidtop.shell.gamepad.ProvideShellCanvas

/**
 * The one shared Material theme for droidtop's own chrome (onboarding,
 * Console systems, the Desktop shell's panels, the PC store and container
 * screens, second-screen ambient surfaces) -- every droidtop-owned Compose
 * root wraps in this, so screens take colours and type from
 * [MaterialTheme] instead of hardcoding either.
 *
 * Both halves come from the design system in `:shell-gamepad`
 * (`DesignTokens.kt`, docs/SPEC.md section 7k): the colours outside Gaming
 * ([GamingTheme.outsidePalette]: the Gaming theme's colours by default, or the
 * Custom droidtop theme, each in the Appearance setting's light or dark) are the
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
 * "Gaming theming"). Everything else takes the colours outside Gaming.
 *
 * The Gaming shell's ES-DE-themed gamelist and carousel views are not drawn
 * through here at all: a themed view owns its whole surface (section 7f).
 */
@Composable
fun DroidtopTheme(
    gamingThemed: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (gamingThemed) {
        GamingTheme(content = content)
        return
    }
    // Light or dark is the Appearance setting's, for every root (docs/SPEC.md 7k, "Light and dark"); the colours are
    // the ones outside Gaming ("Colours outside Gaming", Droidtop/tracker#827), with their Material scheme and notice
    // colours, the ones a Gaming surface draws with when no theme is held. Every root used to force dark, the companion
    // among them (rig 1931, Droidtop/tracker#559).
    rememberLightAppearance()
    val palette = GamingTheme.outsidePalette
    CompositionLocalProvider(LocalNoticePalette provides palette.notice) {
        MaterialTheme(colorScheme = palette.colorScheme, typography = DroidtopTypography) {
            // No full-screen input method over droidtop's own fields (Droidtop/tracker#485 comment 6224857113).
            ProvideShellCanvas { dev.droidtop.ui.shared.NoFullscreenTextInput(content) }
        }
    }
}

/**
 * Whether a screen droidtop opens takes the Gaming theme: when droidtop was last in Gaming, or in Desktop, whose
 * chrome wears the Gaming theme too (docs/SPEC.md 2b "One bar, in droidtop's look"; rig 2026-10-15: the Switch mode
 * sheet opened from Desktop was in the plain face).
 */
@Composable
fun rememberGamingThemed(): Boolean {
    val context = LocalContext.current
    return remember(context) { Modes.lastMode(context) in setOf(Mode.GAMING.id, Mode.DESKTOP.id) }
}
