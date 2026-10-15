package dev.droidtop.library.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SaveMatcherTest {
    /** An index that knows the rules it is given, by (kind, value). */
    private class Fake(vararg rules: Pair<Pair<String, String>, String>) : SaveIndex {
        private val known = rules.toMap()
        val asked = ArrayList<Pair<String, String>>()
        override fun find(kind: String, value: String): String? {
            asked += kind to value
            return known[kind to value]
        }
    }

    private val everything = SaveIdentity(
        steamId = "440",
        gogId = "1207658",
        scraped = mapOf(GameIdKind.LUTRIS to "team-fortress-2", GameIdKind.IGDB to "123"),
        installFolder = "tf2",
        titleKey = "teamfortress2",
    )

    @Test
    fun `a Steam id beats everything else`() {
        val index = Fake(
            (GameIdKind.STEAM to "440") to "Team Fortress 2",
            (SaveMatchHow.TITLE to "teamfortress2") to "Other",
        )
        assertEquals(SaveMatch("Team Fortress 2", SaveMatchHow.STEAM), SaveMatcher.match(everything, index))
    }

    @Test
    fun `the order is Steam, GOG, a scraper's id, the install folder, the exact title`() {
        val index = Fake()
        assertNull(SaveMatcher.match(everything, index))
        assertEquals(
            listOf(
                GameIdKind.STEAM to "440",
                GameIdKind.GOG to "1207658",
                GameIdKind.LUTRIS to "team-fortress-2",
                GameIdKind.IGDB to "123",
                SaveMatchHow.FOLDER to "tf2",
                SaveMatchHow.TITLE to "teamfortress2",
            ),
            index.asked,
        )
    }

    @Test
    fun `an id a scraper learned counts as a scraper match, not a store one`() {
        val scraped = SaveIdentity(null, null, mapOf(GameIdKind.STEAM to "620"), null, "portal2")
        val index = Fake((GameIdKind.STEAM to "620") to "Portal 2")
        assertEquals(SaveMatch("Portal 2", SaveMatchHow.SCRAPER), SaveMatcher.match(scraped, index))
    }

    @Test
    fun `the same id is not asked twice`() {
        val identity = SaveIdentity("440", null, mapOf(GameIdKind.STEAM to "440"), null, null)
        val index = Fake()
        SaveMatcher.match(identity, index)
        assertEquals(listOf(GameIdKind.STEAM to "440"), index.asked)
    }

    @Test
    fun `the folder is tried before the title, and a title alone still matches`() {
        val identity = SaveIdentity(null, null, emptyMap(), "some game", "somegame")
        assertEquals(
            SaveMatch("A", SaveMatchHow.FOLDER),
            SaveMatcher.match(identity, Fake((SaveMatchHow.FOLDER to "some game") to "A", (SaveMatchHow.TITLE to "somegame") to "B")),
        )
        assertEquals(SaveMatch("B", SaveMatchHow.TITLE), SaveMatcher.match(identity, Fake((SaveMatchHow.TITLE to "somegame") to "B")))
    }

    @Test
    fun `a match says in words how it was made`() {
        assertEquals("matched by its Steam id", SaveMatch("x", SaveMatchHow.STEAM).words())
        assertEquals("chosen by you", SaveMatch("x", SaveMatchHow.YOU).words())
        assertEquals("matched by its title", SaveMatch("x", SaveMatchHow.TITLE).words())
    }
}
