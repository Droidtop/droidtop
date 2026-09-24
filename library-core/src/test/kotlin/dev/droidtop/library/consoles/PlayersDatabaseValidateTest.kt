package dev.droidtop.library.consoles

import dev.droidtop.library.SeedAssets
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An installed players database is validated with the parser that reads it,
 * so a document that parses as JSON but carries a row [KnownPlayers] would
 * reject is refused, instead of being written and then silently replaced by
 * the seed on every read.
 */
class PlayersDatabaseValidateTest {
    @Test
    fun `the shipped seed validates`() {
        assertTrue(PlayersDatabaseUpdater.validate(SeedAssets.read("players-database.json")) > 0)
    }

    @Test(expected = Exception::class)
    fun `a row without a package is refused`() {
        PlayersDatabaseUpdater.validate(
            """{"players":[{"id":"x","systemId":"psx","label":"X","argumentsTemplate":"-n a/.B"}]}""",
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `an empty database is refused`() {
        PlayersDatabaseUpdater.validate("""{"players":[]}""")
    }
}
