package dev.droidtop.library.integrations

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIndexTest {
    @Test
    fun `titles fold to plain lower-case words, so a search matches however the source wrote them`() {
        assertEquals("doom ii hell on earth", SourceIndex.fold("DOOM II: Hell on Earth"))
        assertEquals("pokemon emerald", SourceIndex.fold("Pokémon  Emerald!"))
        assertEquals("", SourceIndex.fold("  ---  "))
    }

    @Test
    fun `each source has a file of its own, named safely from its id`() {
        // Droidtop/tracker#937: indexes are per source, because sources are turned on and off one by one.
        assertEquals("plugin_gamegrab.abandonware.db", SourceIndex.fileName("plugin_gamegrab.abandonware"))
        assertEquals("plugin_a_b.db", SourceIndex.fileName("plugin_a/b"))
    }
}
