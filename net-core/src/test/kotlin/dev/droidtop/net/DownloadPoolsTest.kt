package dev.droidtop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pools (docs/SPEC.md 12a): one game at a time, the others by what the device can spare, and the person's own limits. */
class DownloadPoolsTest {
    private val wifi = Conditions(NetworkClass.UNMETERED, 12 * 60, gameRunning = false)

    @Test
    fun automaticLimitsFollowTheNetworkAGameAndBatterySaver() {
        assertEquals(1, DownloadPools.automaticLimit(DownloadCategory.GAMES, wifi))
        assertEquals(1, DownloadPools.automaticLimit(DownloadCategory.UPDATES, wifi))
        assertEquals(2, DownloadPools.automaticLimit(DownloadCategory.PLUGINS, wifi))
        assertEquals(6, DownloadPools.automaticLimit(DownloadCategory.ART, wifi))
        assertEquals(2, DownloadPools.automaticLimit(DownloadCategory.ART, wifi.copy(network = NetworkClass.METERED)))
        assertEquals(1, DownloadPools.automaticLimit(DownloadCategory.ART, wifi.copy(gameRunning = true)))
        assertEquals(1, DownloadPools.automaticLimit(DownloadCategory.SYSTEM, wifi.copy(batterySaver = true)))
    }

    @Test
    fun thePersonsLimitWinsAndOnlyAUsableOne() {
        val settings = DownloadSettings(poolLimits = mapOf("games" to 3, "art" to 99))
        assertEquals(3, DownloadPools.limit(settings, DownloadCategory.GAMES, wifi))
        assertEquals("a limit out of range is automatic", 6, DownloadPools.limit(settings, DownloadCategory.ART, wifi))
    }

    @Test
    fun aFullPoolQueuesOnlyItsOwnAndTheOldestRunFirst() {
        val entries = listOf(
            DownloadPools.Entry("g2", DownloadCategory.GAMES, 20, running = true),
            DownloadPools.Entry("g1", DownloadCategory.GAMES, 10, running = true),
            DownloadPools.Entry("a1", DownloadCategory.ART, 30, running = true),
        )
        val moves = DownloadPools.plan(entries, { if (it == DownloadCategory.GAMES) 1 else 6 })
        assertEquals(listOf<DownloadPools.Move>(DownloadPools.Move.Queue("g2", "Queued: one game download at a time")), moves)
    }

    @Test
    fun aFreedSlotStartsTheNextAndAPinnedDownloadKeepsItsSlot() {
        val queued = DownloadPools.Entry("g2", DownloadCategory.GAMES, 20, running = false)
        assertEquals(listOf<DownloadPools.Move>(DownloadPools.Move.Start("g2")), DownloadPools.plan(listOf(queued), { 1 }))
        val pinned = DownloadPools.Entry("g1", DownloadCategory.GAMES, 30, running = true, pinned = true)
        val line = DownloadPools.queuedLine(DownloadCategory.GAMES, 1)
        assertTrue("already queued with the same line: not moved", DownloadPools.plan(listOf(pinned, queued), { 1 }, { line }).isEmpty())
        assertTrue(DownloadPools.isQueued(line))
        assertFalse(DownloadPools.isQueued("Waiting for Wi-Fi"))
        assertEquals("Queued: 2 plugin downloads at a time", DownloadPools.queuedLine(DownloadCategory.PLUGINS, 2))
    }
}
