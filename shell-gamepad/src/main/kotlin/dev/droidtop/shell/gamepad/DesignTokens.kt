package dev.droidtop.shell.gamepad

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * droidtop's design system (docs/SPEC.md section 7k), in one file: one
 * spacing step scale, one type scale with a documented job per role, and
 * one colour source per surface family.
 *
 * It lives in `:shell-gamepad` because that is the module both the Gaming
 * shell and `:app` (onboarding, the PC surface, the companion and store
 * screens) can see; a token that only half the chrome can reach is not a
 * system. A themed ES-DE view is deliberately out of scope: it takes
 * every colour, typeface and measurement from the theme's own files.
 */

/**
 * The one spacing scale. Every padding, gap and inset between the
 * screen-edge gutter and the glyph is a step on it; a measurement that is
 * not a step is a defect, not a preference.
 *
 * The screen-edge gutter, the tab gap, the minimum grid item, the minimum
 * touch target and the maximum panel width are NOT here: they depend on
 * how much room there is and live on [ShellWindow], which is the one
 * place that asks that question.
 *
 * The steps are the 4dp grid Android's own layouts are drawn on, plus a
 * 2dp hairline for the gap between a title and the line explaining it,
 * which is a deliberate near-touch rather than a gap. Before this existed
 * shell-gamepad's own sources carried roughly 240 raw `N.dp` literals
 * across twelve different values (2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 24,
 * 32), which is not a scale but the absence of one.
 */
object Space {
    /** A title and the line that explains it: touching, not spaced. */
    val Hair = 2.dp
    /** Inside a chip or a label; the gap between an icon and its text. */
    val Xs = 4.dp
    /** Between rows of a list; inside a compact control. */
    val Sm = 8.dp
    /** A row's own vertical padding; the gap inside a card. */
    val Md = 12.dp
    /** The default gap between two things that belong together. */
    val Lg = 16.dp
    /** Between two blocks that do not. */
    val Xl = 24.dp
    /** Above a section label; around a screen's main content block. */
    val Xxl = 32.dp
    /** A deliberate hole: an empty state, a bottom-docked action area. */
    val Xxxl = 48.dp
}

/**
 * Measures that are about reading rather than spacing.
 */
object Measure {
    /**
     * Body text is capped to a readable line regardless of how wide the
     * window is. Roughly 72 characters at the body size, which is the
     * upper end of what typography guidance calls comfortable; a
     * full-bleed 1280dp line (onboarding's welcome text was ~128
     * characters on the rig) is a defect.
     */
    val bodyMaxWidth = 560.dp

    /**
     * A leading icon in a row -- an app's own icon, a system's logo. One
     * size, so a run of rows does not step in and out as icons vary.
     */
    val rowIcon = 32.dp
}

/**
 * The type scale, and the job of each role. droidtop's chrome supplies
 * this to [MaterialTheme] rather than inheriting the platform default, so
 * two screens in the same flow cannot use different roles for the same
 * job -- which is exactly what two adjacent onboarding steps did, one
 * titled `headlineMedium` and the next `headlineSmall`.
 *
 * Five roles carry droidtop's chrome. Read them through [TypeRole], which
 * names the job; the Material role behind each is an implementation
 * detail of this file.
 */
object TypeRole {
    /** The name of the screen you are on. One per screen, at the top. */
    val screenTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineSmall

    /** The name of a row, a card, a tile or a choice. */
    val rowTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium

    /** Prose: a step's explanation, a paragraph, an empty state. */
    val body: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** The one line under a row title that says what it does. */
    val supporting: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall

    /** What a setting is set to, in the value column. */
    val value: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** A group marker above a run of rows. Drawn uppercase, tracked out. */
    val sectionLabel: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium

    /** The label on a button. */
    val button: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge
}

/**
 * The scale itself. Sizes step rather than drift, and line heights are
 * 1.35x the size for prose and 1.25x for single-line roles, so a row's
 * height is predictable from its type.
 */
val DroidtopTypography: Typography = Typography().let { stock ->
    stock.copy(
        headlineSmall = stock.headlineSmall.copy(
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = stock.titleMedium.copy(
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        ),
        bodyMedium = stock.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = stock.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = stock.labelMedium.copy(fontSize = 12.sp, lineHeight = 15.sp),
        labelLarge = stock.labelLarge.copy(fontSize = 15.sp, lineHeight = 19.sp),
    )
}

/**
 * The colour source for droidtop's chrome OUTSIDE the shell's menus:
 * onboarding, the PC surface, the companion, the store and container
 * screens, the second-screen surfaces.
 *
 * [MenuTokens] is the other family, and the two do not meet: those tokens
 * are absolute white-at-alpha values that are legible over
 * [MenuTokens.OverlaySurface] and over nothing else, so any panel hosting
 * them paints that surface. These are a real Material pair, because the
 * screens that use them are ordinary Android screens a person can reach
 * with the system in either colour state.
 *
 * The dark palette is the exact set of colours the chrome used as
 * literals before any theme existed, so dark mode did not move when the
 * theme was introduced; the light palette is its counterpart. Both are
 * held to a contrast floor by `ChromeColorsContrastTest`, which is the
 * half of the system that had no test at all -- the two low-contrast
 * cases found on the rig (onboarding's disabled Continue, the gamelist's
 * grey-on-grey) were both outside `MenuTokensContrastTest`'s reach.
 */
object ChromeColors {
    /**
     * How far a disabled label is faded. Material's stock 38% leaves a
     * light-mode control at 2.3:1 against the ground -- not a thing a
     * person sees, which is exactly what onboarding's disabled Continue
     * was on the rig. This clears 3:1 in both palettes and is the one
     * value droidtop's chrome fades by.
     */
    const val DisabledAlpha = 0.5f

    val DarkBackground = Color(0xFF161616)
    val DarkOnBackground = Color(0xFFEDEDED)
    val DarkSurface = Color(0xFF1A1A1A)
    val DarkOnSurface = Color(0xFFEDEDED)
    val DarkSurfaceVariant = Color(0xFF242424)
    val DarkOnSurfaceVariant = Color(0xFFB9B9B9)
    val DarkPrimary = Color(0xFF8AB4FF)
    val DarkOnPrimary = Color(0xFF0B1220)
    val DarkTertiary = Color(0xFFCC8800)
    val DarkOnTertiary = Color(0xFF161616)
    val DarkOutline = Color(0xFF3A3A3A)

    val LightBackground = Color(0xFFF5F5F3)
    val LightOnBackground = Color(0xFF1C1C1E)
    val LightSurface = Color(0xFFFFFFFF)
    val LightOnSurface = Color(0xFF1C1C1E)
    val LightSurfaceVariant = Color(0xFFE9E9E6)
    val LightOnSurfaceVariant = Color(0xFF45454A)
    val LightPrimary = Color(0xFF2F5DC8)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightTertiary = Color(0xFF7A5400)
    val LightOnTertiary = Color(0xFFFFFFFF)
    val LightOutline = Color(0xFFC9C9C5)

    val Dark = darkColorScheme(
        background = DarkBackground,
        onBackground = DarkOnBackground,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        tertiary = DarkTertiary,
        onTertiary = DarkOnTertiary,
        outline = DarkOutline,
    )

    val Light = lightColorScheme(
        background = LightBackground,
        onBackground = LightOnBackground,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        primary = LightPrimary,
        onPrimary = LightOnPrimary,
        tertiary = LightTertiary,
        onTertiary = LightOnTertiary,
        outline = LightOutline,
    )
}
