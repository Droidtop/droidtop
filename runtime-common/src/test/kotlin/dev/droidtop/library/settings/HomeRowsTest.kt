package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The person's pinned and hidden Home rows (HomeRows, Droidtop/tracker#611). */
class HomeRowsTest {
    @Test
    fun `pinning adds at the end, hiding unpins, showing clears both`() {
        var rules = HomeRows.Rules()
        rules = HomeRows.with(rules, "a", HomeRows.PINNED)
        rules = HomeRows.with(rules, "b", HomeRows.PINNED)
        assertEquals(listOf("a", "b"), rules.pinned)
        rules = HomeRows.with(rules, "a", HomeRows.HIDDEN)
        assertEquals(listOf("b"), rules.pinned)
        assertEquals(setOf("a"), rules.hidden)
        rules = HomeRows.with(rules, "a", HomeRows.SHOWN)
        assertEquals(HomeRows.Rules(listOf("b"), emptySet()), rules)
    }

    @Test
    fun `placing a pinned row swaps it with the one in that place`() {
        val rules = HomeRows.Rules(pinned = listOf("a", "b", "c"))
        assertEquals(listOf("c", "b", "a"), HomeRows.place(rules, 0, "c").pinned)
        assertEquals(rules, HomeRows.place(rules, 5, "c"))
        assertEquals(rules, HomeRows.place(rules, 0, "x"))
    }

    @Test
    fun `moving a pinned row steps it one place, and an unpinned row or an end stays`() {
        val rules = HomeRows.Rules(pinned = listOf("a", "b", "c"))
        assertEquals(listOf("a", "c", "b"), HomeRows.move(rules, "c", -1).pinned)
        assertEquals(listOf("b", "a", "c"), HomeRows.move(rules, "a", 1).pinned)
        assertEquals(rules, HomeRows.move(rules, "a", -1))
        assertEquals(rules, HomeRows.move(rules, "c", 1))
        assertEquals(rules, HomeRows.move(rules, "x", 1))
    }
}
