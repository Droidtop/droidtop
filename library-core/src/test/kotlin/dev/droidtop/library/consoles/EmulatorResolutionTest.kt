package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one resolver behind the launcher and the emulator screens
 * (Droidtop/tracker#248): game choice, then system choice, then the global
 * default emulator, then the first installed candidate. The players here
 * are stand-ins with placeholder ids; only the ordering rules are tested.
 */
class EmulatorResolutionTest {
    private fun player(id: String, name: String, pkg: String) =
        Player.AmStart(id = id, name = name, argumentsTemplate = "-n $pkg/.Main -d {file.uri}", packageName = pkg)

    private val standalone = player("sys-standalone", "Standalone Emu", "org.example.standalone")
    private val retro = player("retroarch-sys", "RetroArch", "com.retroarch")
    private val other = player("sys-other", "Other Emu", "org.example.other")
    private val candidates = listOf(standalone, retro, other)

    @Test
    fun nothingInstalledResolvesToNothing() {
        assertNull(EmulatorResolution.resolve(emptyList(), "RetroArch", "x", "com.retroarch"))
    }

    @Test
    fun withNoChoicesTheFirstCandidateWins() {
        val r = EmulatorResolution.resolve(candidates, null, null, null)!!
        assertEquals(standalone, r.player)
        assertEquals(EmulatorSource.AUTOMATIC, r.source)
    }

    @Test
    fun globalDefaultBeatsTheFirstCandidateButOnlyWhenItCanRunTheSystem() {
        val r = EmulatorResolution.resolve(candidates, null, null, "com.retroarch")!!
        assertEquals(retro, r.player)
        assertEquals(EmulatorSource.GLOBAL, r.source)
        // The global default app cannot run this system: the fallback answers.
        val fallback = EmulatorResolution.resolve(listOf(standalone, other), null, null, "com.retroarch")!!
        assertEquals(standalone, fallback.player)
        assertEquals(EmulatorSource.AUTOMATIC, fallback.source)
    }

    @Test
    fun systemChoiceBeatsGlobalDefault() {
        val r = EmulatorResolution.resolve(candidates, null, other.id, "com.retroarch")!!
        assertEquals(other, r.player)
        assertEquals(EmulatorSource.SYSTEM, r.source)
    }

    @Test
    fun gameChoiceBeatsSystemAndGlobal() {
        val r = EmulatorResolution.resolve(candidates, standalone.id, other.id, "com.retroarch")!!
        assertEquals(standalone, r.player)
        assertEquals(EmulatorSource.GAME, r.source)
    }

    @Test
    fun gameChoiceMatchesByLabelIgnoringCaseAsEsDeStoresIt() {
        val r = EmulatorResolution.resolve(candidates, "  retroarch ", other.id, null)!!
        assertEquals(retro, r.player)
        assertEquals(EmulatorSource.GAME, r.source)
    }

    @Test
    fun aStaleChoiceFallsThroughInsteadOfFailing() {
        // The game and system both name emulators that are no longer installed.
        val r = EmulatorResolution.resolve(candidates, "Gone Emu", "sys-gone", "com.retroarch")!!
        assertEquals(retro, r.player)
        assertEquals(EmulatorSource.GLOBAL, r.source)
        val last = EmulatorResolution.resolve(candidates, "Gone Emu", "sys-gone", "com.gone")!!
        assertEquals(standalone, last.player)
        assertEquals(EmulatorSource.AUTOMATIC, last.source)
    }

    @Test
    fun blankGameChoiceIsNoChoice() {
        val r = EmulatorResolution.resolve(candidates, "   ", other.id, null)!!
        assertEquals(EmulatorSource.SYSTEM, r.source)
    }

    @Test
    fun withoutGameChoiceReportsWhatAGameWithNoSettingGets() {
        val r = EmulatorResolution.resolveWithoutGame(candidates, null, "org.example.other")!!
        assertEquals(other, r.player)
        assertEquals(EmulatorSource.GLOBAL, r.source)
        assertNull(EmulatorResolution.matchGameChoice(candidates, null))
        assertEquals(other, EmulatorResolution.matchGameChoice(candidates, "Other Emu"))
    }

    @Test
    fun emulatorAppsAreGroupedByPackageAcrossSystems() {
        val apps = groupEmulatorApps(
            listOf(
                "gba" to retro,
                "psx" to retro,
                "psx" to retro,
                "psx" to standalone,
            ),
        ) { it == "com.retroarch" }
        val retroApp = apps.single { it.packageName == "com.retroarch" }
        assertTrue(retroApp.installed)
        assertEquals(listOf("gba", "psx"), retroApp.systemIds)
        assertEquals(false, apps.single { it.packageName == "org.example.standalone" }.installed)
    }
}
