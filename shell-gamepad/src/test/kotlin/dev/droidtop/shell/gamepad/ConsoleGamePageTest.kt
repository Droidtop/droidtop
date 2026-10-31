package dev.droidtop.shell.gamepad

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.SwitchGameFacts
import dev.droidtop.shell.gamepad.pc.PageKind
import dev.droidtop.shell.gamepad.pc.PageStatusKind
import dev.droidtop.shell.gamepad.pc.PageStatusTone
import dev.droidtop.shell.gamepad.pc.PageTab
import dev.droidtop.shell.gamepad.pc.groupRowsByTab
import dev.droidtop.shell.gamepad.pc.pageStatuses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A console game's page in the one frame every game's page shares (EntryDetailScreen.kt; docs/SPEC.md 7i, "The
 * page's frame"; Droidtop/tracker#845 slice U2): which tab each of its facts lands on, and what its primary action
 * and status row say about the emulator.
 */
class ConsoleGamePageTest {
    private val day = 24 * 60 * 60 * 1000L

    private val rom = LibraryEntry(
        id = "/storage/emulated/0/ROMs/snes/Super Metroid.sfc",
        title = "Super Metroid",
        kind = LibraryEntryKind.CONSOLE_ROM,
        systemId = "snes",
        description = "Samus returns to Zebes.",
        developer = "Nintendo",
        manualUri = "/storage/emulated/0/ROMs/snes/manuals/Super Metroid.pdf",
        fieldSources = mapOf("description" to "ScreenScraper"),
    )

    private fun rows(entry: LibraryEntry, now: Long = 100 * day) = groupRowsByTab(
        consolePageRows(entry, systemName = "Super Nintendo", emulator = "Snes9x (your default emulator)", now = now, onAddSwitchContent = {}),
        emptyList(),
    )

    @Test
    fun aConsoleGamesFactsLandOnTheSameTabsAsAPcGames() {
        val byTab = rows(rom.copy(lastPlayedEpochMs = 100 * day - 2 * day, playtimeSeconds = 3600, playCount = 3))
        assertEquals(PageTab.values().toList(), byTab.keys.toList())
        assertEquals(listOf("About"), byTab.getValue(PageTab.OVERVIEW).map { it.title })
        assertEquals(listOf("Last played", "Play time", "Times played"), byTab.getValue(PageTab.ACTIVITY).map { it.title })
        assertEquals(listOf("Manual", "Where these facts came from"), byTab.getValue(PageTab.EXTRAS).map { it.title })
        assertEquals(listOf("Kind", "System", "Emulator", "File", "Developer"), byTab.getValue(PageTab.DETAILS).map { it.title })
        // Where the file lives as a person names it; the whole path is the row's tip.
        val file = byTab.getValue(PageTab.DETAILS).first { it.title == "File" }
        assertEquals("Internal storage / ROMs / snes / Super Metroid.sfc", file.value)
        assertEquals(rom.id, file.tip)
        // Nothing of a plain cartridge game is a version or a thread: those tabs say their empty lines.
        assertTrue(byTab.getValue(PageTab.VERSIONS).isEmpty())
        assertTrue(byTab.getValue(PageTab.THREAD).isEmpty())
    }

    @Test
    fun aSwitchGamesUpdateAndDlcAreOnVersionsAndAddThemWhenThereAreFiles() {
        val switch = rom.copy(
            id = "/roms/switch/Game.nsp",
            systemId = "switch",
            switchFacts = SwitchGameFacts(hasUpdate = true, updateVersion = "131072", dlcCount = 2, updatePaths = listOf("/roms/switch/Game [v131072].nsp")),
        )
        val versions = rows(switch).getValue(PageTab.VERSIONS)
        assertEquals(listOf(SWITCH_CONTENT_ROW), versions.map { it.title })
        assertEquals("Update v131072 · 2 DLC", versions.single().value)
        assertNotNull("A opens the emulator and names the files", versions.single().onActivate)
        // A row that is itself DLC whose game is missing says so, and has nothing to add.
        val loose = rows(switch.copy(switchFacts = SwitchGameFacts(dlcCount = 1, loose = true))).getValue(PageTab.VERSIONS).single()
        assertEquals("DLC without base game", loose.value)
        assertNull(loose.onActivate)
    }

    @Test
    fun anAppsPageNamesItsPackageAndNoSystem() {
        val app = LibraryEntry(id = "org.example.reader", title = "Reader", kind = LibraryEntryKind.NATIVE_ANDROID_APP)
        val details = groupRowsByTab(consolePageRows(app, systemName = null, emulator = null, now = 0, onAddSwitchContent = {}), emptyList())
            .getValue(PageTab.DETAILS)
        assertEquals(listOf("Kind" to "Android app", "Package" to "org.example.reader"), details.map { it.title to it.value })
        assertEquals("Open", consolePlayState(kind = PageKind.APP, emulatorsKnown = false, emulator = null, systemName = null).verb)
    }

    @Test
    fun playSaysWhichEmulatorRunsItOrThatNoneIsInstalled() {
        val ready = consolePlayState(kind = PageKind.GAME, emulatorsKnown = true, emulator = "Snes9x", systemName = "Super Nintendo")
        assertEquals("Play", ready.verb)
        assertTrue(ready.ready)
        assertEquals("Runs with Snes9x", ready.detail)
        val missing = consolePlayState(kind = PageKind.GAME, emulatorsKnown = true, emulator = null, systemName = "Super Nintendo")
        assertFalse(missing.ready)
        // Still pressable: the launch says what is missing and how to get it.
        assertTrue(missing.pressable)
        assertEquals("No emulator installed for Super Nintendo", missing.detail)
        // Not yet read: the page claims nothing it does not know.
        assertTrue(consolePlayState(kind = PageKind.GAME, emulatorsKnown = false, emulator = null, systemName = null).ready)

        val statuses = pageStatuses(rom, missing, runsWith = null, cloudSaves = null, saves = "None found yet", achievements = "4 of 10", achievementsSignIn = false)
        assertEquals(listOf(PageStatusKind.READY, PageStatusKind.SAVES, PageStatusKind.ACHIEVEMENTS), statuses.map { it.kind })
        assertEquals("Not ready to play: No emulator installed for Super Nintendo", statuses[0].label)
        assertEquals(PageStatusTone.WAITING, statuses[0].tone)
        assertEquals("Ready to play with Snes9x", pageStatuses(rom, ready, "Snes9x", null, null, null, false).single().label)
    }
}
