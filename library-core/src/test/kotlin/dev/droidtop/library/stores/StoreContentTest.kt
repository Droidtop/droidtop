package dev.droidtop.library.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The content picker's pure rules (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313). */
class StoreContentTest {
    private fun branch(id: String, locked: Boolean = false, unlocked: Boolean = false, selected: Boolean = false) =
        StoreBranch(id, id, locked, unlocked, build = null, updatedMs = null, selected = selected)

    private val options = StoreContentOptions(
        extras = listOf(
            StoreExtra("1", "Installed on", 100_000_000L, selected = true, installed = true),
            StoreExtra("2", "Off and absent", 2_500_000_000L, selected = false, installed = false),
            StoreExtra("3", "On, not yet here", 300_000_000L, selected = true, installed = false),
        ),
        branches = listOf(branch("public", selected = true), branch("beta", locked = true), branch("open")),
        installed = true,
    )

    @Test
    fun `the current choice is read off the options`() {
        assertEquals(StoreContentChoice(setOf("1", "3"), "public"), StoreContent.current(options))
    }

    @Test
    fun `toggling flips one extra`() {
        val start = StoreContent.current(options)
        assertEquals(setOf("1", "2", "3"), StoreContent.toggled(start, "2").extraIds)
        assertEquals(setOf("3"), StoreContent.toggled(start, "1").extraIds)
    }

    @Test
    fun `only extras that are on and not yet installed add a download`() {
        assertEquals(300_000_000L, StoreContent.downloadBytes(options, StoreContent.current(options)))
        val all = StoreContent.current(options).copy(extraIds = setOf("1", "2", "3"))
        assertEquals(2_800_000_000L, StoreContent.downloadBytes(options, all))
    }

    @Test
    fun `installed extras that are turned off are the ones removed`() {
        val none = StoreContent.current(options).copy(extraIds = emptySet())
        assertEquals(listOf("1"), StoreContent.removedExtras(options, none).map { it.id })
    }

    @Test
    fun `a locked branch is usable once its password was accepted`() {
        val locked = StoreContentChoice(emptySet(), "beta")
        assertFalse(StoreContent.branchUsable(options, locked))
        val unlocked = options.copy(branches = options.branches.map { if (it.id == "beta") it.copy(unlocked = true) else it })
        assertTrue(StoreContent.branchUsable(unlocked, locked))
        assertTrue(StoreContent.branchUsable(options, StoreContentChoice(emptySet(), "open")))
    }

    @Test
    fun `a choice that is the current one does not differ`() {
        assertFalse(StoreContent.differs(options, StoreContent.current(options)))
        assertTrue(StoreContent.differs(options, StoreContent.current(options).copy(branchId = "open")))
    }

    @Test
    fun `sizes read as megabytes below a gigabyte and gigabytes above, blank for nothing`() {
        assertEquals("", StoreContent.sizeLabel(0))
        assertEquals("1 MB", StoreContent.sizeLabel(10))
        assertEquals("300 MB", StoreContent.sizeLabel(300_000_000L))
        assertEquals("2.5 GB", StoreContent.sizeLabel(2_500_000_000L))
    }

    @Test
    fun `a store with one branch and no extras has nothing to choose`() {
        assertTrue(StoreContentOptions(emptyList(), listOf(branch("public", selected = true)), installed = false).isEmpty)
        assertFalse(options.isEmpty)
    }
}
