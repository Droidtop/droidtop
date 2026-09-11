package dev.droidtop.shell.gamepad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one place the Handheld shell asks "how much room is there, and is
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
