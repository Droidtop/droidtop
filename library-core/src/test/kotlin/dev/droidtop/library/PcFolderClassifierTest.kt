package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [PcFolderClassifier] over folder listings (docs/SPEC.md 7n). Names are placeholders. */
class PcFolderClassifierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun classify(folder: String, vararg paths: String, engine: GameEngine? = null) =
        PcFolderClassifier.classify(paths.map { ListedFile(it) }, folder, engine)

    private fun FolderFacts.collapsedRoles(): Map<String, ExeRole> = collapsed.associate { it.path to it.role }

    // --- roles -----------------------------------------------------------

    @Test
    fun `installers, uninstallers, redistributables and crash handlers are told apart by name`() {
        val expected = mapOf(
            "Game.exe" to ExeRole.GAME,
            "unins000.exe" to ExeRole.UNINSTALLER,
            "Uninstall.exe" to ExeRole.UNINSTALLER,
            "setup.exe" to ExeRole.INSTALLER,
            "Install Game.exe" to ExeRole.INSTALLER,
            "vcredist_x64.exe" to ExeRole.REDISTRIBUTABLE,
            "VC_redist.x86.exe" to ExeRole.REDISTRIBUTABLE,
            "dxsetup.exe" to ExeRole.REDISTRIBUTABLE,
            "dotNetFx45_Full_setup.exe" to ExeRole.REDISTRIBUTABLE,
            "UnityCrashHandler64.exe" to ExeRole.CRASH_HANDLER,
            "crashpad_handler.exe" to ExeRole.CRASH_HANDLER,
            "CrashReporter.exe" to ExeRole.CRASH_HANDLER,
            "Config.exe" to ExeRole.TOOL,
            "Settings.exe" to ExeRole.TOOL,
            "Updater.exe" to ExeRole.TOOL,
            "Editor.exe" to ExeRole.TOOL,
        )
        for ((name, role) in expected) assertEquals(name, role, PcFolderClassifier.roleOf(name))
    }

    @Test
    fun `a program in a runtime or crash folder is that, whatever it is called`() {
        assertEquals(ExeRole.REDISTRIBUTABLE, PcFolderClassifier.roleOf("_redist/Game.exe"))
        assertEquals(ExeRole.REDISTRIBUTABLE, PcFolderClassifier.roleOf("Redist/anything.exe"))
        assertEquals(ExeRole.REDISTRIBUTABLE, PcFolderClassifier.roleOf("DirectX/helper.exe"))
        assertEquals(ExeRole.CRASH_HANDLER, PcFolderClassifier.roleOf("crashpad/handler.exe"))
        assertEquals(ExeRole.GAME, PcFolderClassifier.roleOf("bin/Game.exe"))
    }

    // --- the main program ------------------------------------------------

    @Test
    fun `the game is what is left when everything else is collapsed`() {
        val facts = classify(
            "Some Game", "Game.exe", "unins000.exe", "vcredist_x64.exe", "UnityCrashHandler64.exe", "setup.exe",
        )
        assertEquals("Game.exe", facts.main)
        assertEquals(emptyList<String>(), facts.alternatives)
        assertEquals(
            mapOf(
                "unins000.exe" to ExeRole.UNINSTALLER,
                "UnityCrashHandler64.exe" to ExeRole.CRASH_HANDLER,
                "vcredist_x64.exe" to ExeRole.REDISTRIBUTABLE,
                "setup.exe" to ExeRole.INSTALLER,
            ),
            facts.collapsedRoles(),
        )
        assertFalse(facts.ambiguous)
    }

    @Test
    fun `programs in redistributable folders are collapsed and never the game`() {
        val facts = classify("Some Game", "Game.exe", "_redist/dxsetup.exe", "Redist/vcredist_x86.exe", "dotnet/ndp48.exe")
        assertEquals("Game.exe", facts.main)
        assertEquals(setOf("_redist/dxsetup.exe", "Redist/vcredist_x86.exe", "dotnet/ndp48.exe"), facts.collapsed.map { it.path }.toSet())
    }

    @Test
    fun `a payload folder's program is the game when nothing is at the top`() {
        val facts = classify("Some Game", "bin/Game.exe", "bin/UnityCrashHandler64.exe", "Uninstall.exe")
        assertEquals("bin/Game.exe", facts.main)
        assertEquals(listOf("bin/UnityCrashHandler64.exe", "Uninstall.exe").toSet(), facts.collapsed.map { it.path }.toSet())
    }

    @Test
    fun `the shallowest layer decides, and the deeper programs are alternatives`() {
        val facts = classify("Some Game", "Game.exe", "bin/Game64.exe")
        assertEquals("Game.exe", facts.main)
        assertEquals(listOf("bin/Game64.exe"), facts.alternatives)
    }

    @Test
    fun `several programs are told apart by the folder's own title`() {
        val named = classify("Sample-0.9.5-pc", "Sample.exe", "Helper.exe")
        assertEquals("Sample.exe", named.main)
        assertEquals(listOf("Helper.exe"), named.alternatives)

        val shipping = classify("Some Game", "SomeGame-Win64-Shipping.exe", "Other.exe")
        assertEquals("SomeGame-Win64-Shipping.exe", shipping.main)
    }

    @Test
    fun `a title's leading article does not hide the program named after the rest of it`() {
        // Droidtop/tracker#308: the folder's shape, with placeholder names.
        val facts = classify("The Samples", "SamplesSE.exe", "Maker.exe")
        assertEquals("SamplesSE.exe", facts.main)
        assertEquals(listOf("Maker.exe"), facts.alternatives)

        // The whole title still wins where a program carries it.
        val whole = classify("The Samples", "TheSamples.exe", "Samples.exe")
        assertEquals("TheSamples.exe", whole.main)

        // A title that is only an article is nothing to go by.
        assertNull(classify("The", "Alpha.exe", "Beta.exe").main)
    }

    @Test
    fun `several programs and no name to tell them apart is not guessed`() {
        val facts = classify("Zzz", "Alpha.exe", "Beta.exe")
        assertNull(facts.main)
        assertEquals(listOf("Alpha.exe", "Beta.exe"), facts.alternatives)
        assertTrue(facts.ambiguous)
    }

    @Test
    fun `a tool never outranks a plain program, and is an alternative to it`() {
        val facts = classify("Zzz", "Game.exe", "Config.exe")
        assertEquals("Game.exe", facts.main)
        assertEquals(listOf("Config.exe"), facts.alternatives)
    }

    @Test
    fun `a folder with only a tool offers the tool`() {
        assertEquals("Settings.exe", classify("Zzz", "Settings.exe").main)
    }

    @Test
    fun `a folder with nothing but collapsed programs has no game`() {
        val facts = classify("Zzz", "setup.exe", "unins000.exe")
        assertNull(facts.main)
        assertEquals(emptyList<String>(), facts.alternatives)
        assertFalse(facts.ambiguous)
    }

    @Test
    fun `an empty folder has nothing`() {
        val facts = classify("Zzz")
        assertNull(facts.main)
        assertEquals(emptyList<CollapsedExecutable>(), facts.collapsed)
    }

    @Test
    fun `the engine the detector reported is carried through, not decided here`() {
        assertEquals(GameEngine.RENPY, classify("Some Game", "Game.exe", engine = GameEngine.RENPY).engine)
        assertNull(classify("Some Game", "Game.exe").engine)
    }

    // --- the bounded listing ---------------------------------------------

    private fun touch(path: String) = File(tmp.root, path).also { it.parentFile?.mkdirs(); it.createNewFile() }

    @Test
    fun `the listing is relative, breadth first, skips hidden folders and is bounded`() {
        touch("Game.exe")
        touch("bin/Game64.exe")
        touch("bin/deep/more/Hidden.exe")
        touch(".git/ignored.exe")
        touch("data/a.bin")
        val listed = PcFolderClassifier.read(tmp.root).map { it.path }
        assertTrue(listed.containsAll(listOf("Game.exe", "bin/Game64.exe", "data/a.bin")))
        assertFalse(listed.any { it.startsWith(".git") })
        // Two folders below the root is as deep as it reads.
        assertFalse(listed.contains("bin/deep/more/Hidden.exe"))

        val many = TemporaryFolder().also { it.create() }
        try {
            repeat(30) { File(many.root, "f$it.txt").createNewFile() }
            assertEquals(10, PcFolderClassifier.read(many.root, maxEntries = 10).size)
        } finally {
            many.delete()
        }
    }

    // --- the resolver answers through it ---------------------------------

    @Test
    fun `the resolver ignores crash handlers and finds a payload program`() {
        val crash = File(tmp.root, "withcrash").also { it.mkdirs() }
        File(crash, "Game.exe").createNewFile()
        File(crash, "UnityCrashHandler64.exe").createNewFile()
        assertEquals("Game.exe", GameExecutableResolver.windowsExecutable(crash)?.name)

        val payload = File(tmp.root, "payload").also { it.mkdirs() }
        File(payload, "setup.exe").createNewFile()
        File(payload, "bin").mkdirs()
        File(payload, "bin/Game.exe").createNewFile()
        assertEquals("bin/Game.exe", GameExecutableResolver.windowsExecutable(payload)?.relativeTo(payload)?.path)
    }
}
