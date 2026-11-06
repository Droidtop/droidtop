package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The theme list (Droidtop/tracker#903 H12b): modern themes first, then every classic Hacker's Keyboard theme. */
class ThemePresetsTest {
    private val modern = KeyboardAppearance.FOLLOW

    @Test fun androidTwelveWithAHostOffersEverything() {
        assertArrayEquals(
            intArrayOf(8, 9, 10, 11, 0, 6, 7, 4, 5, 1, 2, 3),
            ThemePresets.ids(31, hosted = true, stored = modern),
        )
    }

    @Test fun dynamicNeedsAndroidTwelveAndMatchNeedsAHost() {
        val ids = ThemePresets.ids(30, hosted = false, stored = modern).toList()
        assertFalse(KeyboardAppearance.DYNAMIC in ids)
        assertFalse(KeyboardAppearance.MATCH_HOST in ids)
        assertEquals(listOf(8, 10), ids.take(2))
    }

    @Test fun aStoredThemeStaysListedWhereItIsNotOffered() {
        assertTrue(KeyboardAppearance.DYNAMIC in ThemePresets.ids(29, hosted = false, stored = KeyboardAppearance.DYNAMIC).toList())
        assertTrue(KeyboardAppearance.MATCH_HOST in ThemePresets.ids(34, hosted = false, stored = KeyboardAppearance.MATCH_HOST).toList())
    }

    @Test fun everyClassicThemeIsKeptOnceWhateverTheDevice() {
        for (sdk in listOf(26, 30, 31, 35)) for (hosted in listOf(true, false)) {
            val ids = ThemePresets.ids(sdk, hosted, modern).toList()
            assertEquals(ids.size, ids.toSet().size)
            assertEquals((0..7).toSet(), ids.filter { !KeyboardAppearance.modern(it) }.toSet())
            assertEquals(modern, ids.first())
        }
    }
}
