package dev.droidtop.library.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveLinesTest {
    private val now = 1_800_000_000_000L

    private fun found(vararg places: FoundPlace) = SaveStatus.Found(places.toList(), emptyList(), emptyList(), false)

    @Test
    fun `times are said the way a person says them`() {
        assertEquals("just now", SaveLines.ago(now - 5_000, now))
        assertEquals("5 min ago", SaveLines.ago(now - 5 * 60_000L, now))
        assertEquals("3 h ago", SaveLines.ago(now - 3 * 3_600_000L, now))
        assertEquals("2 days ago", SaveLines.ago(now - 2 * 86_400_000L, now))
        assertEquals("4 months ago", SaveLines.ago(now - 125 * 86_400_000L, now))
        assertEquals("just now", SaveLines.ago(now + 10_000, now))
    }

    @Test
    fun `the three honest answers read differently`() {
        val place = FoundPlace("Enginehost's save folder", "/storage/emulated/0/Enginehost/saves/Demo-1", 3, 2048, now - 3_600_000L)
        assertEquals("3 files, 1 h ago", SaveLines.value(found(place), now))
        assertEquals("None found", SaveLines.value(SaveStatus.NoneFound(listOf("the game's folder")), now))
        assertEquals("No rule known", SaveLines.value(SaveStatus.NoRule("x"), now))
        assertEquals("Not known yet", SaveLines.value(SaveStatus.Unknown("x", emptyList()), now))
        assertEquals("Not looked for", SaveLines.value(SaveStatus.TurnedOff, now))
    }

    @Test
    fun `where and when are in the line under a find`() {
        val place = FoundPlace("Enginehost's save folder", "/storage/emulated/0/Enginehost/saves/Demo-1", 3, 2048, now - 3_600_000L)
        val line = SaveLines.subtitle(SaveReport(found(place), "renpy.py path_to_saves", "a Ren'Py game"), now)
        assertTrue(line, line.contains("Internal storage/Enginehost/saves/Demo-1"))
        assertTrue(line, line.contains("3 files"))
        assertTrue(line, line.contains("2 KB"))
        assertTrue(line, line.contains("newest 1 h ago"))
        assertTrue(line, line.contains("This game is a Ren'Py game."))
    }

    @Test
    fun `none found says where it looked and a missing rule says what would give one`() {
        val none = SaveLines.subtitle(SaveReport(SaveStatus.NoneFound(listOf("the game's folder", "Enginehost's save folder")), null, "a Ren'Py game"), now)
        assertTrue(none, none.startsWith("Looked in the game's folder, Enginehost's save folder, and found no saves."))
        val rule = SaveLines.subtitle(SaveReport(SaveStatus.NoRule("no save location is known for this game"), null, null), now)
        assertEquals("No save location is known for this game.", rule)
    }

    @Test
    fun `a part that could not be searched is said beside the find`() {
        val place = FoundPlace("the game's folder", "/storage/emulated/0/Games/X", 1, 10, now)
        val status = SaveStatus.Found(listOf(place), emptyList(), emptyList(), false, note = "droidtop could not work out the project's name")
        assertTrue(SaveLines.subtitle(SaveReport(status, null, null), now).contains("Not everywhere could be searched"))
    }

    @Test
    fun `save states are listed apart and said not to count`() {
        val state = FoundPlace("the emulator's save-state folder", "/s/states", 2, 100, now)
        val details = SaveLines.details(SaveReport(SaveStatus.NoneFound(listOf("x"), listOf(state)), "RetroArch", "played in RetroArch"), now)
        assertTrue(details.any { it.first.startsWith("Save states") && it.second.contains("Not counted as saves") })
        assertFalse(details.any { it.first.startsWith("Saves:") })
    }
}
