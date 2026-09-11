package dev.droidtop.shell.gamepad

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half of the colour system that had no test.
 * `MenuTokensContrastTest` covers the shell's menu palette; droidtop's
 * other chrome (onboarding, the PC surface, the companion and store
 * screens) takes [ChromeColors], and nothing held those to a floor --
 * which is why a disabled Continue button shipped at roughly 2:1 and why
 * a light-mode run of onboarding was never checked at all.
 *
 * docs/SPEC.md 7k: every text-on-surface pair in BOTH palettes is
 * covered, not only the menu palette.
 *
 * 4.5:1 is WCAG AA for body text; 3:1 is AA for large and secondary text.
 */
class ChromeColorsContrastTest {

    private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun luminance(color: Color): Float =
        0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)

    private fun contrast(color: Color, over: Color): Float {
        val a = color.alpha
        val flat = Color(
            red = color.red * a + over.red * (1 - a),
            green = color.green * a + over.green * (1 - a),
            blue = color.blue * a + over.blue * (1 - a),
        )
        val l1 = luminance(flat)
        val l2 = luminance(over)
        return (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
    }

    private fun assertReadable(name: String, color: Color, over: Color, floor: Float) {
        val ratio = contrast(color, over)
        assertTrue("$name is $ratio:1 over its surface, below $floor:1", ratio >= floor)
    }

    /** Every on-X colour over its own X, in one palette. */
    private fun assertPairs(which: String, scheme: ColorScheme) {
        assertReadable("$which onBackground", scheme.onBackground, scheme.background, 4.5f)
        assertReadable("$which onSurface", scheme.onSurface, scheme.surface, 4.5f)
        assertReadable("$which onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant, 4.5f)
        assertReadable("$which onPrimary", scheme.onPrimary, scheme.primary, 4.5f)
        assertReadable("$which onTertiary", scheme.onTertiary, scheme.tertiary, 4.5f)
        // The accent colours are used as TEXT on the plain grounds too --
        // a link-coloured action, an amber warning line.
        assertReadable("$which primary on background", scheme.primary, scheme.background, 4.5f)
        assertReadable("$which primary on surface", scheme.primary, scheme.surface, 4.5f)
        assertReadable("$which tertiary on background", scheme.tertiary, scheme.background, 4.5f)
        assertReadable("$which tertiary on surface", scheme.tertiary, scheme.surface, 4.5f)
        // A supporting line under a row title, drawn on the plain grounds.
        assertReadable("$which onSurfaceVariant on background", scheme.onSurfaceVariant, scheme.background, 4.5f)
        assertReadable("$which onSurfaceVariant on surface", scheme.onSurfaceVariant, scheme.surface, 4.5f)
    }

    @Test
    fun `the dark chrome palette is readable`() = assertPairs("dark", ChromeColors.Dark)

    @Test
    fun `the light chrome palette is readable`() = assertPairs("light", ChromeColors.Light)

    /**
     * Material's stock 38% disabled alpha is where onboarding's disabled
     * Continue came from: on the light ground it lands at 2.3:1, which is
     * not a control a person sees. [ChromeColors.DisabledAlpha] is the
     * one value droidtop's chrome fades a disabled label by, and it clears
     * 3:1 -- the floor for a UI component -- in both palettes.
     */
    @Test
    fun `a disabled label is still visible in both palettes`() {
        listOf("dark" to ChromeColors.Dark, "light" to ChromeColors.Light).forEach { (which, scheme) ->
            val faded = scheme.onSurface.copy(alpha = ChromeColors.DisabledAlpha)
            assertReadable("$which disabled label", faded, scheme.background, 3f)
            assertReadable("$which disabled label on a surface", faded, scheme.surface, 3f)
        }
    }
}
