package dev.droidtop.library.computers

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which computer has a game installed, read from the agent core's state file (docs/SPEC.md 7o). */
class ComputerInstalledOnTest {
    private val games = JSONObject(
        """{"steam:440":{"installs":{"aa":{"install":{"installed":true}},"bb":{"install":{"installed":false}}}}}""",
    )

    @Test
    fun `only an install marked installed counts`() {
        assertTrue(ComputerLibrary.installedOn(games, "steam:440", "aa"))
        assertFalse(ComputerLibrary.installedOn(games, "steam:440", "bb"))
        assertFalse(ComputerLibrary.installedOn(games, "steam:440", "cc"))
        assertFalse(ComputerLibrary.installedOn(games, "steam:10", "aa"))
        assertFalse(ComputerLibrary.installedOn(null, "steam:440", "aa"))
    }
}
