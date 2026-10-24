package dev.droidtop.library.identity

import dev.droidtop.library.identity.MatchScore.Candidate
import dev.droidtop.library.identity.MatchScore.Local
import dev.droidtop.library.identity.MatchScore.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchScoreTest {
    @Test fun `the slug and the parsed title both count as the local name`() {
        val local = Local("Seven Day W Yandere Girl", "SevenDayWYandereGirlV0.80")
        assertEquals(100, MatchScore.score(local, Candidate("1", "Seven Day W Yandere Girl")))
        assertEquals(100, MatchScore.score(Local("Being A Dik", "BeingADik"), Candidate("2", "Being a DIK")))
    }

    @Test fun `an alternate name matches like the primary one`() {
        assertEquals(100, MatchScore.score(Local("FF7"), Candidate("1", "Final Fantasy VII", alternates = listOf("FF7"))))
    }

    @Test fun `a longer title that starts with the local one stays below the bar`() {
        val score = MatchScore.score(Local("Hollow Knight"), Candidate("1", "Hollow Knight: Silksong"))
        assertTrue("$score", score <= MatchScore.SUBTITLE_CAP)
        assertEquals(Tier.C, MatchScore.verdict(Local("Hollow Knight"), listOf(Candidate("1", "Hollow Knight - Silksong"))).tier)
    }

    @Test fun `the year adds a little when it agrees and takes a lot when it does not`() {
        assertEquals(80, MatchScore.score(Local("Doom", year = 1993), Candidate("1", "DOOM", year = 2016)))
        assertEquals(100, MatchScore.score(Local("Doom", year = 1993), Candidate("1", "Doom", year = 1993)))
        assertEquals(100, MatchScore.score(Local("Doom", year = 1993), Candidate("1", "Doom", year = 1994)))
    }

    @Test fun `two exact names with nothing to tell them apart are asked, not guessed`() {
        val verdict = MatchScore.verdict(Local("Doom"), listOf(Candidate("a", "DOOM", year = 2016), Candidate("b", "Doom", year = 1993)))
        assertEquals(Tier.C, verdict.tier)
    }

    @Test fun `a candidate from another system is left out before scoring`() {
        val verdict = MatchScore.verdict(Local("Super Metroid", system = "snes"), listOf(Candidate("gba", "Super Metroid", system = "gba"), Candidate("snes", "Super Metroid", system = "snes")))
        assertEquals(Tier.B, verdict.tier)
        assertEquals("snes", verdict.best?.id)
        assertEquals(1, verdict.ranked.size)
    }

    @Test fun `tiers follow the bars`() {
        fun tier(vararg names: String) = MatchScore.verdict(Local("red blue green yellow"), names.mapIndexed { i, n -> Candidate("$i", n) }).tier
        assertEquals(Tier.B, tier("Red Blue Green Yellow"))
        assertEquals(Tier.C, tier("red blue green yellow purple")) // 2*4/9 = 88
        assertEquals(Tier.D, tier("red blue orange")) // 2*2/7 = 57
        assertEquals(Tier.E, tier("orange")) // 0
        assertEquals(Tier.E, MatchScore.verdict(Local("anything"), emptyList()).tier)
    }

    @Test fun `a plugin's own confidence stands in for the score`() {
        assertEquals(95, MatchScore.score(Local("x"), Candidate("1", "y", confidence = 0.95)))
        assertEquals(Tier.D, MatchScore.verdict(Local("x"), listOf(Candidate("1", "y", confidence = 0.5))).tier)
    }
}
