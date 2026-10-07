package dev.droidtop.library.integrations

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.consoles.EmulatorSource
import dev.droidtop.library.consoles.Player
import dev.droidtop.library.consoles.ResolvedEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data `library.read` `systems` hands a plugin (docs/plugin-api.md 3 A1): which systems have games, and the
 * emulator a launch would use for each, in the same shape the `default_player_changed` event uses (empty strings, never
 * nulls). The broker's permission check is covered in plugin-host's BrokerCoreTest.
 */
class PluginLibraryReadTest {
    private val defs = listOf(
        ConsoleSystemDef("snes", "Super Nintendo", setOf("sfc"), "snes9x"),
        ConsoleSystemDef("psx", "Sony PlayStation", setOf("cue"), "mednafen_psx"),
        ConsoleSystemDef("gba", "Game Boy Advance", setOf("gba"), "mgba"),
    ).associateBy { it.id }

    private fun rom(id: String, system: String?, missing: Boolean = false, kind: LibraryEntryKind = LibraryEntryKind.CONSOLE_ROM) =
        LibraryEntry(id = id, title = id, kind = kind, systemId = system, missing = missing)

    private val retroArch = Player.AmStart(
        id = "snes-ra",
        name = "RetroArch",
        argumentsTemplate = "-n com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture -e ROM {file.path}",
        packageName = "com.retroarch",
    )
    private val beetle = Player.AmStart(
        id = "psx-beetle-hw",
        name = "Retroarch - beetle psx hw",
        argumentsTemplate = "-n com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture -e ROM {file.path} " +
            "-e LIBRETRO /data/data/com.retroarch/cores/mednafen_psx_hw_libretro_android.so",
        packageName = "com.retroarch",
    )

    private val resolve: (ConsoleSystemDef) -> ResolvedEmulator? = { system ->
        when (system.id) {
            "snes" -> ResolvedEmulator(retroArch, EmulatorSource.AUTOMATIC)
            "psx" -> ResolvedEmulator(beetle, EmulatorSource.SYSTEM)
            else -> null
        }
    }

    @Test
    fun `only systems with a game that is there are listed, by name, with the game count`() {
        val rows = PluginLibraryRead.systems(
            listOf(
                rom("a", "snes"), rom("b", "snes"), rom("c", "snes", missing = true),
                rom("d", "psx"),
                rom("e", "gba", missing = true),
                rom("f", null),
                rom("g", "snes", kind = LibraryEntryKind.NATIVE_ANDROID_APP),
            ),
            defs,
            resolve,
        )
        assertEquals(listOf("psx", "snes"), rows.map { it.id })
        assertEquals(listOf("Sony PlayStation", "Super Nintendo"), rows.map { it.name })
        assertEquals(2, rows.first { it.id == "snes" }.games)
    }

    @Test
    fun `a system the definitions do not know keeps its id as its name and has no player`() {
        val rows = PluginLibraryRead.systems(listOf(rom("a", "mystery")), defs, resolve)
        assertEquals("mystery", rows.single().name)
        assertNull(rows.single().player)
    }

    @Test
    fun `the reply carries the chosen emulator and its own core, and empty strings when there is none`() {
        val json = PluginLibraryRead.toJson(
            PluginLibraryRead.systems(listOf(rom("a", "snes"), rom("b", "psx"), rom("c", "gba")), defs, resolve),
        )
        assertTrue(json.getBoolean("ready"))
        val byId = (0 until json.getJSONArray("systems").length()).map { json.getJSONArray("systems").getJSONObject(it) }.associateBy { it.getString("id") }

        val psx = byId.getValue("psx")
        assertEquals("system", psx.getString("choice"))
        assertEquals("com.retroarch", psx.getString("playerPackage"))
        assertEquals("psx-beetle-hw", psx.getString("playerId"))
        assertEquals("the entry's own core, not the system's configured one", "mednafen_psx_hw", psx.getString("core"))

        val snes = byId.getValue("snes")
        assertEquals("automatic", snes.getString("choice"))
        assertEquals("a template naming no core falls back to the system's", "snes9x", snes.getString("core"))

        val gba = byId.getValue("gba")
        assertEquals("none", gba.getString("choice"))
        assertEquals("", gba.getString("playerPackage"))
        assertEquals("", gba.getString("core"))
        assertEquals("", gba.getString("playerName"))
    }

    @Test
    fun `a library not published yet says not ready with no systems`() {
        val json = PluginLibraryRead.toJson(emptyList(), ready = false)
        assertFalse(json.getBoolean("ready"))
        assertEquals(0, json.getJSONArray("systems").length())
    }

    @Test
    fun `the person's global default emulator reads as global`() {
        val global = PluginLibraryRead.toJson(
            PluginLibraryRead.systems(listOf(rom("a", "snes")), defs) { ResolvedEmulator(retroArch, EmulatorSource.GLOBAL) },
        )
        assertEquals("global", global.getJSONArray("systems").getJSONObject(0).getString("choice"))
    }
}
