package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A group's expert rows sit behind one Advanced row (docs/SPEC.md "The Advanced row", mytop-project/tracker#964). */
class AdvancedRowsTest {
    @Before
    fun installWords() = WordsForTests.install()

    private fun row(id: String) = ActionItem(id = id, title = id)

    private val group = CatalogGroup(id = "g", title = null, items = listOf(row("a"), row("b")), advanced = listOf(row("x"), row("y")))

    @Test
    fun `closed, the group draws its rows then one Advanced row counting what it holds`() {
        val shown = group.shownItems(expanded = false) {}
        assertEquals(listOf("a", "b", CatalogGroup.advancedRowId("g")), shown.map { it.id })
        assertEquals("Advanced", shown.last().title)
        assertEquals("2", shown.last().value)
    }

    @Test
    fun `open, the advanced rows follow in place and the Advanced row is gone`() {
        assertEquals(listOf("a", "b", "x", "y"), group.shownItems(expanded = true) {}.map { it.id })
    }

    @Test
    fun `a group with no advanced rows draws no Advanced row`() {
        val plain = CatalogGroup(id = "p", title = null, items = listOf(row("a")))
        assertEquals(listOf("a"), plain.shownItems(expanded = false) {}.map { it.id })
    }

    @Test
    fun `search and lookups see every row, advanced included`() {
        assertEquals(listOf("a", "b", "x", "y"), group.allItems.map { it.id })
    }

    @Test
    fun `a mode that hides a row hides it behind Advanced too, and keeps a group that only has advanced rows`() {
        val onlyAdvanced = CatalogGroup(id = "o", title = null, items = emptyList(), advanced = listOf(row("z")))
        val filtered = ControlAccess.filter(UiMode.FULL, listOf(group, onlyAdvanced))
        assertEquals(listOf("g", "o"), filtered.map { it.id })
        assertEquals(listOf("z"), filtered.last().advanced.map { it.id })
    }
}
