package dev.droidtop.shell.gamepad

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MenuTokens is a palette against one surface (see its doc comment), so
 * the thing worth asserting is that every colour the menus put TEXT in is
 * readable over the surfaces the menus paint. The Quick Menu's System tab
 * shipped light grey on white because the panel was painted from the
 * platform's colour scheme instead of this palette; a contrast floor is
 * what makes that a test failure rather than a screenshot someone has to
 * notice.
 *
 * 4.5:1 is WCAG AA for body text; 3:1 is AA for large/secondary text,
 * which is what the muted tokens are used for.
 */
class MenuTokensContrastTest {

    private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun luminance(color: Color): Float =
        0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)

    /** [over] must be opaque; [color] is composited onto it first, as it is on screen. */
    private fun contrast(color: Color, over: Color): Float {
        val a = color.alpha
        val flat = Color(
            red = color.red * a + over.red * (1 - a),
            green = color.green * a + over.green * (1 - a),
            blue = color.blue * a + over.blue * (1 - a),
        )
        val l1 = luminance(flat)
        val l2 = luminance(over)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private fun assertReadable(name: String, color: Color, over: Color, floor: Float) {
        val ratio = contrast(color, over)
        assertTrue("$name over this surface is $ratio:1, below $floor:1", ratio >= floor)
    }

    @Test
    fun `body text is readable on the overlay surface`() {
        val surface = MenuTokens.OverlaySurface
        assertReadable("OnSurface", MenuTokens.OnSurface, surface, 4.5f)
        assertReadable("Value", MenuTokens.Value, surface, 4.5f)
        assertReadable("Accent", MenuTokens.Accent, surface, 4.5f)
        assertReadable("Danger", MenuTokens.Danger, surface, 4.5f)
        assertReadable("Affirmative", MenuTokens.Affirmative, surface, 4.5f)
    }

    @Test
    fun `secondary text clears the large-text floor on the overlay surface`() {
        val surface = MenuTokens.OverlaySurface
        assertReadable("OnSurfaceMuted", MenuTokens.OnSurfaceMuted, surface, 3f)
        assertReadable("Placeholder", MenuTokens.Placeholder, surface, 3f)
        assertReadable("SectionLabel", MenuTokens.SectionLabel, surface, 3f)
    }

    @Test
    fun `a tile or row fill keeps its label readable, focused or not`() {
        // What a tile actually is: a translucent fill over the overlay
        // surface, with label text on top of that.
        val surface = MenuTokens.OverlaySurface
        listOf("Surface" to MenuTokens.Surface, "SurfaceSelected" to MenuTokens.SurfaceSelected).forEach { (name, fill) ->
            val a = fill.alpha
            val tile = Color(
                red = fill.red * a + surface.red * (1 - a),
                green = fill.green * a + surface.green * (1 - a),
                blue = fill.blue * a + surface.blue * (1 - a),
            )
            assertReadable("OnSurface over $name", MenuTokens.OnSurface, tile, 4.5f)
            assertReadable("OnSurfaceMuted over $name", MenuTokens.OnSurfaceMuted, tile, 3f)
        }
    }

    @Test
    fun `the palette would NOT be readable on a light platform surface`() {
        // The bug, written down: these tokens are not theme-aware, so any
        // panel hosting them must paint MenuTokens.OverlaySurface. White
        // label text on a white panel is 1:1.
        val white = Color(0xFFFFFFFF)
        assertTrue(contrast(MenuTokens.OnSurface, white) < 1.5f)
        assertTrue(contrast(MenuTokens.OnSurfaceMuted, white) < 3f)
    }
}
