package dev.droidtop.ui.shared

import dev.droidtop.library.theme.noticeColors
import dev.droidtop.library.theme.noticeInputs
import dev.droidtop.notices.ColorVision
import dev.droidtop.notices.NoticeChecks
import dev.droidtop.library.theme.EsDeThemeParser
import dev.droidtop.library.theme.GamingThemeMapping
import dev.droidtop.notices.NoticeColors
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The notice colours over every theme droidtop bundles, in every colour scheme each declares
 * (docs/SPEC.md 7k, "Notices"; Droidtop/tracker#485, final design tests 1 and 2): what the person sees
 * on a fresh install is held to the same contrast, hue and separation rules as droidtop's own palette.
 * Robolectric, because the theme parser reads XML through Android's pull parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticeThemesTest {

    private val themesDir = File("../shell-gamepad/src/main/assets/themes")

    /** The ground lightness where readable ink is confined to one end of the scale. */
    private val MID_TONE = 35.0..80.0

    private fun schemes(theme: File): List<String> {
        val capabilities = File(theme, "capabilities.xml").takeIf { it.isFile }?.readText() ?: return listOf("1")
        return Regex("<colorScheme\\s+name=\"([^\"]+)\"").findAll(capabilities).map { it.groupValues[1] }.toList().ifEmpty { listOf("1") }
    }

    @Test
    fun `every bundled theme and colour scheme passes the notice checks`() {
        val themes = themesDir.listFiles { f -> File(f, "theme.xml").isFile }.orEmpty().sortedBy { it.name }
        assertTrue("no bundled themes under ${themesDir.absolutePath}", themes.any { it.name.startsWith("decaffe") })
        for (theme in themes) {
            for (scheme in schemes(theme)) {
                val parsed = EsDeThemeParser.parse(File(theme, "theme.xml"), colorScheme = scheme)
                val colors = GamingThemeMapping.map(parsed)
                val label = "${theme.name} scheme $scheme"
                try {
                    val inputs = colors.noticeInputs()
                    val notice = colors.noticeColors()
                    assertEquals(emptyList<String>(), NoticeChecks.unreadable(inputs, notice, 4.5))
                    assertEquals(emptyList<String>(), NoticeChecks.hueMoved(inputs, notice))
                    // Contrast wins over separation only where it must: a mid-tone ground confines readable ink to one end.
                    if (notice.separated) {
                        assertEquals(emptyList<String>(), NoticeChecks.tooClose(notice))
                    } else {
                        val groundL = ColorVision.lab(colors.ground)[0]
                        assertTrue("not separated on a ground of L* $groundL", groundL in MID_TONE)
                    }
                    val hc = NoticeColors.HIGH_CONTRAST_FLOOR
                    assertEquals(emptyList<String>(), NoticeChecks.unreadable(inputs, colors.noticeColors(highContrast = true), hc))
                } catch (e: AssertionError) {
                    throw AssertionError("$label: ${e.message}", e)
                }
            }
        }
    }
}
