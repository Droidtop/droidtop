package dev.droidtop.library.integrations

import dev.droidtop.library.EnginehostTarget
import dev.droidtop.library.LaunchFacts
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/plugin-api.md 3 A7a (Droidtop/tracker#547): the engine games droidtop offers an app, and finding one by its folder. */
class AppInterfaceSavesTest {
    private fun entry(id: String, title: String, kind: LibraryEntryKind = LibraryEntryKind.RENPY, missing: Boolean = false) =
        LibraryEntry(id = id, title = title, kind = kind, missing = missing)

    private fun facts(root: String, engine: String? = "renpy", version: String? = "8.1") =
        LaunchFacts.Engine(gameRoot = root, engine = "RENPY", engineVersion = version, enginehostTarget = engine?.let { EnginehostTarget(it, null) })

    private val ddlc = entry("/storage/emulated/0/Games/DDLC", "Doki Doki Literature Club")
    private val aster = entry("/storage/emulated/0/Games/Aster", "aster")
    private val facts = mapOf(
        ddlc.id to facts("/storage/emulated/0/Games/DDLC/DDLC-1.1.1-pc"),
        aster.id to facts(aster.id, engine = "rpgmaker-mv", version = null),
    )

    private fun games(vararg entries: LibraryEntry) = AppInterfaceSaves.engineGamesOf(entries.toList()) { facts[it.id] }

    @Test
    fun `only engine games Enginehost can run are offered, by title`() {
        val noTarget = entry("/storage/emulated/0/Games/Old", "Old")
        val gone = entry("/storage/emulated/0/Games/Gone", "Gone", missing = true)
        val rom = entry("/storage/emulated/0/Roms/snes/a.sfc", "A", kind = LibraryEntryKind.CONSOLE_ROM)
        val all = AppInterfaceSaves.engineGamesOf(listOf(ddlc, noTarget, gone, rom, aster)) { e ->
            facts[e.id] ?: if (e == noTarget) facts(e.id, engine = null) else facts(e.id)
        }
        assertEquals(listOf(aster.id, ddlc.id), all.map { it.entry.id })
    }

    @Test
    fun `the list carries the game, its folder and engine, never a config`() {
        val json = AppInterfaceSaves.toJson(games(ddlc, aster), engines = null) { 42L }
        assertTrue(json.getBoolean("complete"))
        val first = json.getJSONArray("games").getJSONObject(1)
        assertEquals(setOf("gameId", "title", "path", "engine", "engineVersion", "updatedAtMs"), first.keys().asSequence().toSet())
        assertEquals("/storage/emulated/0/Games/DDLC/DDLC-1.1.1-pc", first.getString("path"))
        assertEquals("renpy", first.getString("engine"))
        assertFalse("no version is left out", json.getJSONArray("games").getJSONObject(0).has("engineVersion"))
    }

    @Test
    fun `engines narrows the list and a cut list says it is not complete`() {
        val onlyRenpy = AppInterfaceSaves.toJson(games(ddlc, aster), engines = setOf("renpy")) { 0L }
        assertEquals(1, onlyRenpy.getJSONArray("games").length())
        val many = (0..AppInterfaceSaves.MAX_GAMES).map { i ->
            AppInterfaceSaves.EngineGame(entry("/g/$i", "g$i"), facts("/g/$i"))
        }
        val cut = AppInterfaceSaves.toJson(many, engines = null) { 0L }
        assertEquals(AppInterfaceSaves.MAX_GAMES, cut.getJSONArray("games").length())
        assertFalse(cut.getBoolean("complete"))
    }

    @Test
    fun `a game is found by its game root or by the folder the library shows`() {
        val all = games(ddlc, aster)
        assertEquals(ddlc.id, AppInterfaceSaves.match(all, "/storage/emulated/0/Games/DDLC/DDLC-1.1.1-pc/")?.entry?.id)
        assertEquals(ddlc.id, AppInterfaceSaves.match(all, ddlc.id)?.entry?.id)
        assertNull(AppInterfaceSaves.match(all, "/storage/emulated/0/Games"))
        assertNull(AppInterfaceSaves.match(all, "Games/DDLC"))
    }

    @Test
    fun `only entries whose folder could hold the path are read`() {
        assertTrue(AppInterfaceSaves.related(ddlc.id, "${ddlc.id}/DDLC-1.1.1-pc"))
        assertTrue(AppInterfaceSaves.related(ddlc.id, ddlc.id))
        assertFalse(AppInterfaceSaves.related(ddlc.id, "${ddlc.id}2"))
        assertFalse(AppInterfaceSaves.related(ddlc.id, "relative"))
    }
}
