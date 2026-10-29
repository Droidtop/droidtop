package dev.droidtop.library.integrations

import dev.droidtop.library.stores.StoreHolding
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A plugin's store (docs/plugin-api.md 3 A13, Droidtop/tracker#220): its id and its `library` answer. */
class PluginStoresTest {
    @Test fun `a store id is short and plain, never one already taken, and the first of a pair wins`() {
        assertEquals(
            listOf("humble", "zoom2"),
            PluginStores.usable(listOf("humble", "steam", "Zoom", "zoom2", "humble", null, "a", "has:colon"), setOf("steam", "gog")),
        )
    }

    @Test fun `the library answer becomes rows, each game once, with an id and a title`() {
        val reply = JSONObject(
            """{"games":[
              {"id":"101","title":"Ori","size":5000,"art":"https://cdn.example/ori.jpg","externalIds":{"steam":"261570"},"holding":"owned"},
              {"id":"101","title":"Ori again"},
              {"id":"102","title":"  "},
              {"title":"No id"},
              {"id":"103","title":"Wished","art":"http://insecure/x.jpg","holding":"wishlist"}
            ]}""",
        )
        val games = PluginStores.gamesOf("humble", reply)
        assertEquals(listOf("101", "103"), games.map { it.gameId })
        assertEquals("humble:101", games[0].key)
        assertEquals(mapOf("steam" to "261570"), games[0].externalIds)
        assertEquals("https://cdn.example/ori.jpg", games[0].artUrl)
        assertEquals(null, games[1].artUrl)
        assertEquals(StoreHolding.WISHLIST, games[1].holding)
        assertEquals(false, games[0].installed)
    }

    @Test fun `droidtop's own copy reads back as it was written`() {
        val games = PluginStores.gamesOf("humble", JSONObject("""{"games":[{"id":"1","title":"A","holding":"family","externalIds":{"gog":"9"}}]}"""))
        val again = PluginStores.gamesOf("humble", JSONObject().put("games", org.json.JSONArray(PluginStores.rowsJson(games))))
        assertEquals(games, again)
    }

    @Test fun `what droidtop installed reads back, and a record that is not one is dropped`() {
        val installs = mapOf(
            "1" to PluginStores.Install("/games/Humble/Ori", installed = true),
            "2" to PluginStores.Install("/games/Humble/Half", installed = false),
        )
        assertEquals(installs, PluginStores.installsOf(PluginStores.installsJson(installs)))
        assertEquals(emptyMap<String, PluginStores.Install>(), PluginStores.installsOf("""{"3":{"path":"relative/x","installed":true},"4":7}"""))
        assertEquals(emptyMap<String, PluginStores.Install>(), PluginStores.installsOf("not json"))
    }

    @Test fun `a finished install marks its row installed at its folder, an unfinished one does not`() {
        val games = PluginStores.gamesOf("humble", JSONObject("""{"games":[{"id":"1","title":"Ori"},{"id":"2","title":"Half"},{"id":"3","title":"Other"}]}"""))
        val laid = PluginStores.withInstalls(
            games,
            mapOf("1" to PluginStores.Install("/games/Humble/Ori", true), "2" to PluginStores.Install("/games/Humble/Half", false)),
        )
        assertEquals(listOf(true, false, false), laid.map { it.installed })
        assertEquals(listOf("/games/Humble/Ori", null, null), laid.map { it.installPath })
    }

    @Test fun `a new install gets a folder of its own, never one with someone's files in it`() {
        val root = java.nio.file.Files.createTempDirectory("plugin-stores").toFile()
        try {
            assertEquals(java.io.File(root, "Love Sex Second Base"), PluginStores.freshFolder(root, "Love & Sex: Second Base", "humble-1"))
            assertEquals(java.io.File(root, "humble-1"), PluginStores.freshFolder(root, "???", "humble-1"))
            java.io.File(root, "Ori").mkdirs()
            // An empty folder of that name is taken as it is; one with files in it is not.
            assertEquals(java.io.File(root, "Ori"), PluginStores.freshFolder(root, "Ori", "humble-1"))
            java.io.File(root, "Ori/save.dat").writeText("x")
            assertEquals(java.io.File(root, "Ori (2)"), PluginStores.freshFolder(root, "Ori", "humble-1"))
        } finally {
            root.deleteRecursively()
        }
    }
}
