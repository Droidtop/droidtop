package dev.droidtop.shell.gamepad

import androidx.compose.foundation.shape.RoundedCornerShape
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
 *
 * **Names first, values second.** What a screen reads is a ROLE -- the
 * gap between two things that belong together, the style of a row title,
 * the colour of a supporting line. The numbers and hex values behind
 * those roles are one visual identity, they all live in this file, and
 * swapping the identity is an edit HERE and nowhere else: no screen
 * names a size, a weight or a colour of its own, so none of them has to
 * change when the identity does. The values below are droidtop's current
 * ones (the dark set is exactly what the chrome used as literals before
 * any theme existed, so dark mode did not move when this landed).
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

    /**
     * A theme's own render, shown beside its name (onboarding's Appearance
     * step): the length of its LONGER side. The other side comes from the
     * screen, because a theme lays itself out against a screen's aspect
     * ratio and a preview at the wrong shape is a preview of a layout the
     * theme never wrote -- which is what a fixed 16:9 plate made of a
     * theme's portrait layout on a phone held upright (rig, build 546).
     * See [dev.droidtop.shell.gamepad.theme.ThemeSystemPreview].
     */
    val themePreviewLongEdge = 112.dp
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

/**
 * The shell's own colour palette, and the only one its menus may use.
 *
 * These are ABSOLUTE values against [OverlaySurface], not a theme-aware
 * scheme: the fills are white at low alpha and the text is white, which
 * is legible over this surface and over nothing else. So any surface
 * that hosts them has to be painted from this same object -- a panel
 * painted with `MaterialTheme.colorScheme.surface` is white wherever the
 * platform is in a light colour state, and these tokens then render
 * white on white (the Quick Menu's System tab did exactly that; see
 * QuickMenu.kt). One palette for the shell, not a platform scheme
 * underneath a hand-picked one.
 *
 * The shell's own PAGES -- the game grid and a game's detail, the PC
 * surface, the editors and pickers the shell opens full-screen -- are the
 * same family on a different ground: they sit on [Ground], and their
 * cards, plates and text are the roles below [Ground]. Every one of them
 * is absolute too, and `MenuTokensContrastTest` holds each text role to a
 * floor over every surface it is drawn on.
 */
object MenuTokens {
    val Surface = Color(0x0DFFFFFF)
    val SurfaceSelected = Color(0x2BFFFFFF)
    val OverlaySurface = Color(0xFF1C2027)
    val OnSurface = Color.White
    val OnSurfaceMuted = Color(0xFF8A93A1)
    val Value = Color(0xFFAEB7C4)
    val Placeholder = Color(0xFF6B7480)
    val Accent = Color(0xFF8AB4FF)
    val Danger = Color(0xFFFFB4AB)
    /** "This is on / included" -- the one affirmative in the menus. */
    val Affirmative = Color(0xFF7FE08A)
    val SectionLabel = Color(0xFF7D8794)

    /** Behind every page the shell draws: the grid, a detail, the PC surface. */
    val Ground = Color.Black
    /** A card or plate on [Ground]: a game card, a detail's artwork plate. */
    val Card = Color(0xFF1A1A1A)
    /** The brightened card: the one selection idiom on a page. */
    val CardFocused = Color(0xFF2A2A2A)
    /** A row sunk into a page (a detail's action rows, the runner picker). */
    val CardInset = Color(0xFF141414)
    /** The hairline edge of a card that is not focused; focused, it is [Accent]. */
    val CardOutline = Color(0x1FFFFFFF)
    /** A chosen chip or tab, drawn solid, and the label on it. */
    val Selected = Color.White
    val OnSelected = Color.Black
    /** Under text laid over artwork: the gradient's dark end. */
    val Scrim = Color(0xCC000000)
    /** The favourite mark. */
    val Favourite = Color(0xFFFFD700)
    /** The plate behind a [Danger] message that sits over the page. */
    val DangerPlate = Color(0xCC330E0B)
    /** The one primary action on a detail (Play), and its states. */
    val Launch = Color(0xFF2B5C3C)
    val LaunchFocused = Color(0xFF3D7A52)
    val LaunchDisabled = Color(0xFF232323)
    /** The supporting line on [Launch]. */
    val OnLaunchMuted = Color(0xFFD7E6DC)
    /**
     * Every label on a control that is not available, its title and its
     * supporting line alike. Faded by the one value droidtop's chrome
     * fades by, never by a grey of its own; a faded [Value] falls under
     * 3:1 on [CardFocused], which is where a disabled row is looked at.
     */
    val OnSurfaceDisabled = OnSurface.copy(alpha = ChromeColors.DisabledAlpha)
    /**
     * The hint bar's own plate, where it has one, and a hint pill's
     * outline. It has none over a themed view: real ES-DE's HelpComponent
     * draws its text ON the view and paints no background of its own, and
     * an opaque strip there covers the plate the theme drew for exactly
     * this row (rig, build 548).
     */
    val HintBar = Color(0xFF111111)
    val HintPillOutline = Color(0x33FFFFFF)

    val RowShape = RoundedCornerShape(10.dp)
    val OverlayShape = RoundedCornerShape(14.dp)
    val RowSpacing = 6.dp

    /**
     * ONE row height rule: every row is at least this tall, whatever it
     * says, and a row with a subtitle grows from it. The rig measured
     * three different heights down one settings screen (100 / 130 / 160
     * px) because the box was whatever its text happened to need.
     */
    val RowMinHeight = 56.dp

    /**
     * The value column's own width, so the values down a screen line up
     * as a column instead of each one starting where its label stopped.
     * A value longer than this still gets the room it needs -- it is a
     * minimum, not a box.
     */
    val ValueColumnMinWidth = 92.dp

    /**
     * The room the hint bar takes at the bottom of the window, as CONTENT
     * padding on every scrolling screen the shell draws.
     *
     * The bar is the last thing in the shell's column, so a list measured
     * against the rest of the window ends exactly where the bar begins:
     * its last row is sliced by the window edge and scrolling to the end
     * never brings that row clear (rig: the settings list's own last row,
     * build 546; a game detail's last card, build 548). As CONTENT padding
     * the same space scrolls with the list, so the end of the list is the
     * end of the list. One value, because it is one bar.
     *
     * Sized to the compact bar below (docs/SPEC.md 7k): a hint chip's own
     * height, `HintChipMinHeight`, plus [HintBarVerticalPadding] on both
     * edges -- a console-style bar, not a phone's, since the console is
     * where this row sits closest to the theme's own content (owner, on
     * the RP5 console, 2026-09-25: "the pills are also too big").
     */
    val HintBarRoom = 56.dp

    /** [TouchHintBar]'s own top/bottom padding, both editions of the bar. */
    val HintBarVerticalPadding = 6.dp

    /**
     * A hint chip's minimum drawn height, touch or not. Kept well under
     * [dev.droidtop.shell.gamepad.ShellWindow.minTouchTarget]: the tap
     * target is not the drawn chip (see [HintTouchTarget]) so shrinking
     * this does not shrink what a finger can hit.
     */
    val HintChipMinHeight = 28.dp

    /**
     * The actual minimum tap size for a hint chip on a touch-first
     * window -- [ShellWindow.minTouchTarget]'s own 48dp, same as every
     * other touch control, even though the chip it surrounds draws at
     * [HintChipMinHeight]. The chip sits centred inside this larger,
     * invisible box, so the extra room is a bigger hit area, never a
     * bigger pill.
     */
    val HintTouchTarget = 48.dp

    /** A hint chip's glyph badge ("A", "B", ...): font size and padding. */
    val HintGlyphTextSize = 12.sp
    val HintGlyphPaddingHorizontal = 6.dp
    val HintGlyphPaddingVertical = 1.dp

    /** A hint chip's action label ("Select", "Back", ...): font size. */
    val HintLabelTextSize = 13.sp

    /**
     * The width of the focus ring `selectionFrame` draws (GamingMenu.kt):
     * one value for every focusable thing the shell draws, cards, rows,
     * chips, tabs and tiles alike (docs/SPEC.md 7k).
     */
    val FocusRingWidth = 3.dp
}
