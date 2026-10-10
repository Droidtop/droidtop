package dev.droidtop.library.computers

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The person's organization of a game, as the marks it travels as (Droidtop/tracker#469 part 1). */
@RunWith(RobolectricTestRunner::class)
class ComputerMarksTest {
    @Test
    fun `a game's organization becomes its marks, unset ones included`() {
        val a = LibraryEntry(id = "/games/Celeste", title = "Celeste", kind = LibraryEntryKind.UNITY, favorite = true, rating = 0.8f, sortName = "Celeste 1", kidGame = true, gameName = "Celeste (mine)")
        val b = LibraryEntry(id = "/games/Other", title = "Other", kind = LibraryEntryKind.UNITY)
        val marks = ComputerLibrary.marksOf(listOf(a, b), mapOf("/games/Celeste" to listOf("Platformers", "Done")))
        val celeste = marks.getJSONObject(ComputerLibrary.keyOf(a))
        assertEquals(true, celeste.getBoolean("favourite"))
        assertEquals(0.8, celeste.getDouble("rating"), 0.001)
        assertEquals("Celeste (mine)", celeste.getString("title"))
        assertEquals("Celeste 1", celeste.getString("sort_name"))
        assertEquals(true, celeste.getBoolean("kid_game"))
        assertEquals(listOf("Done", "Platformers"), celeste.getJSONArray("collections").let { j -> (0 until j.length()).map { j.getString(it) } })
        val other = marks.getJSONObject(ComputerLibrary.keyOf(b))
        assertEquals(0.0, other.getDouble("rating"), 0.0)
        assertEquals("", other.getString("title"))
        assertEquals(0, other.getJSONArray("collections").length())
    }
}
