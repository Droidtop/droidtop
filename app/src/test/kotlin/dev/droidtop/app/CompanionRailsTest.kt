package dev.droidtop.app

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The companion Home's two rails (docs/SPEC.md, "The companion's tabs"): what they list and in what order. */
class CompanionRailsTest {
    private fun game(id: String, title: String = id, lastPlayed: Long? = null, firstSeen: Long = 0L) = LibraryEntry(
        id = id,
        title = title,
        kind = LibraryEntryKind.RENPY,
        firstSeenEpochMs = firstSeen,
        lastPlayedEpochMs = lastPlayed,
    )

    @Test
    fun `continue playing is newest first and only played games`() {
        val rail = companionRecents(listOf(game("/a", lastPlayed = 10), game("/b"), game("/c", lastPlayed = 20)))
        assertEquals(listOf("/c", "/a"), rail.map { it.id })
    }

    @Test
    fun `a game under two ids shows once`() {
        val rail = companionRecents(listOf(game("/a", title = "Same", lastPlayed = 10), game("/b", title = "same", lastPlayed = 20)))
        assertEquals(listOf("/b"), rail.map { it.id })
    }

    @Test
    fun `recently added is newest first and leaves out rows with no time`() {
        val rail = companionRecentlyAdded(listOf(game("/a", firstSeen = 5), game("/b"), game("/c", firstSeen = 9)))
        assertEquals(listOf("/c", "/a"), rail.map { it.id })
    }

    @Test
    fun `a rail is capped`() {
        val rail = companionRecentlyAdded((1..30).map { game("/g$it", firstSeen = it.toLong()) })
        assertEquals(10, rail.size)
        assertEquals("/g30", rail.first().id)
    }
}
