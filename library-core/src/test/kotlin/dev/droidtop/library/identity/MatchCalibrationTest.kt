package dev.droidtop.library.identity

import dev.droidtop.library.identity.MatchScore.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchCalibrationTest {
    private fun resource(name: String): String? =
        javaClass.classLoader?.getResourceAsStream("match-fixtures/$name")?.bufferedReader()?.use { it.readText() }

    @Test fun `the seed cases land in the tiers tracker 318 sets out, and nothing wrong is taken`() {
        val report = MatchCalibration.grade(MatchCalibration.read(resource("seeds.jsonl")!!))
        val tiers = report.graded.associate { it.fixture to it.tier }
        assertEquals(
            report.summary(),
            mapOf(
                "s01" to Tier.B, // the version on the slug does not stop the match
                "s02" to Tier.C, // "Hollow Knight" is not "Hollow Knight: Silksong"
                "s03" to Tier.C, // two exact Doom answers and no year to choose
                "s04" to Tier.B, // the year chooses
                "s05" to Tier.A, // by hash
                "s06" to Tier.B, // the GBA answer is left out
                "s07" to Tier.A, // by store id
                "s08" to Tier.E,
                "s09" to Tier.A_ALIAS,
                "s10" to Tier.B, // a plugin's 0.95
            ),
            tiers,
        )
        assertEquals(report.summary(), 0, report.wrongTaken)
    }

    @Test fun `a fixture written and read back is the same`() {
        val fixtures = MatchCalibration.read(resource("seeds.jsonl")!!)
        fixtures.forEach { assertEquals(it, MatchCalibration.fixture(org.json.JSONObject(MatchCalibration.write(it)))) }
    }

    /**
     * The calibration gate (§3.3). Until calibration.jsonl holds the full set it only prints the report; once it does,
     * auto-accept at B goes live only while this passes.
     */
    @Test fun `the calibration set passes the gate once it is complete`() {
        val text = resource("calibration.jsonl") ?: return
        val report = MatchCalibration.grade(MatchCalibration.read(text))
        println(report.summary())
        if (report.enough) assertTrue(report.summary(), report.passes)
    }
}
