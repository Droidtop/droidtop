package dev.droidtop.ui.shared

import dev.droidtop.library.theme.ColorVision
import dev.droidtop.library.theme.GamingThemeColors
import dev.droidtop.library.theme.Hsl
import dev.droidtop.library.theme.NoticeColors
import dev.droidtop.library.theme.ThemeColorMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notice colours (docs/SPEC.md 7k, "Notices"; Droidtop/tracker#485, final design tests 1 and 2):
 * every tone reads on its own plate and on the surfaces it is drawn over, at 4.5:1 and at 7:1 with
 * high-contrast text; caution and danger keep their hue; danger stays apart from caution under the three
 * simulated dichromacies; info falls back to neutral ink when the accent would read as caution.
 * [NoticeThemesTest] runs the same checks over every bundled theme.
 */
class NoticeColorsTest {

    private val dark = GamingThemeColors.DEFAULT

    /** A light theme as the mapping builds one from a light ground and dark text. */
    private val light = GamingThemeColors.DEFAULT.copy(
        ground = 0xFFF2F2F2.toInt(),
        overlaySurface = 0xFFE4E4E4.toInt(),
        card = 0xFFE0E0E0.toInt(),
        onSurface = 0xFF111111.toInt(),
        onSurfaceMuted = 0xFF555555.toInt(),
        accent = 0xFF1A5FB4.toInt(),
        danger = 0xFFB3261E.toInt(),
        declared = true,
    )

    @Test
    fun `droidtop's own palette reads at AA`() = NoticeAssertions.assertReadable(dark, NoticeColors.derive(dark), 4.5)

    @Test
    fun `a light palette reads at AA`() = NoticeAssertions.assertReadable(light, NoticeColors.derive(light), 4.5)

    @Test
    fun `high-contrast text raises every pair to 7 to 1`() {
        NoticeAssertions.assertReadable(dark, NoticeColors.derive(dark, highContrast = true), NoticeColors.HIGH_CONTRAST_FLOOR)
        NoticeAssertions.assertReadable(light, NoticeColors.derive(light, highContrast = true), NoticeColors.HIGH_CONTRAST_FLOOR)
    }

    @Test
    fun `caution and danger keep their hue`() {
        NoticeAssertions.assertHueLocked(dark, NoticeColors.derive(dark))
        NoticeAssertions.assertHueLocked(light, NoticeColors.derive(light))
    }

    @Test
    fun `danger stays apart from caution under every simulated dichromacy`() {
        assertTrue(NoticeColors.derive(dark).separated)
        assertTrue(NoticeColors.derive(light).separated)
        NoticeAssertions.assertSeparated(NoticeColors.derive(dark))
        NoticeAssertions.assertSeparated(NoticeColors.derive(light))
    }

    @Test
    fun `an amber accent gives info neutral ink, a blue one keeps the accent`() {
        val amber = dark.copy(accent = 0xFFFFB84D.toInt(), declared = true)
        val notice = NoticeColors.derive(amber)
        assertTrue(notice.infoNeutral)
        NoticeAssertions.assertReadable(amber, notice, 4.5)
        val blue = NoticeColors.derive(dark)
        assertFalse(blue.infoNeutral)
    }

    @Test
    fun `caution is the fixed amber on a dark ground`() {
        assertEquals(NoticeColors.CAUTION_DARK, NoticeColors.derive(dark).cautionInk)
    }

    @Test
    fun `hsl round-trips a colour`() {
        listOf(0xFFFFC56B.toInt(), 0xFF8A5A00.toInt(), 0xFFFFB4AB.toInt(), 0xFF1A5FB4.toInt()).forEach { c ->
            val hsl = Hsl.toHsl(c)
            val back = Hsl.fromHsl(hsl[0], hsl[1], hsl[2])
            listOf(ThemeColorMath::red, ThemeColorMath::green, ThemeColorMath::blue).forEach { channel ->
                assertTrue(Math.abs(channel(c) - channel(back)) <= 1)
            }
        }
    }
}

/** The checks this test and [NoticeThemesTest] share. */
object NoticeAssertions {
    fun assertReadable(c: GamingThemeColors, n: NoticeColors, floor: Double) {
        val m = ThemeColorMath
        val surfaces = listOf(c.ground, c.overlaySurface, c.card)
        fun check(name: String, fg: Int, bgs: List<Int>) {
            bgs.forEach { bg ->
                val r = m.ratio(fg, bg)
                assertTrue("$name is $r:1 on ${Integer.toHexString(bg)} (floor $floor)", r >= floor - 0.01)
            }
        }
        check("info ink", n.infoInk, surfaces + n.infoPlate)
        check("caution ink", n.cautionInk, surfaces + n.cautionPlate)
        check("danger ink", n.dangerInk, surfaces + n.dangerPlate)
        check("on info", n.onInfo, listOf(n.infoPlate))
        check("on caution", n.onCaution, listOf(n.cautionPlate))
        check("on danger", n.onDanger, listOf(n.dangerPlate))
        check("on danger fill", n.onDangerFill, listOf(n.dangerFill))
        check("muted", n.muted, listOf(n.infoPlate, n.cautionPlate, n.dangerPlate))
        // The plates are opaque: what is measured is what is drawn.
        listOf(n.infoPlate, n.cautionPlate, n.dangerPlate).forEach { assertEquals(0xFF, m.alpha(it)) }
    }

    fun assertHueLocked(c: GamingThemeColors, n: NoticeColors) {
        fun hue(x: Int) = Hsl.toHsl(x)[0]
        fun apart(a: Double, b: Double) = Math.abs(a - b).let { if (it > 180) 360 - it else it }
        val cautionBase = if (c.isLight) NoticeColors.CAUTION_LIGHT else NoticeColors.CAUTION_DARK
        if (Hsl.toHsl(n.cautionInk)[2] in 0.04..0.96) {
            assertTrue("caution hue moved", apart(hue(n.cautionInk), hue(cautionBase)) <= 3.0)
        }
        if (Hsl.toHsl(c.danger)[1] > 0.1 && Hsl.toHsl(n.dangerInk)[2] in 0.04..0.96) {
            assertTrue("danger hue moved", apart(hue(n.dangerInk), hue(c.danger)) <= 3.0)
        }
    }

    fun assertSeparated(n: NoticeColors) {
        ColorVision.SIMULATIONS.forEach { sim ->
            val apart = ColorVision.lightnessApart(n.dangerInk, n.cautionInk, sim)
            assertTrue("danger and caution are $apart L* apart under $sim", apart >= NoticeColors.NEAR_LSTAR - 0.01)
        }
    }
}
