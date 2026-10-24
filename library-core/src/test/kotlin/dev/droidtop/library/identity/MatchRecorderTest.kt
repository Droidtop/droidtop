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

    @Test fun `an answer the reviewer was not sure of is left out of the grade, and a sure one is graded`() {
        val recorded = MatchRecorder.fixture(
            "/games/Foo", "folder", "pc", "Foo", "/games/Foo", null, null, "igdb", "name",
            listOf(MatchScore.Candidate("igdb:1", "Foo")),
        )
        val unsure = recorded.copy(truth = mapOf("igdb" to "igdb:1"), review = mapOf("igdb" to MatchCalibration.Review("agent", false, "two releases share the name")))
        assertEquals(1, MatchCalibration.grade(listOf(unsure)).unchecked)
        val sure = unsure.copy(review = mapOf("igdb" to MatchCalibration.Review("person", true, "checked the store page")))
        assertEquals(1, MatchCalibration.grade(listOf(sure)).graded.size)
    }

    @Test fun `facts, answer details and the review survive a write and a read`() {
        val line = MatchRecorder.fixture(
            "/games/Foo", "engine", "pc", "Foo", "/games/Foo", null, null, "igdb", "name",
            listOf(MatchScore.Candidate("igdb:1", "Foo"), MatchScore.Candidate("igdb:2", "Foo 2")),
            details = mapOf("igdb:1" to mapOf("developer" to "Bar", "release_date" to "2021-03-02")),
            facts = mapOf("files" to "game, renpy", "decision_today" to "asked"),
        ).copy(truth = mapOf("igdb" to "igdb:1"), review = mapOf("igdb" to MatchCalibration.Review("agent", true, "same developer")))
        assertEquals(listOf(line), MatchCalibration.read(MatchCalibration.write(line)))
    }

    @Test fun `a candidate's details carry every id and what the source said about it`() {
        val details = MatchRecorder.candidateDetails(
            PcMatch("Foo", "IGDB", year = 2021, developer = "Bar", description = "x".repeat(2000), ids = PcGameIds(igdbId = 7, steamAppId = 9)),
        )
        assertEquals("IGDB", details["source"])
        assertEquals("7", details["igdb"])
        assertEquals("9", details["steam"])
        assertEquals("Bar", details["developer"])
        assertEquals(600, details["description"]?.length)
    }
}
