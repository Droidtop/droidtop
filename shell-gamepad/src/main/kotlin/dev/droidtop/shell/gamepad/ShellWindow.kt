package dev.droidtop.shell.gamepad

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import dev.droidtop.shell.gamepad.input.ControllerLayouts

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
 * [touchFirst] is portrait, or a small landscape window with no pad
 * registered. droidtop's own pad-shaped devices (the Retroid console, a
 * TV box, a docked handheld) are landscape, and the screens people hold
 * upright are phones without a controller attached. A pad plugged into a
 * portrait phone still works exactly as before -- [touchFirst] only ever
 * ADDS touch affordances (see [TouchHintBar]), it never removes a pad
 * route.
 */
enum class ShellWidthClass { COMPACT, MEDIUM, EXPANDED }

data class ShellWindow(
    val widthDp: Int,
    val heightDp: Int,
    /**
     * Whether a real, non-virtual gamepad or joystick is registered with
     * Android right now -- [ControllerLayouts.attachedControllers], the one
     * gamepad-detection rule droidtop has. A Retroid console's own face
     * buttons and sticks ARE such a device: they show up exactly like an
     * external pad would, which is what [touchFirst] uses them for.
     */
    val padPresent: Boolean = false,
    /**
     * A touch target is at least this tall: a finger's size on this panel ([touchTargetDp]), which is Android's own
     * 48dp where the layout is at Android's density and more of the canvas where it is drawn smaller.
     */
    val minTouchTarget: Dp = 48.dp,
    /** Set on a pane of a larger window ([pane]): that window's [touchFirst], which narrowing must not change. */
    val paneTouchFirst: Boolean? = null,
) {
    val portrait: Boolean get() = heightDp > widthDp

    /**
     * The least size of anything pressed in this window: a finger's ([minTouchTarget]) where touch comes first, else
     * [Measure.padTarget]. The one place that choice is made (Droidtop/tracker#740): sizing a press target from
     * [minTouchTarget] directly made the pad-driven Quick Menu as sparse as a phone's.
     */
    val pressTarget: Dp get() = if (touchFirst) minTouchTarget else Measure.padTarget

    /**
     * A pane of a larger window ([pane]): the Quick Menu's catalog tabs. Its rows and section labels are compact
     * (DroidDeck's compact settings rows, Droidtop/tracker#363): a one-line row is [pressTarget] tall and no taller, and
     * a section label sits closer above its rows on a hairline.
     */
    val inPane: Boolean get() = paneTouchFirst != null

    /** A row's padding above and below its text: tighter in a pane ([inPane]), so a one-line row is its press target. */
    val rowVerticalPadding: Dp get() = if (inPane) MenuTokens.PaneRowVerticalPadding else MenuTokens.RowVerticalPadding

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
     * its sides is phone-sized AND nothing has already answered the pad
     * question: a 1080x1920 phone at 420dpi is 411 x 731dp, so rotating
     * it gives a 731dp-WIDE window that is not compact by width and is
     * still a phone in somebody's hands with no pad attached. Keying on
     * `portrait` alone took the touch bar away in that rotation
     * (emulator capture, 2026-09-11: the rotated screen had the theme's
     * own help legend and no route to B/Y/Select at all).
     *
     * The short-side check alone is wrong for a handheld console: a
     * Retroid Pocket 5's 5.5" 1080x1920 panel is a 768x432dp window in
     * landscape (rig capture, 2026-09-25), so its 432dp short side trips
     * the same "phone-sized" branch the rotated-phone fix above added,
     * and the shell's touch-sized pills got substituted in over the
     * theme's own thin `<helpsystem>` legend -- sized and positioned for
     * a legend, not for 48dp touch targets, so they visually overlapped
     * the system carousel it sits above (docs/SPEC.md 7j). [padPresent]
     * is [ControllerLayouts.attachedControllers], the one existing
     * gamepad-detection rule; a console's own buttons answer it exactly
     * like an external pad would, so this reuses that answer instead of
     * guessing again from geometry.
     */
    val touchFirst: Boolean get() = paneTouchFirst ?: (portrait || (minOf(widthDp, heightDp) < 600 && !padPresent))

    /**
     * This window seen by a pane [widthDp] wide inside it (the Quick Menu's catalog tabs, Droidtop/tracker#649): its
     * gutters and layout follow the pane, its input does not. A plain copy at the pane's width was taller than wide, so
     * it read as a phone held upright: touch-first, finger-sized rows with the pad attached, about 200px each on the
     * add-on (Droidtop/tracker#740).
     */
    fun pane(widthDp: Int): ShellWindow = copy(widthDp = widthDp, paneTouchFirst = touchFirst)

    /**
     * The shell's own screen-edge gutter: one proportion of the window's
     * width, as Steam's gamepad UI uses for its shelves, page content,
     * play section and settings (docs/SPEC.md 7k), so every left edge in
     * the shell lines up. [contentGutterDp] is the rule.
     */
    val edgePadding: Dp get() = contentGutterDp(widthDp).dp

    /** The header's and footer's own side gutter ([barGutterDp]). */
    val barPadding: Dp get() = barGutterDp(widthDp).dp

    /**
     * Minimum width of a game card in an adaptive grid. A phone fits two
     * readable columns at 150dp and one oversized one at 220dp.
     */
    val gridItemMinWidth: Dp
        get() = if (compact) 150.dp else 220.dp

    /** The one height of the header and footer bars (docs/SPEC.md 7k). */
    val frameBarHeight: Dp get() = if (touchFirst) MenuTokens.FrameBarHeightTouch else MenuTokens.FrameBarHeight


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
    // Whether the pad is what the person is using now (PadModality). "Show button hints" off hides the
    // bar only then: the bar is also the touch route to what the pad buttons do, and for some of it
    // (the Start menu) the only one, so a person touching always has it (Droidtop/tracker#839).
    padDriving: Boolean = true,
): HelpRowOwner {
    val shown = showHints || !padDriving
    return when (claim) {
        // A screen's own bar IS the control surface for that screen; there
        // is nothing for the shell's to add.
        HelpRowClaim.SCREEN -> if (shown) HelpRowOwner.SCREEN else HelpRowOwner.NONE
        // The theme's row is a legend: with no pad attached it names buttons
        // a finger cannot reach, so the shell's tappable bar takes the row.
        HelpRowClaim.THEME -> if (shown && touchFirst) HelpRowOwner.SHELL else HelpRowOwner.THEME
        HelpRowClaim.NONE -> if (shown) HelpRowOwner.SHELL else HelpRowOwner.NONE
    }
}

/**
 * The live answer, read by the themed renderer (which suppresses the
 * theme's `<helpsystem>` unless it is [HelpRowOwner.THEME]) and by any
 * screen that draws a bar of its own.
 */
val LocalHelpRowOwner = staticCompositionLocalOf { HelpRowOwner.SHELL }

/**
 * The content gutter for a window [widthDp] wide: 2.8 percent of the width
 * (4 percent from 1500dp, where a line of capsules would otherwise run edge
 * to edge on a television), never under 16dp, so a phone held upright keeps
 * a thumb's room. Pure.
 */
fun contentGutterDp(widthDp: Int): Float =
    maxOf(16f, widthDp * if (widthDp >= 1500) 0.04f else 0.028f)

/** The bars' side gutter: 12dp plus 1.4 percent of the width. Pure. */
fun barGutterDp(widthDp: Int): Float = 12f + widthDp * 0.014f

val LocalShellWindow = staticCompositionLocalOf {
    // Only ever seen by a preview or a test composing a screen outside
    // the shell: the console's own landscape geometry.
    ShellWindow(widthDp = 1280, heightDp = 720)
}

/**
 * The short side, in dp, of the window the shell is laid out for: the console's landscape 1280x720dp. Every type size
 * (`DroidtopTypography`) and spacing (`Space`, `MenuTokens`) was tuned at it, and the DP screen gives it (1920x1080
 * at 1.5x).
 */
const val CANVAS_SHORT_SIDE_DP = 720

/**
 * The smallest a dp may be on a panel, as a fraction of an inch: 1/250. Android's own reference is 1/160; this lets the
 * canvas draw denser than that, and no denser, so text on a small, sharp panel stays legible.
 */
const val MIN_DP_INCHES = 1f / 250f

/**
 * The density a window [widthDp] by [heightDp] at [density], on a panel that reports [xdpi] pixels per inch, lays the
 * shell out at: the one that makes its short side [CANVAS_SHORT_SIDE_DP], bounded by the display's own report. Never
 * above Android's density, so a big screen is not blown up; never below [MIN_DP_INCHES] of the reported panel, so a
 * small sharp one is not shrunk past legible. A display that reports no believable dpi (outside 80 to 1000) is taken
 * at Android's density as its physical one. Each window works this out from its own display, so the main screen and a
 * companion screen are scaled separately. It reads the display alone, never what input is attached (owner,
 * 2026-10-29: "Phones can have controllers. Can't we look at device-reported display information?"). The pixels do not
 * change, only how many of them a dp is: a Retroid Pocket 5's 5.5" 1080p panel reports 360dpi, so Android counts a dp
 * as 2.25 pixels and gives the shell 853x480dp, where the 1080p DP screen counts 1.5 and gives 1280x720dp. Drawn at 1.5
 * here too, the panel shows the same layout and proportions as the DP screen instead of 1.5 times the text and spacing
 * in the same pixels (owner, the same night: the text needed to come "WWAAYY down"; "We need responsive design and
 * intelligent scaling"); so does a 1080p phone, in either orientation. A window already at least the canvas keeps its
 * own density, so a big screen is never shrunk below Android's size. Touch targets do not shrink with it:
 * [touchTargetDp] sizes them from the panel's own reported dpi. Pure.
 */
fun canvasDensity(density: Float, widthDp: Int, heightDp: Int, xdpi: Float): Float {
    val shortSidePx = minOf(widthDp, heightDp) * density
    val panelDpi = if (xdpi in 80f..1000f) xdpi else density * 160f
    val floor = minOf(density, panelDpi * MIN_DP_INCHES)
    return (shortSidePx / CANVAS_SHORT_SIDE_DP).coerceIn(floor, density)
}

/**
 * The density a window [widthPx] by [heightPx] lays out at with the Interface size [factor] (Droidtop/tracker#656):
 * [canvasDensity] from the display's own [systemDensity], then times [factor], so 125 percent draws everything a
 * quarter bigger and 75 percent a quarter smaller, layout and text together, whatever density the window's
 * configuration already carries. Pure.
 */
fun scaledCanvasDensity(systemDensity: Float, widthPx: Float, heightPx: Float, xdpi: Float, factor: Float): Float =
    canvasDensity(systemDensity, (widthPx / systemDensity).roundToInt(), (heightPx / systemDensity).roundToInt(), xdpi) * factor

/** The density Android gives [displayId], from its real metrics; [fallback] when the display cannot be read. */
@Suppress("DEPRECATION")
private fun displaySystemDensity(context: android.content.Context, displayId: Int, fallback: Float): Float {
    val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return fallback
    val display = manager.getDisplay(displayId) ?: return fallback
    val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
    return if (metrics.densityDpi > 0) metrics.densityDpi / 160f else fallback
}

/** A finger's target, 48dp at Android's reference 160dpi: 0.3 inch, about 7.6 mm. */
const val TOUCH_TARGET_INCHES = 0.3f

/**
 * The touch target, in dp at [layoutDensity], that is [TOUCH_TARGET_INCHES] on a panel the display reports at [xdpi]
 * pixels per inch, so it is a finger's size whatever density the layout is drawn at. A display that reports no
 * believable dpi (outside 80 to 1000, as some external screens do) falls back to Android's 48dp at [systemDensity].
 * Never under 48dp of the layout. Pure.
 */
fun touchTargetDp(xdpi: Float, systemDensity: Float, layoutDensity: Float): Float {
    val px = if (xdpi in 80f..1000f) xdpi * TOUCH_TARGET_INCHES else 48f * systemDensity
    return maxOf(48f, px / layoutDensity)
}

/** The live [touchTargetDp], provided by [ProvideShellCanvas] and read into [ShellWindow.minTouchTarget]. */
val LocalTouchTarget = staticCompositionLocalOf { 48.dp }

/**
 * Lays [content] out at [canvasDensity]: the composition's density and its configuration's dp sizes both follow, so
 * every `dp`, every `sp` (with the Text size setting's scale kept), [currentShellWindow] and every
 * `BoxWithConstraints` measure the same canvas, and [LocalTouchTarget] keeps touch targets a finger's size. Applying
 * it twice changes nothing, since the window's pixels are the same at either density. Every droidtop Compose root goes
 * through it, from `GamingTheme` and `DroidtopTheme`.
 */
@Composable
fun ProvideShellCanvas(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val xdpi = context.resources.displayMetrics.xdpi
    // The window's pixels, from the configuration's own dp size and dpi (a consistent pair even when the Resources
    // under it were already moved to the canvas, as droidtop's activities are).
    val configDensity = configuration.densityDpi / 160f
    val widthPx = configuration.screenWidthDp * configDensity
    val heightPx = configuration.screenHeightDp * configDensity
    // The canvas is bounded by the display's own density, not the configuration's, which droidtop's activities have
    // already moved to the canvas: with the Interface size factor on top, bounding by the moved density would apply
    // the factor twice. The factor is the setting for the screen this window is on (Droidtop/tracker#656).
    val displayId = LocalView.current.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
    val sizeTick by dev.droidtop.runtime.InterfaceSize.changed.collectAsState()
    val (systemDensity, factor) = remember(configuration, displayId, sizeTick) {
        displaySystemDensity(context, displayId, configDensity) to dev.droidtop.runtime.InterfaceSize.factorFor(context, displayId)
    }
    val target = scaledCanvasDensity(systemDensity, widthPx, heightPx, xdpi, factor)
    val touchTarget = touchTargetDp(xdpi, configDensity, target).dp
    if (kotlin.math.abs(target - configDensity) < 0.01f && kotlin.math.abs(target - density.density) < 0.01f) {
        CompositionLocalProvider(LocalTouchTarget provides touchTarget, content = content)
        return
    }
    val scaled = remember(configuration, target) {
        Configuration(configuration).apply {
            screenWidthDp = (widthPx / target).roundToInt()
            screenHeightDp = (heightPx / target).roundToInt()
            smallestScreenWidthDp = (minOf(widthPx, heightPx) / target).roundToInt()
            densityDpi = (target * 160f).roundToInt()
        }
    }
    CompositionLocalProvider(
        LocalConfiguration provides scaled,
        LocalDensity provides Density(target, density.fontScale),
        LocalTouchTarget provides touchTarget,
        content = content,
    )
}

/**
 * Reads the live configuration and whether a pad is attached ([ControllerLayouts.padPresent]). Recomposes on
 * rotation, which is what makes one screen serve both orientations, and when a pad comes or goes.
 */
@Composable
fun currentShellWindow(): ShellWindow {
    val configuration = LocalConfiguration.current
    val padPresent by ControllerLayouts.padPresent.collectAsState()
    return ShellWindow(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
        padPresent = padPresent,
        minTouchTarget = LocalTouchTarget.current,
    )
}
