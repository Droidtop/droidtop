package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** HKR's modern themes and "Light and dark" (Droidtop/tracker#903 H12b), as pure rules. */
class KeyboardThemesTest {
    private val modern = intArrayOf(
        KeyboardAppearance.FOLLOW, KeyboardAppearance.DYNAMIC, KeyboardAppearance.HIGH_CONTRAST, KeyboardAppearance.MATCH_HOST,
    )

    @Test fun onlyTheNewIdsAreModern() {
        for (id in modern) assertTrue(KeyboardAppearance.modern(id))
        for (id in 0..7) assertFalse(KeyboardAppearance.modern(id))
        assertFalse(KeyboardAppearance.modern(12))
    }

    @Test fun aModernThemeDrawsOverMaterialLightOrDarkAndAClassicOneStays() {
        for (id in modern) {
            assertEquals(KeyboardAppearance.MATERIAL_LIGHT, KeyboardAppearance.resolve(id, light = true))
            assertEquals(KeyboardAppearance.MATERIAL_DARK, KeyboardAppearance.resolve(id, light = false))
        }
        for (id in 0..7) for (light in listOf(true, false)) assertEquals(id, KeyboardAppearance.resolve(id, light))
    }

    @Test fun lightAndDarkFollowsTheSettingTheHostOrTheSystem() {
        val f = KeyboardAppearance.FOLLOW
        // Follow the system: the host's answer when there is one (droidtop's Light or dark, #562), else the system's.
        assertTrue(KeyboardAppearance.lightFor(f, KeyboardAppearance.FOLLOW_SYSTEM, false, hostLight = null, systemLight = true))
        assertFalse(KeyboardAppearance.lightFor(f, KeyboardAppearance.FOLLOW_SYSTEM, false, hostLight = false, systemLight = true))
        assertTrue(KeyboardAppearance.lightFor(f, null, false, hostLight = null, systemLight = true))
        // Always light, always dark: whatever the host and system say.
        for (host in listOf(true, false, null)) for (system in listOf(true, false)) {
            assertTrue(KeyboardAppearance.lightFor(f, KeyboardAppearance.ALWAYS_LIGHT, false, host, system))
            assertFalse(KeyboardAppearance.lightFor(f, KeyboardAppearance.ALWAYS_DARK, false, host, system))
        }
    }

    @Test fun matchDroidtopAndAnEmbeddedKeyboardFollowTheHostWhateverTheSetting() {
        for (choice in listOf(KeyboardAppearance.ALWAYS_LIGHT, KeyboardAppearance.ALWAYS_DARK)) {
            assertFalse(KeyboardAppearance.lightFor(KeyboardAppearance.MATCH_HOST, choice, false, hostLight = false, systemLight = true))
            assertTrue(KeyboardAppearance.lightFor(KeyboardAppearance.MATCH_HOST, choice, false, hostLight = true, systemLight = false))
            assertTrue(KeyboardAppearance.lightFor(KeyboardAppearance.FOLLOW, choice, true, hostLight = true, systemLight = false))
            assertFalse(KeyboardAppearance.lightFor(0, choice, true, hostLight = null, systemLight = false))
        }
    }

    @Test fun everyPresetReadsAtFourAndAHalfToOne() {
        for (id in modern + (0..7).toList()) for (light in listOf(true, false)) {
            val c = KeyboardAppearance.ownColors(id, light, null)
            assertTrue("$id $light ${KeyContrast.worstText(c)}", KeyContrast.worstText(c) >= KeyContrast.TEXT)
            assertTrue("$id $light ${KeyContrast.worstHint(c)}", KeyContrast.worstHint(c) >= KeyContrast.HINT)
        }
        // HKR's own palettes read as designed, with nothing adjusted.
        for (p in listOf(KeyboardAppearance.LIGHT, KeyboardAppearance.DARK, KeyboardAppearance.HIGH_CONTRAST_LIGHT, KeyboardAppearance.HIGH_CONTRAST_DARK)) {
            assertSame(p, KeyContrast.readable(p))
        }
    }

    @Test fun highContrastReadsAtSevenToOneOnLetters() {
        for (c in listOf(KeyboardAppearance.HIGH_CONTRAST_LIGHT, KeyboardAppearance.HIGH_CONTRAST_DARK)) {
            assertTrue(KeyContrast.onPlate(c.text, c.key, c.ground) >= 7.0)
        }
    }

    @Test fun dynamicWithNoSystemPaletteIsModern() {
        assertEquals(KeyboardAppearance.LIGHT, KeyboardAppearance.ownColors(KeyboardAppearance.DYNAMIC, true, null))
        assertEquals(KeyboardAppearance.DARK, KeyboardAppearance.ownColors(KeyboardAppearance.DYNAMIC, false, null))
        val dynamic = KeyboardAppearance.HIGH_CONTRAST_DARK
        assertEquals(dynamic, KeyboardAppearance.ownColors(KeyboardAppearance.DYNAMIC, false, dynamic))
        // Only Dynamic takes the system palette.
        assertEquals(KeyboardAppearance.LIGHT, KeyboardAppearance.ownColors(KeyboardAppearance.FOLLOW, true, dynamic))
    }
}
