package dev.droidtop.library.saves

import dev.droidtop.library.EngineRegistryParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SaveRulesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `an engine rule is read, and one with an unknown version or no source is skipped`() {
        val rule = EngineSaves.parse(
            JSONObject("""{"v":1,"beside":["game/saves"],"data":{"linux":"<home>/.renpy"},"user":["{a}"],"hostUser":["{a|game_folder}"],"facts":{"a":"renpy.save_directory"},"source":"s"}"""),
        )!!
        assertEquals(listOf("game/saves"), rule.beside)
        assertEquals(mapOf("linux" to "<home>/.renpy"), rule.data)
        assertEquals(listOf("{a|game_folder}"), rule.hostUser)
        assertEquals(mapOf("a" to "renpy.save_directory"), rule.facts)
        assertNull(EngineSaves.parse(JSONObject("""{"v":2,"source":"s"}""")))
        assertNull(EngineSaves.parse(JSONObject("""{"v":1,"beside":["x"]}""")))
        assertNull(EngineSaves.parse(null))
    }

    @Test
    fun `an emulator rule fills the package into its paths`() {
        val rule = EmulatorSaves.parse(
            JSONObject(
                """{"v":1,"folders":["/sdcard/Android/data/{pkg}/files/saves"],"folderKey":"k","config":{"file":"/sdcard/{pkg}.cfg","format":"ini"},
                    "patterns":["<emuSaves>/{rom_stem}.srm"],"states":["<emuStates>/x"],"source":"s"}""",
            ),
            "com.x",
        )!!
        assertEquals(listOf("/sdcard/Android/data/com.x/files/saves"), rule.folders)
        assertEquals("/sdcard/com.x.cfg", rule.configFile)
        assertEquals(dev.droidtop.library.consoles.ConfigFormat.INI, rule.configFormat)
        assertEquals(listOf("<emuStates>/x"), rule.all.states)
        assertNull(EmulatorSaves.parse(JSONObject("""{"v":9,"source":"s"}"""), "com.x"))
    }

    /** The composed database in the pinned droidtop-platforms snapshot, when the checkout has it. */
    private fun snapshot(name: String): String? =
        listOf("../vendor/droidtop-platforms/$name", "vendor/droidtop-platforms/$name").map(::File).firstOrNull { it.isFile }?.readText()

    @Test
    fun `every rule in the pinned engines database parses, cites its source and uses only readers droidtop has`() {
        val text = snapshot("engines-database.json")
        assumeTrue("the pinned droidtop-platforms snapshot is not checked out", text != null)
        val root = JSONObject(text!!)
        val rows = root.getJSONArray("engines")
        var withRules = 0
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val saves = row.optJSONObject("saves") ?: continue
            // A rule this app does not know the version of is skipped, which is fine; one it knows must parse whole.
            if (saves.optInt("v") != EngineSaves.VERSION) continue
            val rule = EngineSaves.parse(saves)
            assertNotNull(row.getString("id") + " does not parse", rule)
            assertTrue(row.getString("id") + " has no source", rule!!.source.isNotBlank())
            for (reader in rule.facts.values) assertTrue("$reader is not a reader droidtop has", SaveFacts.hasReader(reader))
            for (template in rule.data.values) assertNotNull(row.getString("id") + " data " + template, SaveTemplates.split(template))
            withRules++
        }
        // The engines the owner asked for first.
        assertTrue("fewer rules than expected: $withRules", withRules >= 15)
        val parsed = EngineRegistryParser.parse(text).associateBy { it.id }
        for (id in listOf("renpy", "rpgmaker-mv", "rpgmaker-mz", "rpgmaker-2000-2003", "rpgmaker-vxace", "kirikiri", "nscripter", "godot", "love2d", "ags", "cmvs", "buriko")) {
            assertNotNull("$id has no save rule", parsed[id]?.saves)
        }
    }

    @Test
    fun `the Renpy rule from the real database finds a game the way the engine saves it`() {
        val text = snapshot("engines-database.json")
        assumeTrue("the pinned droidtop-platforms snapshot is not checked out", text != null)
        val rule = EngineRegistryParser.parse(text!!).first { it.id == "renpy" }.saves
        assumeTrue("the pinned snapshot predates save rules", rule != null)
        val game = File(temp.root, "games/Demo").also { File(it, "game").mkdirs() }
        File(game, "game/options.rpy").writeText("define config.save_directory = \"Demo-1600000000\"\n")
        File(temp.root, "eh/saves/Demo-1600000000").mkdirs()
        File(temp.root, "eh/saves/Demo-1600000000/1-1-LT1.save").writeText("save")
        File(game, "game/saves").mkdirs()
        File(game, "game/saves/persistent").writeText("p")
        val plan = SavePlans.engine(rule!!, game, File(temp.root, "eh/saves"), null) { SaveFacts.read(it, game) }
        val status = SaveSearch.run(plan) as SaveStatus.Found
        assertEquals(2, status.files)
    }

    @Test
    fun `the RetroArch rule from the real database reaches every RetroArch package`() {
        val text = snapshot("players-database.json")
        assumeTrue("the pinned droidtop-platforms snapshot is not checked out", text != null)
        val rows = JSONObject(text!!).getJSONArray("players")
        val packages = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("pkg").startsWith("com.retroarch") }
        assumeTrue("the pinned snapshot predates save rules", packages.any { it.has("saves") })
        for (row in packages) {
            val rule = EmulatorSaves.parse(row.optJSONObject("saves"), row.getString("pkg"))
            assertNotNull(row.getString("id") + " has no save rule", rule)
            assertTrue(rule!!.folders.any { it.contains(row.getString("pkg")) })
        }
    }
}
