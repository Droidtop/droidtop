package dev.droidtop.library

import dev.droidtop.library.stores.StoreSyncs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recently added means added since a source's first sync or scan (docs/SPEC.md 7g, Droidtop/tracker#397 slice B). */
class SyncBaselineTest {

    private val steam = PcSource.Store("steam").id
    private val card = PcSource.Folder("/storage/card/Games").id

    @Test
    fun `a source's first sync sets the baseline and adds nothing`() {
        val first = SyncBaselines.update(emptyMap(), listOf(steam to 100L, steam to 120L, steam to 110L), now = 130L)
        assertEquals(120L, first.getValue(steam).seenUpTo)
        listOf(100L, 110L, 120L).forEach { assertFalse(SyncBaselines.isRecentlyAdded(steam, it, first)) }
    }

    @Test
    fun `a game a later sync brings is recently added, and only that one`() {
        val first = SyncBaselines.update(emptyMap(), listOf(steam to 100L, steam to 120L), now = 130L)
        val later = 130L + SyncBaselines.SETTLE_MS + 1
        val second = SyncBaselines.update(first, listOf(steam to 100L, steam to 120L, steam to later), now = later + 5)
        assertEquals(first, second)
        assertTrue(SyncBaselines.isRecentlyAdded(steam, later, second))
        assertFalse(SyncBaselines.isRecentlyAdded(steam, 120L, second))
    }

    @Test
    fun `a first folder scan that publishes a part at a time adds nothing either`() {
        val firstPart = SyncBaselines.update(emptyMap(), listOf(card to 1_000L), now = 1_000L)
        val rest = SyncBaselines.update(firstPart, listOf(card to 1_000L, card to 200_000L), now = 200_000L)
        assertFalse(SyncBaselines.isRecentlyAdded(card, 200_000L, rest))
    }

    @Test
    fun `sources are kept apart, and one with no baseline has added nothing`() {
        val baselines = SyncBaselines.update(emptyMap(), listOf(steam to 100L), now = 100L)
        assertFalse(SyncBaselines.isRecentlyAdded(card, 999_999L, baselines))
        assertFalse(SyncBaselines.isRecentlyAdded(steam, 0L, baselines))
    }

    @Test
    fun `the sync line says what changed, and a first read adds nothing`() {
        assertEquals("3 games", StoreSyncs.SyncChange.between(3, null, setOf("a", "b", "c")).line())
        assertEquals("3 games", StoreSyncs.SyncChange.between(3, emptySet(), setOf("a", "b", "c")).line())
        assertEquals("3 games: 1 new, 2 removed", StoreSyncs.SyncChange.between(3, setOf("a", "b", "x", "y"), setOf("a", "b", "c")).line())
        assertEquals("1,193 games", StoreSyncs.SyncChange(1193).line())
    }
}
