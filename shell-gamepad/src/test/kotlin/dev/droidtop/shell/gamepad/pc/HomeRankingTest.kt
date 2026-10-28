package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PlayStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home's adaptive order and its time-of-day row (HomeRanking.kt, Droidtop/tracker#611). */
class HomeRankingTest {
    private fun game(id: String, hidden: Boolean = false) = LibraryEntry(id = id, title = id, kind = LibraryEntryKind.RENPY, hidden = hidden)

    /** A start at [hour] o'clock on day [day]; the test's hourOf reads the hour back from it. */
    private fun start(id: String, hour: Int, day: Int = 0) = PlayStart(id, (day * 24L + hour) * 3_600_000L)
    private val hourOf = { ms: Long -> ((ms / 3_600_000L) % 24).toInt() }

    private fun shelf(id: String) = PcShelf(id, id, listOf(game("/$id")), 1)

    @Test
    fun `usual now holds the games started at least twice within an hour of now, most first`() {
        val all = listOf(game("/a"), game("/b"), game("/c"))
        val starts = listOf(
            start("/a", 21, 0), start("/a", 22, 1), start("/a", 20, 2),
            start("/b", 21, 0), start("/b", 20, 3),
            start("/c", 21, 0), start("/c", 9, 1),
        )
        val shelf = usualNowShelf(starts, all, nowHour = 21, hourOf = hourOf)!!
        assertEquals(SHELF_USUAL_NOW, shelf.id)
        // /a three starts near 21:00, /b two; /c has one near 21:00 (its 9:00 is far), so it stays out.
        assertEquals(listOf("/a", "/b"), shelf.entries.map { it.id })
        // Without /b only /a qualifies, and one card is not a row.
        assertNull(usualNowShelf(starts.filter { it.gameId != "/b" }, all, 21, hourOf))
    }

    @Test
    fun `the hour wraps round midnight and hidden or unknown games stay out`() {
        val all = listOf(game("/a"), game("/b"), game("/h", hidden = true))
        val starts = listOf(
            start("/a", 23, 0), start("/a", 0, 2),
            start("/b", 0, 0), start("/b", 1, 1),
            start("/h", 0, 0), start("/h", 0, 1),
            start("/gone", 0, 0), start("/gone", 0, 1),
        )
        // 23:00 is an hour before midnight; both have two starts, so they go by name.
        assertEquals(listOf("/a", "/b"), usualNowShelf(starts, all, nowHour = 0, hourOf = hourOf)!!.entries.map { it.id })
    }

    @Test
    fun `rows that started more games come first, usual now leads, and unused rows keep their order`() {
        val rows = listOf(shelf(SHELF_RECENT_GAMES), shelf(SHELF_MOST_PLAYED), shelf(SHELF_RECOMMENDED), shelf("plugin:x/y"))
        assertEquals(rows.map { it.id }, rankHomeShelves(rows, emptyMap()).map { it.id })
        assertEquals(
            listOf(SHELF_RECOMMENDED, "plugin:x/y", SHELF_RECENT_GAMES, SHELF_MOST_PLAYED),
            rankHomeShelves(rows, mapOf(SHELF_RECOMMENDED to 5, "plugin:x/y" to 2)).map { it.id },
        )
        val withUsual = rows + shelf(SHELF_USUAL_NOW)
        assertEquals(SHELF_USUAL_NOW, rankHomeShelves(withUsual, mapOf(SHELF_RECOMMENDED to 5)).first().id)
    }
}
