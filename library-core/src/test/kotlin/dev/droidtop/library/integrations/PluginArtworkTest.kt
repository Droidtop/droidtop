package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.scraper.PcGameIds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Artwork from plugins (docs/plugin-api.md 3 A4, Droidtop/tracker#714): what a provider is told and how its pictures are read. */
class PluginArtworkTest {
    @Test
    fun `a provider is told the game's title, system and store ids, and the kinds wanted`() {
        val entry = LibraryEntry(id = "pc:steam:440", title = "tf2", kind = LibraryEntryKind.WINE_PROFILE, systemId = "windows")
        val args = PluginArtwork.args(entry, PcGameIds(steamAppId = 440, gogId = "123"), "Team Fortress 2")
        val game = args.getJSONObject("entry")
        assertEquals("Team Fortress 2", game.getString("title"))
        assertEquals("windows", game.getString("system"))
        assertEquals("440", game.getJSONObject("ids").getString("steam"))
        assertEquals("123", game.getJSONObject("ids").getString("gog"))
        assertEquals(PluginArtwork.KINDS, (0 until args.getJSONArray("kinds").length()).map { args.getJSONArray("kinds").getString(it) })
    }

    @Test
    fun `the first https picture of each kind is taken, in the device's language before others`() {
        val reply = JSONObject(
            """{"items":[
              {"kind":"cover","url":"https://img.example/cover-de.png","lang":"de"},
              {"kind":"cover","url":"https://img.example/cover-en.png","lang":"en"},
              {"kind":"logo","url":"http://insecure.example/logo.png"},
              {"kind":"logo","url":"https://img.example/logo.png"},
              {"kind":"wide","url":"https://img.example/wide.png"},
              {"kind":"screenshot","url":"https://img.example/shot.png"}
            ]}""",
        )
        val match = PluginArtwork.matchOf("Grids", "Team Fortress 2", reply, language = "en")!!
        assertEquals("Grids", match.sourceLabel)
        assertEquals("https://img.example/cover-en.png", match.coverUrl)
        assertEquals("https://img.example/logo.png", match.logoUrl)
        assertEquals("a wide picture fills the landscape slot without a hero", "https://img.example/wide.png", match.heroUrl)
        assertNull(match.iconUrl)
    }

    @Test
    fun `a provider that gives nothing usable is no candidate`() {
        assertNull(PluginArtwork.matchOf("Grids", "X", JSONObject("""{"items":[{"kind":"cover","url":"ftp://x"}]}""")))
        assertNull(PluginArtwork.matchOf("Grids", "X", JSONObject("{}")))
    }
}
