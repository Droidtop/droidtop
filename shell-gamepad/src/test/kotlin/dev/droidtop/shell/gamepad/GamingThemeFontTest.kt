package dev.droidtop.shell.gamepad

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A theme's all-capitals font never reaches the roles that carry data (Droidtop/tracker#532). */
class GamingThemeFontTest {
    private val proportional = mapOf('i' to 4f, 'm' to 12f)

    @Test
    fun aFontWhoseSmallLettersAreCapitalsIsCapsOnly() {
        assertTrue(isCapsOnlyFont { c -> proportional[c.lowercaseChar()] ?: 7f })
    }

    @Test
    fun anOrdinaryFontOrAMonospacedOneIsNot() {
        assertFalse(isCapsOnlyFont { c -> (proportional[c.lowercaseChar()] ?: 7f) + if (c.isUpperCase()) 1f else 0f })
        assertFalse(isCapsOnlyFont { 8f })
    }

    @Test
    fun aCapsOnlyBodyFaceKeepsTheChromeAndLeavesDataInDroidtopsOwn() {
        val face = FontFamily.Monospace
        val own = Typography().bodyMedium.fontFamily
        val themed = Typography().withFamilies(body = face, display = null, capsOnly = true)
        assertEquals(face, themed.labelLarge.fontFamily)
        assertEquals(face, themed.titleSmall.fontFamily)
        assertEquals(own, themed.bodyMedium.fontFamily)
        assertEquals(own, themed.bodySmall.fontFamily)
        assertEquals(own, themed.titleMedium.fontFamily)
    }

    @Test
    fun anOrdinaryBodyFaceGoesEverywhere() {
        val face = FontFamily.Monospace
        val themed = Typography().withFamilies(body = face, display = null)
        assertEquals(face, themed.bodyMedium.fontFamily)
        assertEquals(face, themed.titleMedium.fontFamily)
    }
}
