package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scrape pass names the games it did not fill (Droidtop/tracker#374). */
class ScrapeMissesTest {

    @Test
    fun `nothing missed adds nothing to the sentence`() {
        val misses = ScrapeMisses()
        assertTrue(misses.isEmpty)
        assertEquals("", misses.sentences())
    }

    @Test
    fun `each bucket names its games with the reason`() {
        val misses = ScrapeMisses().apply {
            add(ScrapeMisses.Kind.NO_MATCH, "Astra Ardent Show")
            add(ScrapeMisses.Kind.NEEDS_PICKING, "BeingADIK", "3 candidates")
            add(ScrapeMisses.Kind.FAILED, "30YearOldVirgin", "timeout")
        }
        assertEquals(
            "\nNo match: Astra Ardent Show." +
                "\nNeeds your pick (Choose match on the game): BeingADIK (3 candidates)." +
                "\nFailed: 30YearOldVirgin (timeout).",
            misses.sentences(),
        )
    }

    @Test
    fun `a long list names the first few and counts the rest`() {
        val misses = ScrapeMisses().apply { (1..9).forEach { add(ScrapeMisses.Kind.NO_MATCH, "Game $it") } }
        val text = misses.sentences()
        assertTrue(text, text.contains("Game 6"))
        assertTrue(text, !text.contains("Game 7"))
        assertTrue(text, text.contains("and 3 more (all are in scan.log)."))
    }

    @Test
    fun `the PC summary and the ROM summary carry the names`() {
        val counts = PcScrapeCounts(targeted = 3).apply {
            attempted = 3
            byName = 2
            noMatch = 1
            misses.add(ScrapeMisses.Kind.NO_MATCH, "Obscure Game")
        }
        val pc = formatPcScrapeSummary("Lutris", counts)
        assertTrue(pc, pc.contains("1 had no result at all"))
        assertTrue(pc, pc.endsWith("\nNo match: Obscure Game."))

        val rom = formatScrapeSummary(
            systemName = "Game Boy Advance",
            targeted = 1,
            attempted = 1,
            found = 0,
            hashMatched = 0,
            thumbnailed = 0,
            miximaged = 0,
            failed = 0,
            refused = 0,
            lastRefusal = null,
            misses = ScrapeMisses().apply { add(ScrapeMisses.Kind.NO_MATCH, "Pokemon.gba") },
        )
        assertTrue(rom, rom.contains("no match for 1"))
        assertTrue(rom, rom.endsWith("\nNo match: Pokemon.gba."))
    }
}
