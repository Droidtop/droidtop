package dev.droidtop.library.identity

import dev.droidtop.library.consoles.MatchQueueEntity
import dev.droidtop.library.scraper.PcGameIds
import dev.droidtop.library.scraper.PcMatch
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchQueueTest {
    private fun m(name: String, year: Int? = null, igdb: Long? = null) = PcMatch(name, "IGDB", year = year, ids = PcGameIds(igdbId = igdb))

    @Test fun `candidates are kept best first, at most five, and put the game in its tab`() {
        val (tab, choices) = MatchQueue.rank(
            "Doom",
            listOf(m("Doom 3"), m("Final Doom"), m("Doom"), m("Doom 64"), m("Doom RPG"), m("Doom II"), m("Quake")),
        )
        assertEquals(MatchQueue.Tab.LIKELY, tab)
        assertEquals(5, choices.size)
        assertEquals("Doom", choices.first().match.name)
        assertEquals(100, choices.first().score)
    }

    @Test fun `a weak best answer is Unsure and no answer is Not found`() {
        assertEquals(MatchQueue.Tab.UNSURE, MatchQueue.rank("red blue green yellow", listOf(m("red blue orange"))).first)
        assertEquals(MatchQueue.Tab.NOT_FOUND, MatchQueue.rank("Anything", emptyList()).first)
    }

    @Test fun `a kept candidate reads back with what applying it needs`() {
        val match = PcMatch(
            "Seven Day W Yandere Girl", "IGDB", year = 2021, coverUrl = "https://img/c.jpg", developer = "Dev",
            rating = 4.5f, ids = PcGameIds(igdbId = 12345, steamAppId = 9, lutrisSlug = "seven-day"),
        )
        val back = MatchQueue.choicesFrom(MatchQueue.choicesJson(listOf(MatchQueue.Choice(match, 100))))
        assertEquals(listOf(MatchQueue.Choice(match, 100)), back)
    }

    @Test fun `a row with a tab droidtop no longer has is left out, not a crash`() {
        assertEquals(null, MatchQueue.itemOf(MatchQueueEntity("g", MatchQueue.QUEUED, "GONE", "IGDB", "x", "[]", 0)))
        assertEquals(emptyList<MatchQueue.Choice>(), MatchQueue.choicesFrom("not json"))
    }

    private fun item(id: String, tab: MatchQueue.Tab, searched: String) = MatchQueue.Item(id, tab, "IGDB", searched, emptyList(), 0)

    @Test fun `games of one tab that searched for the same name are one card, and cards go Likely, Unsure, Not found`() {
        val cards = MatchQueue.cards(
            listOf(
                item("/g/Zeta v2", MatchQueue.Tab.LIKELY, "Zeta"),
                item("/g/Lost", MatchQueue.Tab.NOT_FOUND, "Lost"),
                item("/g/Zeta v1", MatchQueue.Tab.LIKELY, "ZETA"),
                item("/g/Alpha", MatchQueue.Tab.UNSURE, "Alpha"),
                item("/g/Beta", MatchQueue.Tab.LIKELY, "Beta"),
            ),
        )
        assertEquals(listOf("beta", "zeta", "alpha", "lost"), cards.map { it.searched.lowercase() })
        assertEquals(listOf("/g/Zeta v1", "/g/Zeta v2"), cards[1].items.map { it.gameId })
    }

    @Test fun `pips follow the bars, never the number`() {
        assertEquals(listOf(4, 3, 2, 1), listOf(95, 75, 45, 10).map(MatchQueue::pips))
    }
}
