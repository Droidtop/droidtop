package dev.droidtop.library.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A remembered ProtonDB answer survives the round trip through the prefs
 * store exactly: the tier and its report count when there was one, and
 * the refusal sentence when there was not (docs/SPEC.md 7i -- an answer
 * the person asked for is a fact, and a remembered refusal must never
 * read as silence).
 */
class ProtonDbMemoryTest {

    @Test
    fun `a summary answer round trips with every field`() {
        val answer = ProtonDbMemory.Answer(
            appId = 440L,
            tier = "gold",
            total = 12,
            confidence = "high",
            trendingTier = "platinum",
            askedAtEpochMs = 1_700_000_000_000,
        )

        assertEquals(answer, ProtonDbMemory.decode(ProtonDbMemory.encode(answer)))
    }

    @Test
    fun `a refusal answer round trips with its sentence and no tier`() {
        val answer = ProtonDbMemory.Answer(
            appId = 440L,
            tier = null,
            total = 0,
            confidence = null,
            trendingTier = null,
            askedAtEpochMs = 1_700_000_000_000,
            line = "ProtonDB has no reports for this game yet",
        )

        val decoded = ProtonDbMemory.decode(ProtonDbMemory.encode(answer))
        assertEquals(answer, decoded)
        assertEquals("ProtonDB: ProtonDB has no reports for this game yet", decoded!!.summaryLine())
    }

    @Test
    fun `a summary line names the tier and the reports behind it`() {
        val answer = ProtonDbMemory.Answer(
            appId = 440L,
            tier = "platinum",
            total = 1,
            confidence = null,
            trendingTier = null,
            askedAtEpochMs = 0,
        )

        assertEquals("ProtonDB: platinum - 1 report", answer.summaryLine())
    }

    @Test
    fun `anything that is not one of our answers is nothing, not an error`() {
        assertNull(ProtonDbMemory.decode(null))
        assertNull(ProtonDbMemory.decode(""))
        assertNull(ProtonDbMemory.decode("not json at all"))
    }
}
