package dev.droidtop.library.computers

import dev.droidtop.library.GameLinks
import dev.droidtop.library.SourceAnswer
import dev.droidtop.library.SourceLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A newer version on another device reads as an update (Droidtop/tracker#469 part 2). */
class ComputerVersionsTest {
    @Test
    fun `newer is compared the way droidtop compares versions`() {
        assertTrue(ComputerLibrary.newerThan("0.9.10", "0.9.9"))
        assertTrue(ComputerLibrary.newerThan("v1.2", "1.1"))
        assertFalse(ComputerLibrary.newerThan("1.1", "v1.1"))
        assertFalse(ComputerLibrary.newerThan("0.9", "0.10"))
    }

    @Test
    fun `the newest answer wins over the first source's`() {
        val links = GameLinks(
            sources = listOf(
                SourceLink("computer:aa", "title:eternum", SourceAnswer("0.9.6", 1L)),
                SourceLink("f95", "123", SourceAnswer("0.9.5", 1L)),
            ),
        )
        assertEquals("0.9.6", links.latestKnown)
    }

    @Test
    fun `a version copied here is named so it reads as the same game at that version`() {
        val entry = dev.droidtop.library.LibraryEntry(id = "/games/Eternum-0.9.5-pc", title = "Eternum", kind = dev.droidtop.library.LibraryEntryKind.RENPY)
        val name = ComputerGames.folderName(entry, "v0.9.6")
        assertEquals("Eternum v0.9.6", name)
        val derived = dev.droidtop.library.GameNaming.derive("/games/$name")
        assertEquals("0.9.6", derived.version)
        assertEquals(dev.droidtop.library.GameNaming.derive(entry.id).name, derived.name)
    }

    @Test
    fun `play time elsewhere is every other device's, most first`() {
        val game = org.json.JSONObject(
            """{"installs":{
              "me":{"device_name":"Pocket","install":{"installed":true,"play_seconds":600}},
              "pc":{"device_name":"DESKTOP","install":{"installed":true,"play_seconds":36000,"last_played_ms":5}},
              "deck":{"device_name":"","install":{"installed":false,"play_seconds":7200}},
              "new":{"device_name":"Laptop","install":{"installed":true,"play_seconds":0}}
            }}""",
        )
        assertEquals(
            listOf(
                ComputerLibrary.PlayElsewhere("DESKTOP", 36000, 5),
                ComputerLibrary.PlayElsewhere("Another device", 7200, 0),
            ),
            ComputerLibrary.playElsewhereIn(game, "me"),
        )
    }
}
