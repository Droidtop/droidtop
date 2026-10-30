package dev.droidtop.shell.gamepad

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.droidtop.ui.shared.Motion

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
     * A press target's least size where the pad drives (Droidtop/tracker#740): the shell's own 48dp of the canvas, as
     * every row, pill and rail tab was through 2026-10-28. The finger-sized target (ShellWindow's minTouchTarget, 0.3
     * inch on the panel) is for touch only: on the console's panel it is about 80dp of the canvas, and as the Quick
     * Menu rail's tab size it took the menu's density away ("It's like the QAM has a ridiculously high scaling or
     * padding value"). [ShellWindow.pressTarget] chooses between the two; nothing else does.
     */
    val padTarget = 48.dp

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
 * The corner scale, from the approved visual language (Droidtop/tracker#644, coordination/design/DESIGN-LANGUAGE.md
 * "Shape"): 8 dp on rows, buttons and chips that are not pills; 12 dp on sheets, cards, dialogs and art tiles; one soft
 * plate for a large generic focused surface; pills for chips and badges. Nothing in between.
 * A `RoundedCornerShape(N.dp)` in a Gaming screen is a defect.
 */
object Corners {
    /** [Crisp]'s radius, for what draws the shape itself (a flood leaving a button, PageFlood.kt). */
    val CrispRadius: Dp = 8.dp

    /** Rows, buttons, chips that are not pills. */
    val Crisp = RoundedCornerShape(CrispRadius)

    /** Art tiles and cards: a capsule, a shelf item, a Home card. */
    val Tile = RoundedCornerShape(12.dp)

    /** Panels, sheets, dialogs, the Quick Menu and the left menu when they float. */
    val Panel = RoundedCornerShape(12.dp)
    /** A large soft plate: the one generic focused surface that is not a card. */
    val Plate = RoundedCornerShape(16.dp)
    /** Tabs, status badges, hint glyphs: fully round ends. */
    val Pill = RoundedCornerShape(50)

    /** An icon or a small picture inside a row (a setting's glyph, a Quick Menu tile's mark). */
    val Icon = RoundedCornerShape(3.dp)
}

/**
 * Elevation, in dp, by role. Only the platform's own shadow is used (no
 * blur, no drawn glow), and only where something is lifted: a capsule
 * carries a small one at rest and a deep one when focused (FocusLook), the
 * primary action a moderate one that deepens when selected, and a floating
 * menu one deeper still. Shadows are black, as Steam's are.
 */
object Elevation {
    val PrimaryRest: Dp = 4.dp
    val PrimaryFocused: Dp = 14.dp
    val Menu: Dp = 24.dp
}

/**
 * The left menu (Start; docs/SPEC.md 7j "Gaming controls"), as Steam's main
 * menu measures (docs/SPEC.md 7k): a 240dp panel flush with the left edge that
 * stops [VerticalInset] short of the top (the bottom is the menu's own hint
 * row), so it reads as a panel over the page rather than a wall; rows
 * [RowHeight] tall with [RowPaddingStart] and [RowPaddingEnd]. The row under
 * the cursor and the destination the person is on are the one row focus every
 * menu has (rowFocus, Droidtop/tracker#644); a destination with something
 * waiting carries an accent dot ([AttentionDot]) or a job ring.
 */
object SideMenu {
    val Width: Dp = 240.dp
    /** A window held upright has no room for 240dp beside the page: the panel takes this share of it. */
    const val PortraitFraction = 0.72f
    val VerticalInset: Dp = 40.dp
    val RowHeight: Dp = 48.dp
    val RowPaddingStart: Dp = 24.dp
    val RowPaddingEnd: Dp = 16.dp
    val IconSize: Dp = 24.dp
    val IconGap: Dp = 16.dp
    val AttentionDot: Dp = 8.dp
    /** The running game's live dot, and the ring that pulses out of it while motion is on. */
    val LiveDot: Dp = 8.dp
    val LiveRing: Dp = 16.dp
    /** How far the live ring grows before it has faded out, as a multiple of [LiveRing]. */
    const val LiveRingGrowth = 1.6f
}

/**
 * Settings' two panes (docs/SPEC.md "Settings layout"), as Steam's settings
 * measure: the category column takes [ColumnFraction] of the page and never
 * less than [ColumnMinWidth], on a plate of its own ([MenuTokens.Card]). The
 * entry under the cursor and the category being shown are the one row focus
 * (rowFocus, Droidtop/tracker#644). Scrolling keeps the selected row
 * [ScrollRoomAbove] of the list's height clear above it and [ScrollRoomBelow]
 * below (Steam's 250 and 60 of 800), so the cursor is never against an edge.
 */
object SettingsLayout {
    const val ColumnFraction = 0.2f
    val ColumnMinWidth: Dp = 240.dp
    val CategoryIcon: Dp = 22.dp
    const val ScrollRoomAbove = 0.3125f
    const val ScrollRoomBelow = 0.075f

    /** The category column's width in a page [hostWidth] wide: Steam's share, with its floor. Pure. */
    fun columnWidth(hostWidth: Dp): Dp = (hostWidth * ColumnFraction).coerceAtLeast(ColumnMinWidth).coerceAtMost(hostWidth)

    /**
     * The room kept above and below a selected row [itemPx] tall in a list [viewportPx] tall,
     * in pixels: Steam's shares, shrunk together when the row and the room would not fit. Pure.
     */
    fun scrollRoom(viewportPx: Int, itemPx: Int): Pair<Int, Int> {
        val above = viewportPx * ScrollRoomAbove
        val below = viewportPx * ScrollRoomBelow
        val spare = (viewportPx - itemPx).coerceAtLeast(0)
        val scale = if (above + below > spare) spare / (above + below) else 1f
        return (above * scale).toInt() to (below * scale).toInt()
    }
}

/**
 * Progress, where a job is drawn with it (a download's row, a capsule being
 * installed; `ShellProgressBar`): a [Height] track in the scrim role, filled
 * in the accent (Steam's black track and blue fill, in the theme's roles).
 */
object ProgressLook {
    val Height: Dp = 4.dp

    /** How wide the shell's loading bar is ([ShellLoading]): where a page waits on its content, and beside a line saying what runs. */
    val LoadingWidth: Dp = 160.dp
    val InlineLoadingWidth: Dp = 40.dp
}

/**
 * A modal dialog's choice list (`DialogChoices`, Steam's power dialog): rows at least
 * [RowMinHeight] tall on one plate, groups split by a [RuleHeight] rule in the scrim role.
 */
object DialogLook {
    val RowMinHeight: Dp = 40.dp
    val RuleHeight: Dp = 2.dp
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
    /** The name of the screen you are on, and a shelf's heading. One per block, at its top. */
    val screenTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineSmall

    /** A game's name over its own art: the hero card, the game page. */
    val heroTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.headlineMedium

    /**
     * A short label set above or beside a value or a title ("LAST PLAYED",
     * "2 HOURS"): drawn uppercase and tracked out, so it reads as a label
     * and never competes with what it labels.
     */
    val eyebrow: TextStyle
        @Composable @ReadOnlyComposable get() = sectionLabel

    /** A tab or a view chip's label. Drawn uppercase. */
    val tabLabel: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleSmall

    /** The name of a row, a card, a tile or a choice. */
    val rowTitle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium

    /** Prose: a step's explanation, a paragraph, an empty state. */
    val body: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium

    /** The one line under a row title that says what it does. */
    val supporting: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall

    /** What a setting is set to, in the value column: Caption in a narrow pane ([NarrowPane]). */
    val value: TextStyle
        @Composable @ReadOnlyComposable get() =
            if (LocalNarrowPane.current) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium

    /** A group marker above a run of rows. Drawn uppercase, tracked out 4 percent, in the secondary colour. */
    val sectionLabel: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.52.sp,
        )

    /** Provenance lines and units: the smallest text, 13 sp. Nothing important lives here. */
    val caption: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelSmall

    /** The label on a button. */
    val button: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.labelLarge
}

/**
 * The scale itself, from the approved visual language (Droidtop/tracker#644, coordination/design/DESIGN-LANGUAGE.md
 * "Type"), re-expressed in Material's roles for a 1080p handheld read at arm's length:
 *
 * - Display 32 Bold (headlineMedium): a game's name over its art, a page title.
 * - Title 24 Bold (headlineSmall): sheet and section titles.
 * - Body 18 Regular (bodyLarge, bodyMedium): rows and descriptions; a row's name is 18 Medium (titleMedium).
 * - Label 15 Medium (titleSmall, labelLarge): tabs, chips, buttons, hints; a row's supporting line is 15 (bodySmall).
 * - Section 13 Medium (labelMedium): group headers, uppercase, tracked out ([TypeRole.sectionLabel]).
 * - Caption 13 (labelSmall): provenance and units. 13 sp is the floor.
 *
 * The typeface is not decided here: a Gaming theme's own fonts replace the families (GamingTheme), so a theme keeps
 * control of its look. Where numbers change, callers ask for tabular figures (dev.droidtop.ui.shared.tabularFigures).
 */
val DroidtopTypography: Typography = Typography().let { stock ->
    stock.copy(
        headlineMedium = stock.headlineMedium.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
        headlineSmall = stock.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        titleMedium = stock.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        titleSmall = stock.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
        bodyLarge = stock.bodyLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
        bodyMedium = stock.bodyMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
        bodySmall = stock.bodySmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
        labelLarge = stock.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = stock.labelMedium.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = stock.labelSmall.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    )
}

/**
 * The compact ladder (owner, 2026-10-29, Droidtop/tracker#739: "text in the QAM is still really big on the internal
 * screen"): the scale one rung down, for a pane narrower than [NarrowPane.WIDTH_DP]. Row titles and body are Label
 * 15, supporting lines and labels Caption 13 (the floor), and a pane's own title stays Title 24; the families are the
 * theme's, untouched.
 */
fun Typography.compact(): Typography = copy(
    headlineMedium = headlineMedium.copy(fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = titleSmall.copy(fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    bodyLarge = bodyLarge.copy(fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 16.sp),
    labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp),
)

/** True inside a pane narrower than [NarrowPane.WIDTH_DP]: its rows read the compact ladder and keep their sentence on the row. */
val LocalNarrowPane = staticCompositionLocalOf { false }

/**
 * A sheet's or a side panel's pane (docs/SPEC.md 7k, "Type"): under [WIDTH_DP] wide, what is drawn in it reads the
 * compact ladder ([compact]). Keyed on the pane's own width, never the device, so it holds on any display and on the
 * companion and follows Interface size (#656), which already sizes the dp.
 */
object NarrowPane {
    const val WIDTH_DP = 600

    fun isNarrow(widthDp: Int): Boolean = widthDp in 1 until WIDTH_DP

    @Composable
    operator fun invoke(widthDp: Int, content: @Composable () -> Unit) {
        if (!isNarrow(widthDp)) return content()
        CompositionLocalProvider(LocalNarrowPane provides true) {
            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme,
                shapes = MaterialTheme.shapes,
                typography = MaterialTheme.typography.compact(),
                content = content,
            )
        }
    }
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
 * The dark palette is droidtop's own visual language (Droidtop/tracker#644,
 * coordination/design/DESIGN-LANGUAGE.md "Surfaces" and "Colour"), the
 * same values as GamingThemeColors.DEFAULT: a blue-grey ground, surfaces
 * one step apart, text that is not pure white, one azure accent. Material's
 * surface containers (what its sheets, menus and dialogs paint) are the
 * same levels, so no stock purple-grey shows anywhere. The light palette is
 * its counterpart. Both are
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

    val DarkBackground = Color(0xFF11151C)
    val DarkOnBackground = Color(0xFFF2F5F9)
    val DarkSurface = Color(0xFF181D26)
    val DarkOnSurface = Color(0xFFF2F5F9)
    val DarkSurfaceVariant = Color(0xFF262D3A)
    val DarkOnSurfaceVariant = Color(0xFFA9B4C4)
    val DarkPrimary = Color(0xFF3BB5FF)
    val DarkOnPrimary = Color(0xFF0B1220)
    val DarkTertiary = Color(0xFFF5B33D)
    val DarkOnTertiary = Color(0xFF11151C)
    val DarkOutline = Color(0xFF3A4352)

    /** The four levels (4.1): content, sheets, menus and dialogs, as Material's surface containers. */
    val DarkSurfaceLow = Color(0xFF141920)
    val DarkSheet = Color(0xFF1E2430)
    val DarkMenu = Color(0xFF262D3A)

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
        surfaceDim = DarkBackground,
        surfaceContainerLowest = DarkBackground,
        surfaceContainerLow = DarkSurfaceLow,
        surfaceContainer = DarkSurface,
        surfaceContainerHigh = DarkSheet,
        surfaceContainerHighest = DarkMenu,
        surfaceBright = DarkMenu,
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
 * Every role here is a getter on the live [GamingTheme] palette: while a
 * Gaming surface is on screen the palette is the active ES-DE theme's
 * (`GamingThemeMapping`, docs/SPEC.md "Gaming theming"), read from the
 * theme's own declared colours and held to WCAG AA, and otherwise it is
 * droidtop's own values, which are the ones this file used to hold as
 * literals. A screen reads the role and never knows which; because the
 * palette is snapshot state, a theme change redraws every reader.
 *
 * The roles are RELATED, not independent: the fills are the text ink at
 * low alpha and the cards are the ground mixed toward that ink, so a set
 * is legible together over [OverlaySurface] and [Ground] and over nothing
 * else. Any surface that hosts them therefore paints from this same
 * object -- a panel painted with `MaterialTheme.colorScheme.surface` is
 * white wherever the platform is in a light colour state, and these
 * tokens then render white on white (the Quick Menu's System tab did
 * exactly that; see QuickMenu.kt). One palette for the shell, not a
 * platform scheme underneath a hand-picked one.
 *
 * The shell's own PAGES -- the game grid and a game's detail, the PC
 * surface, the editors and pickers the shell opens full-screen -- are the
 * same family on a different ground: they sit on [Ground] (painted with
 * `Modifier.groundBackground()`, which also lays the theme's own texture
 * where it has one), and their cards, plates and text are the roles below
 * [Ground]. `MenuTokensContrastTest` holds each text role to a floor over
 * every surface it is drawn on, for droidtop's own palette, and
 * `GamingThemeMappingTest` does the same for a theme's.
 */
object MenuTokens {
    val Surface: Color get() = GamingTheme.palette.rowFill
    val SurfaceSelected: Color get() = GamingTheme.palette.rowFillSelected
    val OverlaySurface: Color get() = GamingTheme.palette.overlaySurface

    /**
     * The plate under a control floating over art (the status cluster, the Start and R2 pills): the overlay surface,
     * nearly opaque, so the muted text on it keeps the 4.5:1 the palette holds it to over any artwork. It was [Surface]
     * at 58%, the ink itself, so text and plate came from the same end of the scale: pale text on a pale pill over light
     * art (rig, droidtop 0.2.0-dev.1931, Droidtop/tracker#559).
     */
    val FloatingPlate: Color get() = GamingTheme.palette.overlaySurface.copy(alpha = 0.92f)
    val OnSurface: Color get() = GamingTheme.palette.onSurface
    val OnSurfaceMuted: Color get() = GamingTheme.palette.onSurfaceMuted
    val Value: Color get() = GamingTheme.palette.value
    val Placeholder: Color get() = GamingTheme.palette.placeholder
    val Accent: Color get() = GamingTheme.palette.accent
    /**
     * The one danger colour: the notice palette's danger ink (docs/SPEC.md 7k, "Notices"; Droidtop/tracker#485), the
     * same ink a danger notice, the status glyph's error mark and the danger dialog use, kept to contrast on this
     * palette's ground. It replaced the theme's own `danger` role here, so a failed capsule and a danger notice can no
     * longer be two reds.
     */
    val NoticeDanger: Color get() = GamingTheme.palette.notice.ink(dev.droidtop.library.settings.NoticeTone.Danger)
    /** "This is on / included" -- the one affirmative in the menus. */
    val Affirmative: Color get() = GamingTheme.palette.affirmative

    /**
     * Ink on an [Affirmative] fill (a switch's knob when on): black or white, whichever reads on it. The knob was
     * [OnSurface], near black in a light appearance, on a dark green track (rig 2026-10-16, Droidtop/tracker#559).
     * 0.179 is the relative luminance where black and white give the same contrast.
     */
    val OnAffirmative: Color get() = if (Affirmative.luminance() > 0.179f) Color.Black else Color.White
    val SectionLabel: Color get() = GamingTheme.palette.sectionLabel

    /** Behind every page the shell draws: the grid, a detail, the PC surface. */
    val Ground: Color get() = GamingTheme.palette.ground
    /** A card or plate on [Ground]: a game card, a detail's artwork plate. */
    val Card: Color get() = GamingTheme.palette.card
    /** The brightened card: the one selection idiom on a page. */
    val CardFocused: Color get() = GamingTheme.palette.cardFocused
    /** A row sunk into a page (a detail's action rows, the runner picker). */
    val CardInset: Color get() = GamingTheme.palette.cardInset
    /** The hairline edge of a card that is not focused; focused, it is [Accent]. */
    val CardOutline: Color get() = GamingTheme.palette.cardOutline
    /** A chosen chip or tab, drawn solid, and the label on it. */
    val Selected: Color get() = GamingTheme.palette.selected
    val OnSelected: Color get() = GamingTheme.palette.onSelected
    /** Under text laid over artwork: the gradient's dark end. */
    val Scrim: Color get() = GamingTheme.palette.scrim

    /**
     * What a side panel lays over the page behind it (the left menu, the Quick Menu): the theme's [Scrim] in a dark
     * appearance; in a light one, black at [LIGHT_VEIL_ALPHA]. The light scrim is the pale ground at 80 percent,
     * which washed the page out rather than setting it back (rig 2026-10-16, Droidtop/tracker#559); a modal
     * dialog's own dim was already black ([ModalScrim]).
     */
    val Veil: Color get() = GamingTheme.palette.let { if (it.colors.isLight) Color.Black.copy(alpha = LIGHT_VEIL_ALPHA) else it.scrim }

    /** [Veil] behind the Quick Menu, lighter for the reason [QuickPanelScrimAlpha] gives. */
    val PanelVeil: Color get() = GamingTheme.palette.let {
        if (it.colors.isLight) Color.Black.copy(alpha = LIGHT_PANEL_VEIL_ALPHA) else it.scrim.copy(alpha = QuickPanelScrimAlpha)
    }
    private const val LIGHT_VEIL_ALPHA = 0.45f
    private const val LIGHT_PANEL_VEIL_ALPHA = 0.32f
    /** The favourite mark. */
    val Favourite: Color get() = GamingTheme.palette.favourite
    /** The one primary action on a detail (Play), and its states. */
    val Launch: Color get() = GamingTheme.palette.launch
    val LaunchFocused: Color get() = GamingTheme.palette.launchFocused
    val LaunchDisabled: Color get() = GamingTheme.palette.launchDisabled
    /** The supporting line on [Launch]. */
    val OnLaunchMuted: Color get() = GamingTheme.palette.onLaunchMuted
    /**
     * Every label on a control that is not available, its title and its
     * supporting line alike. Faded by the one value droidtop's chrome
     * fades by, never by a grey of its own; a faded [Value] falls under
     * 3:1 on [CardFocused], which is where a disabled row is looked at.
     */
    val OnSurfaceDisabled: Color get() = GamingTheme.palette.onSurfaceDisabled
    /**
     * The hint bar's own plate, where it has one, and a hint pill's
     * outline. It has none over a themed view: real ES-DE's HelpComponent
     * draws its text ON the view and paints no background of its own, and
     * an opaque strip there covers the plate the theme drew for exactly
     * this row (rig, build 548).
     */
    val HintBar: Color get() = GamingTheme.palette.hintBar
    val HintPillOutline: Color get() = GamingTheme.palette.hintPillOutline

    /** The hairline round a floating side panel: the text ink at 5 percent (Steam's white-5% edge, in the theme's ink). */
    val PanelBorder: Color get() = OnSurface.copy(alpha = 0.05f)

    /**
     * How dark the page goes behind the Quick Menu: lighter than [Scrim]'s own strength, because the
     * game or page behind a quick-management panel is still what the person is looking at. A plain
     * scrim, not a blur: a real blur of a themed canvas with video and animation on it is a per-frame
     * cost the handheld should not pay for a panel that is open for seconds. The left menu, which
     * takes the person somewhere else, dims the page by [Scrim]'s own strength (Steam's 80 percent).
     */
    const val QuickPanelScrimAlpha = 0.55f

    val RowShape = Corners.Crisp
    val OverlayShape = Corners.Panel
    val RowSpacing = 6.dp

    /**
     * ONE row height rule: every row is at least this tall, whatever it
     * says, and a row with a subtitle grows from it. The rig measured
     * three different heights down one settings screen (100 / 130 / 160
     * px) because the box was whatever its text happened to need.
     */
    val RowMinHeight = 72.dp

    /**
     * An entry of the settings category column (docs/SPEC.md "Settings
     * layout"): a glyph and a one- or two-line name, shorter than a row
     * because it holds no value, still more than a touch target.
     */
    val CategoryRowMinHeight = 56.dp

    /**
     * The least height of a row in a scrolling settings list ([uniformRowHeight]): one touch target. Its one-line
     * title and padding make it about 52dp at the default text size, so a landscape page holds five or six rows
     * where [RowMinHeight]'s 72dp held three (Droidtop/tracker#664).
     */
    val ListRowMinHeight = 48.dp

    /** Generous vertical padding inside a row (owner, tracker#154). */
    val RowVerticalPadding = 14.dp

    /** A row's value wraps to this many lines inside [ValueColumnMaxWidth]. */
    const val ValueMaxLines = 3

    /** In a uniform-height row a value takes at most this many lines. */
    const val UniformValueMaxLines = 2
    val ValueColumnMaxWidth = 220.dp

    /** The most of a row's free width its value may take; the title keeps the rest (tracker#316). */
    const val ValueMaxShare = 0.55f

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
     * Header and footer are one frame (docs/SPEC.md 7k): both bars are at
     * least this tall (the touch figure on a touch-first window, where the
     * footer's 48dp tap targets have to fit), both use the screen-edge
     * gutter, and both end in the same hairline on the side facing the
     * content. [FrameBarHeightTouch] leaves a hint's 48dp tap target room.
     */
    val FrameBarHeight = 40.dp
    val FrameBarHeightTouch = 52.dp
    val FrameHairline: Color get() = CardOutline

    /** The selected tab's pill, and the L1/R1 badges beside the tabs, share this height. */
    val TabPillHeight = 32.dp

    /**
     * Estimates the header uses to decide whether the equal side slots
     * (L2 on the left, status and R2 on the right) fit around the centred tabs: one
     * tab's drawn width, one L1/R1 badge, and the least a slot needs.
     * [TabEstimateDp] is a tab at normal text; the header scales it by
     * the live text scale before the comparison (ChromeBar.kt).
     */
    const val TabEstimateDp = 84
    const val ShoulderEstimateDp = 38
    const val StatusSlotMinDp = 150

    /**
     * A section heading ("Apps", "Games") sits close to what it names and
     * leaves the room to the bar above: the gap under it is larger than
     * the gap over it, and the list's own top gap is small.
     */
    val SectionHeadingTopGap = 4.dp
    val SectionHeadingGap = 16.dp
    val SectionListTopGap = 8.dp

    /**
     * A hint chip's minimum drawn height, touch or not. Kept well under
     * [dev.droidtop.shell.gamepad.ShellWindow.pressTarget]: the tap
     * target is not the drawn chip (see [HintTouchTarget]) so shrinking
     * this does not shrink what a finger can hit.
     */
    val HintChipMinHeight = 28.dp

    /**
     * The actual minimum tap size for a hint chip on a touch-first
     * window -- [ShellWindow.pressTarget], same as every
     * other touch control, even though the chip it surrounds draws at
     * [HintChipMinHeight]. The chip sits centred inside this larger,
     * invisible box, so the extra room is a bigger hit area, never a
     * bigger pill.
     */
    val HintTouchTarget = 48.dp

    /** A button glyph ([Keycap], "A", "Start", "L1"): its text size, side padding and two heights. */
    val HintGlyphTextSize = 12.sp
    val HintGlyphPaddingHorizontal = 6.dp
    val KeycapHeight = 24.dp
    val KeycapHeightSmall = 20.dp

    /** A hint chip's action label ("Select", "Back", ...): font size. */
    val HintLabelTextSize = 13.sp

    /**
     * The width of the focus ring (FocusGlide.kt): one value for every
     * focusable thing the shell draws, cards, rows, chips, tabs and tiles
     * alike (docs/SPEC.md 7k). Steam's 2dp.
     */
    val FocusRingWidth = FocusLook.RingWidthDp.dp
}
