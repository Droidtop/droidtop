package dev.droidtop.library.achievements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shapes api-docs.retroachievements.org documents for the game list and the game with progress. */
class RetroAchievementsClientTest {

    @Test
    fun `a game list keeps the id, title, console and lower-cased hashes`() {
        val games = RetroAchievementsClient.parseGameList(
            """[{"Title":"Sonic the Hedgehog","ID":1,"ConsoleID":1,"ConsoleName":"Mega Drive","ImageIcon":"/Images/1.png",
               "NumAchievements":24,"NumLeaderboards":0,"Points":400,"DateModified":"2024-01-01 00:00:00","ForumTopicID":9,
               "Hashes":["AABBCCDDEEFF00112233445566778899","00112233445566778899aabbccddeeff"]},
               {"Title":"","ID":2,"ConsoleID":1},{"Title":"No id","ID":0}]""",
        )
        assertEquals(1, games.size)
        assertEquals("Sonic the Hedgehog", games[0].title)
        assertEquals(24, games[0].numAchievements)
        assertEquals("aabbccddeeff00112233445566778899", games[0].hashes[0])
    }

    @Test
    fun `a game with progress lists achievements in display order with earn dates`() {
        val progress = RetroAchievementsClient.parseProgress(
            """{"ID":1,"Title":"Sonic the Hedgehog","ConsoleID":1,"NumAchievements":3,"Achievements":{
               "9":{"ID":9,"Title":"C","Description":"third","Points":10,"BadgeName":"b9","DisplayOrder":3},
               "7":{"ID":7,"Title":"A","Description":"first","Points":5,"BadgeName":"b7","DisplayOrder":1,
                    "DateEarned":"2024-02-01 10:00:00","DateEarnedHardcore":"2024-02-01 10:00:00"},
               "8":{"ID":8,"Title":"B","Description":"second","Points":25,"BadgeName":"b8","DisplayOrder":2,
                    "DateEarned":"2024-02-02 10:00:00"}}}""",
        )!!
        assertEquals(listOf("A", "B", "C"), progress.achievements.map { it.title })
        assertEquals(3, progress.total)
        assertEquals(2, progress.earned)
        assertEquals(1, progress.earnedHardcore)
        assertEquals(40, progress.pointsTotal)
        assertEquals(30, progress.pointsEarned)
    }

    @Test
    fun `a game that has no achievements object still parses and an unknown game does not`() {
        val empty = RetroAchievementsClient.parseProgress("""{"ID":5,"Title":"X"}""")!!
        assertTrue(empty.achievements.isEmpty())
        assertNull(RetroAchievementsClient.parseProgress("""{"ID":0}"""))
        assertNull(RetroAchievementsClient.parseProgress("not json"))
    }
}
