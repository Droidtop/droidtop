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

    /**
     * The shell's own pages draw the same text roles on [MenuTokens.Ground]
     * and on the cards laid on it (docs/SPEC.md 7k: every text-on-surface
     * pair is covered, not only the overlay's).
     */
    @Test
    fun `page text is readable on the ground and on every card`() {
        listOf(
            "Ground" to MenuTokens.Ground,
            "Card" to MenuTokens.Card,
            "CardFocused" to MenuTokens.CardFocused,
            "CardInset" to MenuTokens.CardInset,
            "HintBar" to MenuTokens.HintBar,
        ).forEach { (name, ground) ->
            assertReadable("OnSurface on $name", MenuTokens.OnSurface, ground, 4.5f)
            assertReadable("Value on $name", MenuTokens.Value, ground, 4.5f)
            assertReadable("Accent on $name", MenuTokens.Accent, ground, 4.5f)
            assertReadable("Danger on $name", MenuTokens.Danger, ground, 4.5f)
            assertReadable("Favourite on $name", MenuTokens.Favourite, ground, 4.5f)
            assertReadable("OnSurfaceMuted on $name", MenuTokens.OnSurfaceMuted, ground, 3f)
            assertReadable("OnSurfaceDisabled on $name", MenuTokens.OnSurfaceDisabled, ground, 3f)
        }
    }

    @Test
    fun `a chosen chip, the launch button and a danger plate keep their labels readable`() {
        assertReadable("OnSelected on Selected", MenuTokens.OnSelected, MenuTokens.Selected, 4.5f)
        listOf(MenuTokens.Launch, MenuTokens.LaunchFocused).forEach { launch ->
            assertReadable("OnSurface on the launch button", MenuTokens.OnSurface, launch, 4.5f)
            assertReadable("OnLaunchMuted on the launch button", MenuTokens.OnLaunchMuted, launch, 3f)
        }
        assertReadable("OnSurfaceDisabled on LaunchDisabled", MenuTokens.OnSurfaceDisabled, MenuTokens.LaunchDisabled, 3f)
        // The plate is translucent over the page, as it is on screen.
        val plate = MenuTokens.DangerPlate
        val onGround = Color(
            red = plate.red * plate.alpha + MenuTokens.Ground.red * (1 - plate.alpha),
            green = plate.green * plate.alpha + MenuTokens.Ground.green * (1 - plate.alpha),
            blue = plate.blue * plate.alpha + MenuTokens.Ground.blue * (1 - plate.alpha),
        )
        assertReadable("Danger on DangerPlate", MenuTokens.Danger, onGround, 4.5f)
    }

    @Test
    fun `the palette would NOT be readable on a light platform surface`() {
        // The bug, written down: these tokens are not theme-aware, so any
        // panel hosting them must paint MenuTokens.OverlaySurface. White
        // label text on a white panel is 1:1.
        val white = Color(0xFFFFFFFF)
        assertTrue(contrast(MenuTokens.OnSurface, white) < 1.5f)
        // 4.5:1, the body-text floor the other tests hold these tokens
        // to over their own surface. OnSurfaceMuted is 3.10:1 on white,
        // which clears the large-text floor by a hair and is nowhere
        // near readable as a row subtitle -- asserting < 3 here was
        // simply wrong about its own number.
        assertTrue(contrast(MenuTokens.OnSurfaceMuted, white) < 4.5f)
    }
}
