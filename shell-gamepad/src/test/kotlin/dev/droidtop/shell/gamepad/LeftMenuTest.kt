package dev.droidtop.shell.gamepad

import dev.droidtop.library.settings.UiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The left menu's rows and where its cursor starts (docs/SPEC.md 7j, "Gaming controls" and "Places"). */
class LeftMenuTest {

    private val all = leftMenuEntries(menuSectionsFor(UiMode.FULL))

    @Test
    fun `every destination the mode allows is a row, named as the top bar names it`() {
        assertEquals(menuSectionsFor(UiMode.FULL), all.map { it.section })
        assertEquals(menuSectionsFor(UiMode.FULL).map { it.displayName() }, all.map { it.label })
        assertEquals(GamingSection.entries.toSet(), all.map { it.section }.toSet())
    }

    @Test
    fun `the tabs come first, then the places, and Settings is last`() {
        assertEquals(
            listOf(
                GamingSection.GAMES, GamingSection.PC_GAMES, GamingSection.APPS,
                GamingSection.STORES, GamingSection.DOWNLOADS, GamingSection.UPDATES, GamingSection.PLUGINS,
                GamingSection.SETTINGS,
            ),
            menuSectionsFor(UiMode.FULL),
        )
    }

    @Test
    fun `the places are not tabs`() {
        assertEquals(
            listOf(GamingSection.GAMES, GamingSection.PC_GAMES, GamingSection.APPS, GamingSection.SETTINGS),
            sectionsFor(UiMode.FULL),
        )
    }

    @Test
    fun `every place names a registered screen and no tab does`() {
        GamingSection.entries.forEach { section ->
            assertEquals(section.name, !section.inTopBar, section.placeScreenId != null)
        }
    }

    @Test
    fun `a mode that hides Settings has no Settings row and no places`() {
        listOf(UiMode.KIOSK, UiMode.KID).forEach { mode ->
            val rows = menuSectionsFor(mode)
            assertFalse(rows.any { it.managesDevice })
            assertEquals(listOf(GamingSection.GAMES, GamingSection.PC_GAMES, GamingSection.APPS), rows)
            assertEquals(rows, sectionsFor(mode))
        }
    }

    @Test
    fun `the cursor opens on the destination the user is on`() {
        all.forEachIndexed { index, entry ->
            assertEquals(index, leftMenuStartIndex(all, entry.section))
        }
    }

    @Test
    fun `a destination the mode hides starts at the top`() {
        val kiosk = leftMenuEntries(menuSectionsFor(UiMode.KIOSK))
        assertEquals(0, leftMenuStartIndex(kiosk, GamingSection.SETTINGS))
        assertEquals(0, leftMenuStartIndex(kiosk, GamingSection.STORES))
    }

    @Test
    fun `Settings and the places manage the device`() {
        assertTrue(GamingSection.SETTINGS.managesDevice)
        assertTrue(GamingSection.STORES.managesDevice)
        assertFalse(GamingSection.GAMES.managesDevice)
    }
}
