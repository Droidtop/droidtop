package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one place the Gaming shell asks "how much room is there, and is
 * this a screen somebody is holding upright?". Every screen in the shell
 * reads this instead of hardcoding the console's 1280x720dp landscape --
 * there are no portrait COPIES of any screen, only the same screens
 * measuring themselves.
 *
 * The buckets are Android's own window size classes (compact < 600dp,
 * medium < 840dp, expanded beyond), which is what the platform's own
 * layout guidance is written against, plus orientation, which size class
 * alone does not tell you: a 1080x1920 phone at 420dpi is compact-width
 * AND portrait, a 1080x1920 tablet at 240dpi is medium-width and
 * portrait, and both want the same vertical arrangement for different
 * reasons.
 *
 * [touchFirst] is portrait. Not a guess about hardware: droidtop's own
 * pad-shaped devices (the Retroid console, a TV box, a docked handheld)
 * are landscape, and the screens people hold upright are phones without
 * a controller attached. A pad plugged into a portrait phone still works
 * exactly as before -- [touchFirst] only ever ADDS touch affordances
 * (see [TouchHintBar]), it never removes a pad route.
 */
enum class ShellWidthClass { COMPACT, MEDIUM, EXPANDED }

data class ShellWindow(
    val widthDp: Int,
    val heightDp: Int,
) {
    val portrait: Boolean get() = heightDp > widthDp

    val widthClass: ShellWidthClass
        get() = when {
            widthDp < 600 -> ShellWidthClass.COMPACT
            widthDp < 840 -> ShellWidthClass.MEDIUM
            else -> ShellWidthClass.EXPANDED
        }

    val compact: Boolean get() = widthClass == ShellWidthClass.COMPACT

    /**
     * Touch affordances are shown IN ADDITION to the pad routes, never
     * instead of them.
     *
     * A window is treated as a phone's when it is upright OR when one of
     * its sides is phone-sized, which is the same device turned
     * sideways: a 1080x1920 phone at 420dpi is 411 x 731dp, so rotating
     * it gives a 731dp-WIDE window that is not compact by width and is
     * still a phone in somebody's hands with no pad attached. Keying on
     * `portrait` alone took the touch bar away in that rotation
     * (emulator capture, 2026-09-11: the rotated screen had the theme's
     * own help legend and no route to B/Y/Select at all). The console
     * is 1280x720dp, whose smaller side is 720dp, so nothing about it
     * changes.
     */
    val touchFirst: Boolean get() = portrait || minOf(widthDp, heightDp) < 600

    /**
     * The shell's own screen-edge gutter. 48dp is right for a TV-distance
     * 1280dp-wide screen and eats a tenth of a phone's width, so it
     * shrinks with the window rather than being repeated as a magic
     * number at every call site.
     */
    val edgePadding: Dp
        get() = when (widthClass) {
            ShellWidthClass.COMPACT -> 16.dp
            ShellWidthClass.MEDIUM -> 32.dp
            ShellWidthClass.EXPANDED -> 48.dp
        }

    /** Gap between top-level tabs; the same reasoning as [edgePadding]. */
    val tabGap: Dp
        get() = when (widthClass) {
            ShellWidthClass.COMPACT -> 16.dp
            ShellWidthClass.MEDIUM -> 24.dp
            ShellWidthClass.EXPANDED -> 32.dp
        }

    /**
     * Minimum width of a game card in an adaptive grid. A phone fits two
     * readable columns at 150dp and one oversized one at 220dp.
     */
    val gridItemMinWidth: Dp
        get() = if (compact) 150.dp else 220.dp

    /** A touch target is at least this tall -- Android's own 48dp minimum. */
    val minTouchTarget: Dp get() = 48.dp

    /**
     * How wide a modal panel (options menu, picker) may get. Landscape
     * keeps the fixed 520dp column it was designed at; a narrow screen
     * gives it everything except the gutters, since a fixed width wider
     * than the screen silently clips its own buttons off the edge.
     */
    fun panelWidth(preferred: Dp = 520.dp): Dp {
        val available = widthDp.dp - edgePadding * 2
        return if (preferred <= available) preferred else available
    }
}

/**
 * What the screen on top has of its OWN to put in the help row.
 *
 * Real ES-DE has exactly one help bar: the Window owns a single
 * `HelpComponent` (Window.cpp:126, and `Window::setHelpPrompts`,
 * Window.cpp:884, is the one way anything fills it), and that component
 * draws nothing at all when help prompts are switched off
 * (HelpComponent.cpp:629 -- `ShowHelpPrompts` false resets the grid).
 * A themed view's own `<helpsystem>` styles that one bar; it is not a
 * second bar of its own. droidtop keeps that count, so a screen says
 * what it HAS and [esDeHelpRowOwner] decides who draws.
 */
enum class HelpRowClaim {
    /** Nothing of its own: an ordinary droidtop screen. */
    NONE,

    /**
     * The screen draws its own real [TouchHintBar], with actions that
     * are not the shell's -- the PC surface, where A opens a game rather
     * than launching it (docs/SPEC.md 7i). It is a control surface, not
     * a legend, so it is tappable and a touch-first window has no reason
     * to add the shell's bar beside it.
     */
    SCREEN,

    /**
     * A themed view: the theme lays the help row out ITSELF, at its own
     * `<helpsystem>` position (or, declaring none, at ES-DE's own default
     * for that component). That row is DECORATION -- it names buttons, it
     * does not dispatch them -- so on a touch-first window the shell's own
     * bar takes the row, in that same place.
     *
     * The claim is about the CANVAS, not about the element: a theme that
     * declares no `<helpsystem>` still draws the whole window and still
     * has a help position, so shortening its canvas to put droidtop's bar
     * in a strip underneath is wrong either way (rig, build 548).
     */
    THEME,
}

/**
 * Where a themed view wants the one help row, as fractions of the view.
 *
 * Real ES-DE draws its single `HelpComponent` ON the view at this
 * position and never shortens the view for it (Window.cpp:126, :884), so
 * droidtop's own bar takes the same place when it takes the row. [posY]
 * is the point in the view the row is placed at and [originY] which point
 * of the ROW that is -- the same pair of `<pos>`/`<origin>` semantics
 * every themed element has, applied after measurement because the row's
 * height is whatever its hints measure to.
 */
data class EsDeHelpRowSlot(val posY: Float, val originY: Float) {
    companion object {
        /**
         * ES-DE's own default help position, before any theme styles it:
         * `0.012 * width, 0.9515 * height` in a landscape window and
         * `0.975 * height` in a vertical one, origin `0 0` -- the
         * component's own constructor (HelpComponent.cpp:23-27), which
         * reads `Renderer::getIsVerticalOrientation()` for exactly that
         * choice. Only the vertical half is carried here: droidtop's bar
         * is a full-width, horizontally scrolling control surface, so its
         * extent is the window's and only its PLACE is the theme's.
         */
        fun esDeDefault(vertical: Boolean) =
            EsDeHelpRowSlot(posY = if (vertical) 0.975f else 0.9515f, originY = 0f)
    }
}

/**
 * How a themed view tells the shell where its help row goes, so the one
 * bar can be drawn there rather than under a shortened canvas. Reported
 * with the key of the screen that reported it, for exactly the reason the
 * claim itself carries one: two themed screens cross over during the
 * shell's crossfade and the one leaving must not answer for the one
 * arriving.
 */
val LocalHelpRowSlotReport = staticCompositionLocalOf<(EsDeHelpRowSlot) -> Unit> { {} }


/** Who actually draws the one help row for the screen on top. */
enum class HelpRowOwner {
    /** droidtop's own `ButtonHintFooter`. */
    SHELL,

    /** The screen's own [TouchHintBar]. */
    SCREEN,

    /** The theme's `<helpsystem>`. */
    THEME,

    /** Nobody: hints are switched off and the screen has none of its own. */
    NONE,
}

/**
 * Who draws the help row, as ONE answer every side reads: the shell's
 * `ButtonHintFooter` is drawn exactly when this is [HelpRowOwner.SHELL],
 * a screen's own bar exactly when it is [HelpRowOwner.SCREEN], and the
 * themed renderer draws the theme's `<helpsystem>` exactly when it is
 * [HelpRowOwner.THEME]. See docs/SPEC.md 7j.
 *
 * Two independent conditions for the one row is what let both draw at
 * once in landscape with Slate (rig, build 546); a claim that only said
 * "a theme handles the hints" is what let the shell's bar stack under
 * the PC grid's own in portrait, because a touch-first window overrode a
 * claim that was never a theme's in the first place (rig, build 547).
 */
fun esDeHelpRowOwner(
    showHints: Boolean,
    touchFirst: Boolean,
    claim: HelpRowClaim,
): HelpRowOwner = when (claim) {
    // A screen's own bar IS the control surface for that screen; there
    // is nothing for the shell's to add. Switching hints off silences it
    // like any other.
    HelpRowClaim.SCREEN -> if (showHints) HelpRowOwner.SCREEN else HelpRowOwner.NONE
    // The theme's row is a legend: with no pad attached it names buttons
    // a finger cannot reach, so the shell's tappable bar takes the row.
    HelpRowClaim.THEME -> if (showHints && touchFirst) HelpRowOwner.SHELL else HelpRowOwner.THEME
    HelpRowClaim.NONE -> if (showHints) HelpRowOwner.SHELL else HelpRowOwner.NONE
}

/**
 * The live answer, read by the themed renderer (which suppresses the
 * theme's `<helpsystem>` unless it is [HelpRowOwner.THEME]) and by any
 * screen that draws a bar of its own.
 */
val LocalHelpRowOwner = staticCompositionLocalOf { HelpRowOwner.SHELL }

val LocalShellWindow = staticCompositionLocalOf {
    // Only ever seen by a preview or a test composing a screen outside
    // the shell: the console's own landscape geometry.
    ShellWindow(widthDp = 1280, heightDp = 720)
}

/** Reads the live configuration. Recomposes on rotation, which is what makes one screen serve both orientations. */
@Composable
@ReadOnlyComposable
fun currentShellWindow(): ShellWindow {
    val configuration = LocalConfiguration.current
    return ShellWindow(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
    )
}
