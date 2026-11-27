package dev.droidtop.shell.gamepad

import dev.droidtop.shell.gamepad.input.ControllerLayouts
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

/** The density Android gives [displayId], from its real metrics; [fallback] when the display cannot be read. */
@Suppress("DEPRECATION")
private fun displaySystemDensity(context: android.content.Context, displayId: Int, fallback: Float): Float {
    val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return fallback
    val display = manager.getDisplay(displayId) ?: return fallback
    val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
    return if (metrics.densityDpi > 0) metrics.densityDpi / 160f else fallback
}

/**
 * Lays [content] out at [canvasDensity]: the composition's density and its configuration's dp sizes both follow, so
 * every `dp`, every `sp` (with the Text size setting's scale kept), [currentShellWindow] and every
 * `BoxWithConstraints` measure the same canvas, and [LocalTouchTarget] keeps touch targets a finger's size. Applying
 * it twice changes nothing, since the window's pixels are the same at either density. Every droidtop Compose root goes
 * through it, from `GamingTheme` and `DroidtopTheme`.
 */
@Composable
fun ProvideShellCanvas(content: @Composable () -> Unit) {
    SharedComponentPlatform.install()
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
    // the factor twice. The factor is the setting for the screen this window is on (mytop-project/tracker#656).
    val displayId = LocalView.current.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
    val sizeTick by dev.droidtop.runtime.InterfaceSize.changed.collectAsState()
    val (systemDensity, factor) = remember(configuration, displayId, sizeTick) {
        displaySystemDensity(context, displayId, configDensity) to dev.droidtop.runtime.InterfaceSize.factorFor(context, displayId)
    }
    val target = scaledCanvasDensity(systemDensity, widthPx, heightPx, xdpi, factor)
    // The display's own density, not the configuration's: droidtop's activities have already moved that to the canvas.
    val touchTarget = touchTargetDp(xdpi, systemDensity, target).dp
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
    SharedComponentPlatform.install()
    val configuration = LocalConfiguration.current
    val padPresent by ControllerLayouts.padPresent.collectAsState()
    return ShellWindow(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
        padPresent = padPresent,
        minTouchTarget = LocalTouchTarget.current,
    )
}
