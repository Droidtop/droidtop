package dev.droidtop.library.gameinfo

import dev.droidtop.library.achievements.hash.DiscSerials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject

class GameInfoTest {
    @get:Rule val folder = TemporaryFolder()

    private val duckStation = """
        SLPS-03086:
          name: "'98 Koshien"
          compatibility:
            rating: NoIssues
            versionTested: "0.1-1308-g622e50fa"
          controllers:
            - DigitalController
          codes:
            - SLPS-03086
            - SLPS-03087
        SLUS-01272:
          name: Some Game
          compatibility:
            rating: GraphicalAudioIssues
            comments: "Crackling in the opening"
        SLES-00001:
          name: Unrated
          controllers:
            - DigitalController
        SLES-00002:
          name: Crashy
          compatibility:
            rating: CrashesInGame
    """.trimIndent()

    @Test
    fun `DuckStation rows keep the rating and the other codes of the same game`() {
        val rows = EmulatorCompat.parseDuckStation(duckStation.lineSequence())
        assertEquals("No issues", rows["SLPS-03086"])
        assertEquals("No issues", rows["SLPS-03087"])
        assertEquals("Graphical audio issues", rows["SLUS-01272"])
        assertEquals("Crashes in game", rows["SLES-00002"])
        assertNull(rows["SLES-00001"])
    }

    @Test
    fun `Azahar rows are keyed by title id, and untested games are left out`() {
        val rows = EmulatorCompat.parseAzahar(
            """[{"compatibility":0,"directory":"a","releases":[{"id":"00040000000c1400"},{"id":"0004000000000001"}],"title":"A"},
               {"compatibility":99,"directory":"b","releases":[{"id":"000400000008FE00"}],"title":"B"},
               {"compatibility":3,"directory":"c","releases":[{"id":"0004000000124F00"}],"title":"C"},
               {"compatibility":5,"directory":"d","releases":[{"id":"short"}],"title":"D"}]""",
        )
        assertEquals("Perfect", rows["00040000000C1400"])
        assertEquals("Perfect", rows["0004000000000001"])
        assertEquals("Bad", rows["0004000000124F00"])
        assertNull(rows["000400000008FE00"])
        assertEquals(3, rows.size)
    }

    @Test
    fun `a 3DS cartridge dump gives its partition 0 title id`() {
        val bytes = ByteArray(0x200)
        "NCSD".toByteArray().copyInto(bytes, 0x100)
        val id = longArrayOf(0x00, 0x14, 0x0C, 0x00, 0x00, 0x00, 0x04, 0x00).map { it.toByte() }
        for (i in 0 until 8) bytes[0x108 + i] = id[i]
        val file = folder.newFile("a.3ds").also { it.writeBytes(bytes) }
        assertEquals("00040000000C1400", EmulatorCompat.nintendo3dsTitleId(file))
        val other = folder.newFile("b.3ds").also { it.writeBytes(ByteArray(0x200)) }
        assertNull(EmulatorCompat.nintendo3dsTitleId(other))
    }

    @Test
    fun `a PlayStation boot file name becomes the serial emulators use`() {
        assertEquals("SLUS-01234", DiscSerials.serial("SLUS_012.34"))
        assertEquals("SCES-50360", DiscSerials.serial("SCES_503.60"))
        assertNull(DiscSerials.serial("PSX.EXE"))
    }

    @Test
    fun `the HowLongToBeat token answer is read by the names of its fields`() {
        val auth = HltbClient.parseToken("""{"token":"abc","hpKey":"ign_k","hpVal":"v1","other":1}""")!!
        assertEquals("abc", auth.token)
        assertEquals("ign_k", auth.key)
        assertEquals("v1", auth.value)
        assertNull(HltbClient.parseToken("""{"hpKey":"k","hpVal":"v"}"""))
        assertNull(HltbClient.parseToken("not json"))
    }

    @Test
    fun `the search body carries the title's words and the key and value`() {
        val body = JSONObject(HltbClient.payload("Super  Metroid", HltbClient.Auth("t", "ign_k", "v1")))
        assertEquals("games", body.getString("searchType"))
        assertEquals(listOf("Super", "Metroid"), (0 until 2).map { body.getJSONArray("searchTerms").getString(it) })
        assertEquals("v1", body.getString("ign_k"))
        assertEquals(1, body.getInt("searchPage"))
        assertEquals("popular", body.getJSONObject("searchOptions").getJSONObject("games").getString("sortCategory"))
    }

    private val results = """{"data":[
        {"game_id":1,"game_name":"Super Metroid","game_alias":"","comp_main":43200,"comp_plus":50400,"comp_100":86400,"comp_all":54000},
        {"game_id":2,"game_name":"Super Metroid: Redesign","game_alias":"","comp_main":0,"comp_plus":0,"comp_100":0,"comp_all":0},
        {"game_id":3,"game_name":"Metroid Prime","game_alias":"Prime, Metroid Prime 1","comp_main":50000}]}"""

    @Test
    fun `results are read in seconds and an exact title wins over a longer one`() {
        val games = HltbClient.parseResults(results)
        assertEquals(3, games.size)
        val (game, exact) = HltbMatch.best("Super Metroid (USA)", games)!!
        assertEquals(1, game.id)
        assertTrue(exact)
        assertEquals("Main 12 h, extras 14 h, all of it 24 h", HowLongToBeat.summary(game))
    }

    @Test
    fun `an alias counts and an unrelated title is not a match`() {
        val games = HltbClient.parseResults(results)
        assertEquals(3, HltbMatch.best("Metroid Prime 1", games)!!.first.id)
        assertNull(HltbMatch.best("Zelda", games))
    }

    @Test
    fun `a close title is offered as close, with the same numbers only`() {
        val games = HltbClient.parseResults("""{"data":[{"game_id":9,"game_name":"Final Fantasy VII Remake","comp_main":1},{"game_id":8,"game_name":"Ys 2 Special","comp_main":1}]}""")
        val near = HltbMatch.best("Remake Final Fantasy VII", games)!!
        assertEquals(9, near.first.id)
        assertFalse(near.second)
        assertNotNull(HltbMatch.best("Special Ys 2", games))
        assertNull(HltbMatch.best("Special Ys 3", games))
    }

    @Test
    fun `times read the way a person says them`() {
        assertEquals("45 min", HowLongToBeat.formatTime(2700))
        assertEquals("1 h", HowLongToBeat.formatTime(3600))
        assertEquals("12.5 h", HowLongToBeat.formatTime(45000))
    }
}
