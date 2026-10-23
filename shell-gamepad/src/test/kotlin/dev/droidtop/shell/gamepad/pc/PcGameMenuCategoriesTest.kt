package dev.droidtop.shell.gamepad.pc

import org.junit.Assert.assertEquals
import org.junit.Test

/** The game menu's categories (docs/SPEC.md 7i, "The game menu"; Droidtop/tracker#610). */
class PcGameMenuCategoriesTest {
    @Test
    fun `the top page lists the owner's categories in order and never the top itself`() {
        assertEquals(
            listOf("Info and art", "Organise", "Install and files", "Updates", "How it runs", "Controls and saves"),
            PcMenuPage.categories.map { it.title },
        )
    }

    @Test
    fun `a category row names what is inside it`() {
        assertEquals("Scrape, Choose match", categoryLine(listOf("Scrape", "Choose match")))
        assertEquals("Scrape, Choose match, View media, and more", categoryLine(listOf("Scrape", "Choose match", "View media", "Title")))
    }
}
