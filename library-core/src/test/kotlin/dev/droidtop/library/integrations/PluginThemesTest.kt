package dev.droidtop.library.integrations

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Themes from plugins (docs/plugin-api.md 3 C7, Droidtop/tracker#714): what Browse themes lists from a provider's answer. */
class PluginThemesTest {
    @Test
    fun `each theme is an https repository in a folder named for its plugin, marked with the plugin`() {
        val entries = PluginThemes.parse(
            "gamegrab.themes", "Theme packs",
            JSONObject(
                """{"themes":[
                  {"id":"neon","name":"Neon","author":"Someone","url":"https://gitlab.com/someone/neon-es-de.git",
                   "screenshots":["https://img.example/neon.png","http://insecure/x.png"],"variants":["grid"]},
                  {"id":"neon","name":"Neon again","url":"https://gitlab.com/x.git"},
                  {"id":"../etc","name":"Escape","url":"https://gitlab.com/y.git"},
                  {"id":"plain","name":"Plain","url":"git://insecure/plain.git"},
                  {"id":"nameless","url":"https://gitlab.com/z.git"}
                ]}""",
            ),
        )
        assertEquals(listOf("Neon"), entries.map { it.name })
        val neon = entries.single()
        assertEquals("plugin-gamegrab.themes-neon", neon.reponame)
        assertEquals("Theme packs", neon.source)
        assertEquals(listOf("https://img.example/neon.png"), neon.screenshots.map { it.image })
        assertEquals(listOf("grid"), neon.variants)
    }

    @Test
    fun `a folder name holds nothing a path could hide in`() {
        assertEquals("plugin-a_b-c_d", PluginThemes.folderName("a/b", "c d"))
    }
}
