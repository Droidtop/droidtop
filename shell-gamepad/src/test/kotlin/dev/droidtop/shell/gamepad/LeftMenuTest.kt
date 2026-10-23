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
    fun `Home is the first row and a section of its own`() {
        // Owner, 2026-10-23: "Home should NOT be the same section" (Droidtop/tracker#611).
        assertEquals(LeftMenuEntry(GamingSection.HOME, "Home"), all.first())
        assertEquals(listOf("Home", "PC Games", "Retro Games", "Apps"), all.take(4).map { it.label })
        assertEquals(all.size, all.map { it.key }.toSet().size)
    }

    @Test
    fun `the tabs come first, then the places, and Settings is last`() {
        assertEquals(
            listOf(
                GamingSection.HOME, GamingSection.PC_GAMES, GamingSection.GAMES, GamingSection.APPS,
                GamingSection.STORES, GamingSection.SOCIAL, GamingSection.DOWNLOADS, GamingSection.UPDATES,
                GamingSection.PLUGINS,
                GamingSection.SETTINGS,
            ),
            menuSectionsFor(UiMode.FULL),
        )
    }

    @Test
    fun `Get new games is a page of Updates, not a row of its own`() {
        // Owner, 2026-10-23: "THe "get new games" menu needs to be UNDER Updates" (Droidtop/tracker#621).
        assertFalse(GamingSection.NEW_GAMES in menuSectionsFor(UiMode.FULL))
        val updates = all.single { it.section == GamingSection.UPDATES }
        assertTrue(updates.isAt(GamingSection.NEW_GAMES))
        assertEquals(listOf(updates), all.filter { it.isAt(GamingSection.NEW_GAMES) })
    }

    @Test
    fun `the places are not tabs`() {
        assertEquals(
            listOf(GamingSection.HOME, GamingSection.GAMES, GamingSection.PC_GAMES, GamingSection.APPS, GamingSection.SETTINGS),
            sectionsFor(UiMode.FULL),
        )
    }

    @Test
    fun `every place names a registered screen and no tab does`() {
        GamingSection.entries.forEach { section ->
            assertEquals(section.name, section.isPlace, section.placeScreenId != null)
        }
    }

    @Test
    fun `a mode that hides Settings has no Settings row and no places`() {
        listOf(UiMode.KIOSK, UiMode.KID).forEach { mode ->
            val rows = menuSectionsFor(mode)
            assertFalse(rows.any { it.managesDevice })
            assertEquals(listOf(GamingSection.HOME, GamingSection.PC_GAMES, GamingSection.GAMES, GamingSection.APPS), rows)
            assertEquals(rows.toSet(), sectionsFor(mode).toSet())
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
    fun `with a game running the Resume row leads and the cursor still opens on where the user is`() {
        all.forEachIndexed { index, entry ->
            assertEquals(index + 1, leftMenuStartIndex(all, entry.section, rowsAbove = 1))
        }
    }

    @Test
    fun `Downloads has a dot while a job runs and Social while a message is unread`() {
        assertEquals(emptySet<GamingSection>(), leftMenuAttention(runningJobs = 0, unreadMessages = 0))
        assertEquals(setOf(GamingSection.DOWNLOADS), leftMenuAttention(runningJobs = 2, unreadMessages = 0))
        assertEquals(setOf(GamingSection.SOCIAL), leftMenuAttention(runningJobs = 0, unreadMessages = 1))
        assertEquals(setOf(GamingSection.DOWNLOADS, GamingSection.SOCIAL), leftMenuAttention(runningJobs = 1, unreadMessages = 3))
    }

    @Test
    fun `every row has its own icon`() {
        assertEquals(all.size, all.map { it.glyph().name }.toSet().size)
    }

    @Test
    fun `Settings and the places manage the device`() {
        assertTrue(GamingSection.SETTINGS.managesDevice)
        assertTrue(GamingSection.STORES.managesDevice)
        assertFalse(GamingSection.GAMES.managesDevice)
    }

    @Test
    fun `a link to a place's screen opens the place`() {
        val full = menuSectionsFor(UiMode.FULL)
        assertEquals(GamingSection.SOCIAL, placeForScreen(PLACE_SOCIAL_SCREEN_ID, full))
        assertEquals(GamingSection.UPDATES, placeForScreen(PLACE_UPDATES_SCREEN_ID, full))
        assertEquals(GamingSection.PLUGINS, placeForScreen(PLACE_PLUGINS_SCREEN_ID, full))
    }

    @Test
    fun `Search sits between Resume and the destinations, and the cursor still opens where the user is`() {
        assertEquals(1, leftMenuRowsAbove(running = false, searchable = true))
        assertEquals(2, leftMenuRowsAbove(running = true, searchable = true))
        assertEquals(0, leftMenuRowsAbove(running = false, searchable = false))
        all.forEachIndexed { index, entry ->
            assertEquals(index + 2, leftMenuStartIndex(all, entry.section, rowsAbove = leftMenuRowsAbove(true, true)))
        }
    }

    @Test
    fun `a row index says what the row is`() {
        assertTrue(leftMenuRowAt(0, running = true, searchable = true) is LeftMenuRow.Resume)
        assertTrue(leftMenuRowAt(1, running = true, searchable = true) is LeftMenuRow.Search)
        assertEquals(0, (leftMenuRowAt(2, running = true, searchable = true) as LeftMenuRow.Destination).index)
        assertTrue(leftMenuRowAt(0, running = false, searchable = true) is LeftMenuRow.Search)
        assertEquals(0, (leftMenuRowAt(0, running = false, searchable = false) as LeftMenuRow.Destination).index)
        assertEquals(2, (leftMenuRowAt(4, running = true, searchable = true) as LeftMenuRow.Destination).index)
    }

    @Test
    fun `a screen that is no place, or a place the mode hides, stays a nested screen`() {
        assertEquals(null, placeForScreen("android_settings", menuSectionsFor(UiMode.FULL)))
        assertEquals(null, placeForScreen(PLACE_UPDATES_SCREEN_ID, menuSectionsFor(UiMode.KIOSK)))
    }
}
