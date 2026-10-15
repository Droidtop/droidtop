package dev.droidtop.library.saves

import dev.droidtop.library.consoles.ConfigFormat
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SavePlansTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun file(path: String, body: String = "x", at: Long? = null): File =
        File(temp.root, path).also { it.parentFile?.mkdirs() }.also { it.writeText(body) }.also { f -> at?.let { f.setLastModified(it) } }

    private val renpy = EngineSaves(
        beside = listOf("game/saves"),
        data = mapOf("windows" to "<winAppData>/RenPy", "linux" to "<home>/.renpy"),
        user = listOf("{save_directory}"),
        hostUser = listOf("{save_directory|game_folder}"),
        facts = mapOf("save_directory" to "renpy.save_directory"),
        source = "renpy.py path_to_saves",
    )

    @Test
    fun `a Renpy game's saves are found beside it, in Enginehost's folder and in its Windows setup`() {
        val game = File(temp.root, "games/Some Game-1.0").also { it.mkdirs() }
        file("games/Some Game-1.0/game/saves/1-1-LT1.save", at = 1_000L)
        file("enginehost/saves/Some-1234/persistent")
        file("enginehost/saves/Some-1234/2-1-LT1.save")
        file("prefix/users/steamuser/AppData/Roaming/RenPy/Some-1234/3-1-LT1.save")
        val wine = mapOf("<winAppData>" to File(temp.root, "prefix/users/steamuser/AppData/Roaming"))
        val plan = SavePlans.engine(renpy, game, File(temp.root, "enginehost/saves"), wine) { "Some-1234" }
        val status = SaveSearch.run(plan) as SaveStatus.Found
        assertEquals(setOf("the game's folder", "Enginehost's save folder", "its Windows setup"), status.places.map { it.label }.toSet())
        assertEquals(4, status.files)
        assertTrue(status.note == null)
    }

    @Test
    fun `Enginehost names the folder for the game folder when the game sets no directory`() {
        val game = File(temp.root, "games/The Game").also { it.mkdirs() }
        file("enginehost/saves/The Game/1-1-LT1.save")
        val plan = SavePlans.engine(renpy, game, File(temp.root, "enginehost/saves"), null) { null }
        val status = SaveSearch.run(plan) as SaveStatus.Found
        assertEquals("Enginehost's save folder", status.places.single().label)
    }

    @Test
    fun `a name that cannot be read is said, not guessed`() {
        val godot = EngineSaves(
            beside = emptyList(),
            data = mapOf("windows" to "<winAppData>", "linux" to "<xdgData>"),
            user = listOf("{godot_custom_dir}", "Godot/app_userdata/{godot_name}"),
            hostUser = emptyList(),
            facts = mapOf("godot_name" to "godot.name", "godot_custom_dir" to "godot.custom_dir"),
            source = "os.cpp",
        )
        val game = File(temp.root, "g").also { it.mkdirs() }
        val plan = SavePlans.engine(godot, game, File(temp.root, "root"), null) { null }
        assertEquals(emptyList<SavePlace>(), plan.places)
        val status = SaveSearch.run(plan)
        assertTrue(status is SaveStatus.Unknown)
        assertTrue((status as SaveStatus.Unknown).why.contains("project's name"))
    }

    @Test
    fun `alternatives need only one name`() {
        val godot = EngineSaves(
            beside = emptyList(),
            data = mapOf("linux" to "<xdgData>"),
            user = listOf("{godot_custom_dir}", "godot/app_userdata/{godot_name}"),
            hostUser = emptyList(),
            facts = mapOf("godot_name" to "godot.name", "godot_custom_dir" to "godot.custom_dir"),
            source = "os.cpp",
        )
        file("root/godot/app_userdata/My Game/savegame.save")
        val plan = SavePlans.engine(godot, File(temp.root, "g").also { it.mkdirs() }, File(temp.root, "root"), null) { if (it == "godot.name") "My Game" else null }
        assertTrue(plan.unknown.isEmpty())
        assertEquals(1, (SaveSearch.run(plan) as SaveStatus.Found).files)
    }

    @Test
    fun `nothing found names the places it looked`() {
        val game = File(temp.root, "g").also { it.mkdirs() }
        val plan = SavePlans.engine(renpy, game, File(temp.root, "enginehost/saves"), null) { "Name-1" }
        val status = SaveSearch.run(plan) as SaveStatus.NoneFound
        assertEquals(listOf("the game's folder", "Enginehost's save folder"), status.looked)
    }

    @Test
    fun `an engine that names its folder only inside the game is unknown, never none`() {
        val ags = EngineSaves(emptyList(), mapOf("windows" to "<home>/Saved Games"), emptyList(), emptyList(), emptyMap(), "acpland.cpp")
        val status = SaveSearch.run(SavePlans.engine(ags, File(temp.root, "g").also { it.mkdirs() }, File(temp.root, "root"), null) { null })
        assertTrue(status is SaveStatus.Unknown)
    }

    @Test
    fun `host folder names are made safe the way Enginehost makes them`() {
        assertEquals("A_B_C", SavePlans.hostFolderName("A/B:C"))
        assertEquals("game", SavePlans.hostFolderName("..."))
        assertEquals("Name", SavePlans.hostFolderName("  Name. "))
    }

    private val retroArch = EmulatorSaves(
        folders = listOf("/nowhere/RetroArch/saves"),
        folderKey = "savefile_directory",
        statesFolders = listOf("/nowhere/RetroArch/states"),
        statesFolderKey = "savestate_directory",
        configFile = "/nowhere/retroarch.cfg",
        configFormat = ConfigFormat.KEY_VALUE,
        all = EmulatorSavePatterns(
            patterns = listOf("<emuSaves>/{rom_stem}.srm", "<emuSaves>/*/{rom_stem}.srm", "{rom_dir}/{rom_stem}.srm"),
            states = listOf("<emuStates>/{rom_stem}.state*"),
            cards = emptyList(),
        ),
        systems = emptyMap(),
        source = "RetroArch runloop.c",
    )

    @Test
    fun `an emulator game finds its save in the configured folder, sorted by core, and keeps its states apart`() {
        val rom = file("roms/gba/Mother 3 (Eng).gba")
        val saves = File(temp.root, "ra/saves")
        file("ra/saves/mGBA/Mother 3 (Eng).srm")
        file("ra/states/Mother 3 (Eng).state1")
        file("ra/states/Mother 3 (Eng).state")
        val config = "savefile_directory = \"${saves.path}\"\nsavestate_directory = \"${File(temp.root, "ra/states").path}\"\n"
        val plan = SavePlans.emulator(retroArch, "gba", rom, config)
        val status = SaveSearch.run(plan) as SaveStatus.Found
        assertEquals(1, status.files)
        assertEquals(File(temp.root, "ra/saves").path, status.places.single().folder)
        assertEquals(2, status.states.single().files)
    }

    @Test
    fun `an emulator that saves next to the game is found there, and a name with star in it is only itself`() {
        val rom = file("roms/snes/Odd * Game.sfc")
        file("roms/snes/Odd * Game.srm")
        file("roms/snes/Odd X Game.srm")
        val plan = SavePlans.emulator(retroArch, "snes", rom, null)
        val status = SaveSearch.run(plan) as SaveStatus.Found
        // The star became a question mark, so it matches one character: both names fit, and nothing else does.
        assertEquals(2, status.files)
    }

    @Test
    fun `no save and no state is none found, and a state alone is not a save`() {
        val rom = file("roms/nes/Game.nes")
        file("states/Game.state1")
        val withStates = retroArch.copy(statesFolders = listOf(File(temp.root, "states").path), folders = listOf(File(temp.root, "saves").path))
        val none = SaveSearch.run(SavePlans.emulator(withStates, "nes", rom, null)) as SaveStatus.NoneFound
        assertEquals(1, none.states.single().files)
    }

    @Test
    fun `a folder droidtop may not list is said, not reported as empty`() {
        val rom = file("roms/nes/Game.nes")
        val rule = retroArch.copy(
            folders = listOf("/storage/emulated/0/Android/data/com.x/files/saves"),
            all = retroArch.all.copy(patterns = listOf("<emuSaves>/{rom_stem}.srm")),
        )
        val plan = SavePlans.emulator(rule, "nes", rom, null) { false }
        val status = SaveSearch.run(plan.copy(places = emptyList()))
        assertTrue(status is SaveStatus.Unknown)
        assertEquals(listOf("/storage/emulated/0/Android/data/com.x/files/saves"), plan.blocked)
    }

    @Test
    fun `a system can have its own patterns and a card is a set of its own`() {
        val rule = EmulatorSaves.parse(
            JSONObject(
                """{"v":1,"folders":["${temp.root.path}/cards"],"patterns":["<emuSaves>/{rom_stem}.srm"],
                    "systems":{"ps2":{"patterns":[],"cards":["<emuSaves>/Mcd001.ps2"]}},"source":"s"}""",
            ),
            "x.y",
        )!!
        file("cards/Mcd001.ps2")
        val rom = file("roms/ps2/Game.iso")
        val status = SaveSearch.run(SavePlans.emulator(rule, "ps2", rom, null)) as SaveStatus.Found
        assertTrue(status.places.isEmpty())
        assertEquals(1, status.cards.single().files)
        assertEquals(1, rule.systems.size)
        assertNotNull(rule.forSystem("ps2"))
        assertEquals(rule.all, rule.forSystem("snes"))
    }

    @Test
    fun `templates from a computer are searched in the Windows setup and the game folder`() {
        val game = File(temp.root, "game").also { it.mkdirs() }
        file("game/saves/a.sav")
        file("w/AppData/Roaming/Studio/b.sav")
        val wine = mapOf("<winAppData>" to File(temp.root, "w/AppData/Roaming"))
        val plan = SavePlans.templates("a paired computer's rule", listOf("<base>/saves/*.sav", "<winAppData>/Studio"), game, wine)
        assertEquals(2, (SaveSearch.run(plan) as SaveStatus.Found).files)
        val noPrefix = SavePlans.templates("r", listOf("<winAppData>/Studio"), game, null)
        assertTrue(SaveSearch.run(noPrefix) is SaveStatus.Unknown)
        assertNull(SaveTemplates.split("nonsense"))
    }

    @Test
    fun `the Windows setup's tokens follow the agent core's layout`() {
        val roots = SavePlans.prefixRoots(dev.droidtop.library.stores.WinePrefixLocation(File("/p"), "steamuser"))
        assertEquals(File("/p/drive_c/users/steamuser/AppData/Roaming"), roots["<winAppData>"])
        assertEquals(File("/p/drive_c/users/Public"), roots["<winPublic>"])
        assertEquals(File("/p/drive_c/windows"), roots["<winDir>"])
    }
}
