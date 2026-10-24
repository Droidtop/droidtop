package dev.droidtop.library.identity

import dev.droidtop.library.scraper.PcGameIds
import dev.droidtop.library.scraper.PcMatch
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchRecorderTest {
    @Test fun `a candidate is written under the first id it carries, named by its kind`() {
        assertEquals("igdb:7", MatchRecorder.candidateId(PcMatch("A", "IGDB", ids = PcGameIds(igdbId = 7, steamAppId = 9))))
        assertEquals("steam:9", MatchRecorder.candidateId(PcMatch("A", "Steam", ids = PcGameIds(steamAppId = 9))))
        assertEquals("A", MatchRecorder.candidateId(PcMatch("A", "Lutris")))
    }

    @Test fun `a recorded line reads back with its truth unchecked, and the grade leaves it out`() {
        val recorded = MatchRecorder.fixture(
            "/games/Foo", "folder", "pc", "Foo", "/games/Foo", 10L, null, "igdb", "name",
            listOf(MatchScore.Candidate("igdb:1", "Foo")),
        )
        val back = MatchCalibration.read(MatchCalibration.write(recorded))
        assertEquals(listOf(recorded), back)
        val report = MatchCalibration.grade(back)
        assertEquals(0, report.graded.size)
        assertEquals(1, report.unchecked)

        val checked = recorded.copy(truth = mapOf("igdb" to "igdb:1"))
        assertEquals(MatchScore.Tier.B, MatchCalibration.grade(listOf(checked)).graded.single().tier)
    }
}
