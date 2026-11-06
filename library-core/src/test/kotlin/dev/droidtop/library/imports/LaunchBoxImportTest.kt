package dev.droidtop.library.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading LaunchBox's platform files for an import (Droidtop/tracker#724). */
class LaunchBoxImportTest {
    private val xml = """
        <?xml version="1.0" standalone="yes"?>
        <LaunchBox>
          <Game>
            <ApplicationPath>..\Games\Super Nintendo\Chrono Trigger.sfc</ApplicationPath>
            <Favorite>true</Favorite>
            <ID>abc</ID>
            <LastPlayedDate>2024-05-01T12:00:00.0000000-04:00</LastPlayedDate>
            <PlayTime>3600</PlayTime>
            <Title>Chrono Trigger</Title>
          </Game>
          <Game>
            <ApplicationPath>D:\Roms\Tom &amp; Jerry.sfc</ApplicationPath>
            <Favorite>false</Favorite>
            <Title>Tom &amp; Jerry</Title>
          </Game>
          <Game><Title>No file</Title></Game>
        </LaunchBox>
    """.trimIndent()

    @Test
    fun `games read with their file, favourite, play time and last play`() {
        val games = LaunchBoxImport.parse(xml)
        assertEquals(2, games.size)
        assertEquals("Games/Super Nintendo/Chrono Trigger.sfc", games[0].matchPath)
        assertEquals(true, games[0].favorite)
        assertEquals(3600L, games[0].playSeconds)
        assertEquals(1714579200000L, LaunchBoxImport.lastPlayedMs(games[0]))
        assertEquals("Tom & Jerry", games[1].title)
        assertEquals("Roms/Tom & Jerry.sfc", games[1].matchPath)
        assertNull(LaunchBoxImport.lastPlayedMs(games[1]))
    }

    @Test
    fun `a LaunchBox path matches a droidtop ROM by its file name when the folders differ`() {
        val game = LaunchBoxImport.parse(xml).first()
        val match = PegasusImport.match(listOf(game.matchPath), listOf("/sd/roms/snes/Chrono Trigger.sfc"))
        assertEquals(listOf("/sd/roms/snes/Chrono Trigger.sfc"), match.ids)
    }
}
