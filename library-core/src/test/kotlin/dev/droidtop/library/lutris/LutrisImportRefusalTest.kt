package dev.droidtop.library.lutris

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The refusal half of the closed mapping tables, which is the security
 * boundary of docs/security/2026-09-25-lutris-importer.md: every
 * directive SPEC 7e3 (docs/SPEC.md 5497-5510) names -- anything that
 * would execute, fetch, write a file, touch the registry or ask the
 * installer a question -- is refused into notImported by name, and a
 * script over a parse limit is refused whole rather than half-read.
 * Each refused directive has its own test, so a regression names exactly
 * which one let something through.
 */
class LutrisImportRefusalTest {

    private fun obj(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    private fun step(name: String, value: Any): JSONObject = obj(name to value)

    private fun script(vararg steps: JSONObject): JSONObject =
        obj("installer" to JSONArray().apply { steps.forEach { put(it) } })

    private fun translate(vararg steps: JSONObject): LutrisImportPlan = LutrisImport.translate(script(*steps))

    private fun assertRefused(plan: LutrisImportPlan, what: String, detail: String) {
        assertEquals(listOf(ImportLine(what, detail)), plan.notImported)
    }

    // ---- the directives SPEC 7e3 names, one test each ---------------------

    @Test
    fun `execute is refused as not imported and never run`() {
        assertRefused(
            translate(step("execute", obj("command" to "bash install.sh"))),
            "Runs a shell command",
            "droidtop never runs a program or command a script names",
        )
        assertRefused(
            translate(step("execute", obj("file" to "setup.exe"))),
            "Runs setup.exe",
            "droidtop never runs a program or command a script names",
        )
    }

    @Test
    fun `task wineexec is refused as not imported and never run`() {
        assertRefused(
            translate(step("task", obj("name" to "wineexec", "executable" to "install.exe"))),
            "Runs install.exe in the prefix",
            "droidtop never runs a program a script names",
        )
        // Scripts also spell the task with its wine. prefix.
        assertRefused(
            translate(step("task", obj("name" to "wine.wineexec"))),
            "Runs a program in the prefix",
            "droidtop never runs a program a script names",
        )
    }

    @Test
    fun `write_file is refused, droidtop does not change files in a game's folder`() {
        assertRefused(
            translate(step("write_file", obj("file" to "\$GAMEDIR/dxvk.conf"))),
            "Writes \$GAMEDIR/dxvk.conf",
            "droidtop does not change files in a game's folder; make the change by hand if the game needs it",
        )
    }

    @Test
    fun `write_config is refused, droidtop does not change files in a game's folder`() {
        assertRefused(
            translate(step("write_config", obj("file" to "settings.ini", "section" to "Game"))),
            "Writes settings.ini",
            "droidtop does not change files in a game's folder; make the change by hand if the game needs it",
        )
    }

    @Test
    fun `write_json is refused, droidtop does not change files in a game's folder`() {
        assertRefused(
            translate(step("write_json", obj("data" to "{}"))),
            "Writes a file",
            "droidtop does not change files in a game's folder; make the change by hand if the game needs it",
        )
    }

    @Test
    fun `extract is refused, the files are already in the folder`() {
        assertRefused(
            translate(step("extract", obj("file" to "patch.zip"))),
            "Unpacks patch.zip",
            "The game's files are already in its folder; droidtop does not unpack, copy or move them",
        )
    }

    @Test
    fun `move is refused, the files are already in the folder`() {
        assertRefused(
            translate(step("move", obj("src" to "a", "dst" to "b"))),
            "Moves a",
            "The game's files are already in its folder; droidtop does not unpack, copy or move them",
        )
    }

    @Test
    fun `merge is refused, the files are already in the folder`() {
        assertRefused(
            translate(step("merge", obj("src" to "data", "dst" to "b"))),
            "Copies data",
            "The game's files are already in its folder; droidtop does not unpack, copy or move them",
        )
    }

    @Test
    fun `copy is refused, the files are already in the folder`() {
        assertRefused(
            translate(step("copy", obj("src" to "saves", "dst" to "b"))),
            "Copies saves",
            "The game's files are already in its folder; droidtop does not unpack, copy or move them",
        )
    }

    @Test
    fun `mkdir chmodx and rename are refused like the other file verbs`() {
        val detail = "The game's files are already in its folder; droidtop does not unpack, copy or move them"
        assertRefused(translate(step("mkdir", obj("file" to "\$GAMEDIR/mods"))), "Makes a folder \$GAMEDIR/mods", detail)
        // A directive may also be a bare string, as Lutris's own YAML spells chmodx.
        assertRefused(translate(step("chmodx", "\$GAMEDIR/tool.sh")), "Makes executable \$GAMEDIR/tool.sh", detail)
        assertRefused(translate(step("rename", obj("src" to "a", "dst" to "b"))), "Renames a", detail)
    }

    @Test
    fun `insert-disc is refused, droidtop runs no installer`() {
        assertRefused(
            translate(step("insert-disc", obj("requires" to "setup.exe"))),
            "Asks for the game's disc",
            "droidtop runs no installer",
        )
    }

    @Test
    fun `input_menu is refused, the answer only ever served the installer`() {
        assertRefused(
            translate(step("input_menu", obj("description" to "Graphics preset", "options" to JSONArray().put("Low").put("High")))),
            "Asks you to choose: Graphics preset",
            "Only the installer used the answer, and droidtop runs no installer",
        )
    }

    @Test
    fun `gogdl_setup is refused, downloading is the store's own job`() {
        assertRefused(
            translate(step("gogdl_setup", obj("lang" to "en"))),
            "Downloads the game from GOG",
            "Sign in to GOG under Stores and folders to install it there",
        )
    }

    @Test
    fun `set_regedit is refused, named with the key it wanted`() {
        assertRefused(
            translate(
                step(
                    "task",
                    obj("name" to "wine.set_regedit", "path" to "HKEY_CURRENT_USER\\Software\\Vendor", "key" to "Option", "value" to "1"),
                ),
            ),
            "Registry: HKEY_CURRENT_USER\\Software\\Vendor\\Option = 1",
            "droidtop does not edit the registry; set it by hand if the game needs it",
        )
    }

    @Test
    fun `set_regedit_file is refused like every registry change`() {
        assertRefused(
            translate(step("task", obj("name" to "wine.set_regedit_file", "filename" to "fix.reg"))),
            "Registry change",
            "droidtop does not edit the registry; make it by hand if the game needs it",
        )
    }

    @Test
    fun `an unknown directive is refused, not guessed`() {
        assertRefused(translate(step("download_certs", obj())), "Step \"download_certs\"", "Not a step droidtop imports")
    }

    @Test
    fun `an unknown task is refused, not guessed`() {
        assertRefused(translate(step("task", obj("name" to "wine.tweak"))), "Task \"tweak\"", "Not a task droidtop imports")
    }

    @Test
    fun `a task without a name is refused`() {
        assertRefused(translate(step("task", "winetricks")), "Task \"unnamed\"", "Not a task droidtop imports")
    }

    @Test
    fun `every refused directive together leaves the settings untouched`() {
        val plan = translate(
            step("execute", obj("command" to "bash x.sh")),
            step("task", obj("name" to "wineexec", "executable" to "install.exe")),
            step("write_file", obj("file" to "\$GAMEDIR/f.txt")),
            step("write_config", obj("file" to "c.ini")),
            step("write_json", obj("data" to "{}")),
            step("extract", obj("file" to "p.zip")),
            step("move", obj("src" to "a", "dst" to "b")),
            step("merge", obj("src" to "a", "dst" to "b")),
            step("copy", obj("src" to "a", "dst" to "b")),
            step("insert-disc", obj("requires" to "x")),
            step("input_menu", obj("description" to "Pick")),
            step("gogdl_setup", obj()),
            step("task", obj("name" to "wine.set_regedit", "key" to "K")),
            step("task", obj("name" to "wine.set_regedit_file")),
        )
        assertEquals(14, plan.notImported.size)
        assertTrue("A refused directive must not change any setting", plan.prefix.isEmpty)
        assertNull(plan.exePath)
        assertTrue(plan.covered.isEmpty())
    }

    // ---- a script over a parse limit is refused whole ----------------------
    // Threat model, decision 8: never half-read a script.

    private fun assertRefusedWhole(message: String, script: JSONObject) {
        try {
            LutrisImport.translate(script)
            fail("Expected the script to be refused whole")
        } catch (e: LutrisScriptRefused) {
            assertEquals(message, e.message)
        }
    }

    @Test
    fun `a script with more steps than the limit is refused whole`() {
        val oneStep = step("execute", obj())
        val installer = JSONArray()
        repeat(LutrisImport.MAX_DIRECTIVES + 1) { installer.put(oneStep) }
        assertRefusedWhole("The script has more steps than droidtop reads", obj("installer" to installer))
    }

    @Test
    fun `a script at the step limit still translates`() {
        val oneStep = step("execute", obj())
        val installer = JSONArray()
        repeat(LutrisImport.MAX_DIRECTIVES) { installer.put(oneStep) }
        val plan = LutrisImport.translate(obj("installer" to installer))
        assertEquals(LutrisImport.MAX_DIRECTIVES, plan.notImported.size)
    }

    @Test
    fun `a script with more files than the limit is refused whole`() {
        val oneFile = obj("patch" to "N/A")
        val files = JSONArray()
        repeat(LutrisImport.MAX_FILES + 1) { files.put(oneFile) }
        assertRefusedWhole("The script lists more files than droidtop reads", obj("files" to files))
    }

    @Test
    fun `a value or a key longer than the limit is refused whole`() {
        val longValue = "a".repeat(LutrisImport.MAX_STRING + 1)
        assertRefusedWhole("The script has a value longer than droidtop reads", obj("game" to obj("args" to longValue)))
        val longKey = "k".repeat(LutrisImport.MAX_STRING + 1)
        assertRefusedWhole("The script has a key longer than droidtop reads", obj(longKey to "value"))
    }

    @Test
    fun `a script nested too deeply is refused whole`() {
        var nested: Any = obj("marker" to true)
        repeat(20) { nested = obj("child" to nested) }
        assertRefusedWhole("The script is nested too deeply", obj("game" to nested))
    }
}
