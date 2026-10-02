package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which category an installed app lands in (docs/SPEC.md 7j, "Mark as
 * game"): the seeded default and the person's override, and the store a
 * package name reads as.
 */
class AppFactsTest {
    private val plain = InstalledAppFacts()
    private val flagged = InstalledAppFacts(flaggedGame = true)
    private val system = InstalledAppFacts(system = true)

    @Test
    fun `Android's game flag and the store list seed Games, anything else is Other`() {
        val rules = AppCategoryRules()

        assertEquals(AppCategory.GAMES, rules.categoryOf("a.game", flagged))
        assertEquals(AppCategory.GAMES, rules.categoryOf("com.epicgames.portal", plain))
        assertEquals(AppCategory.OTHER, rules.categoryOf("a.tool", plain))
    }

    @Test
    fun `a known emulator is Emulators and a system app is System`() {
        val rules = AppCategoryRules(emulatorPackages = setOf("an.emulator"))

        assertEquals(AppCategory.EMULATORS, rules.categoryOf("an.emulator", flagged))
        assertEquals(AppCategory.SYSTEM, rules.categoryOf("com.android.settings", system))
    }

    @Test
    fun `the person's marks beat every default, both ways`() {
        val rules = AppCategoryRules(
            emulatorPackages = setOf("an.emulator"),
            markedGames = setOf("a.tool", "an.emulator"),
            markedNotGames = setOf("a.game", "com.epicgames.portal"),
        )

        assertEquals(AppCategory.GAMES, rules.categoryOf("a.tool", plain))
        assertEquals(AppCategory.GAMES, rules.categoryOf("an.emulator", plain))
        assertEquals(AppCategory.OTHER, rules.categoryOf("a.game", flagged))
        assertEquals(AppCategory.OTHER, rules.categoryOf("com.epicgames.portal", plain))
        assertTrue(rules.isGame("a.tool", plain))
        assertFalse(rules.isGame("a.game", flagged))
    }

    @Test
    fun `an entry that is not an installed app has no category`() {
        assertNull(AppCategoryRules().categoryOf("anything", null))
    }

    @Test
    fun `an installer reads as its store, no installer is a sideload, a system app is System`() {
        assertEquals("Play Store", appSourceLabel(InstalledAppFacts(installer = "com.android.vending")))
        assertEquals("Sideloaded", appSourceLabel(InstalledAppFacts(installer = null)))
        assertEquals("Sideloaded", appSourceLabel(InstalledAppFacts(installer = "com.google.android.packageinstaller")))
        assertEquals("Other store", appSourceLabel(InstalledAppFacts(installer = "some.other.store")))
        assertEquals("System", appSourceLabel(InstalledAppFacts(system = true, installer = "com.android.vending")))
    }
}
