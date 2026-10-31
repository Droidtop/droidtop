package dev.droidtop.shell.gamepad

import dev.droidtop.library.InstalledAppFacts
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.integrations.AppCatalogs
import dev.droidtop.shell.gamepad.pc.PageKind
import dev.droidtop.shell.gamepad.pc.PageOptionRows
import dev.droidtop.shell.gamepad.pc.PageStatusKind
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
 * An Android app's page in the one frame every page shares (EntryDetailScreen.kt, AppPageFacts.kt; docs/SPEC.md 7i,
 * "Apps on the page"; Droidtop/tracker#845 slice U3): the frame's words name an app, and the app's facts land on
 * their tabs.
 */
class AppPageTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day

    private val app = LibraryEntry(
        id = "org.example.reader",
        title = "Reader",
        kind = LibraryEntryKind.NATIVE_ANDROID_APP,
        appFacts = InstalledAppFacts(firstInstalledEpochMs = now - 30 * day, installer = "org.fdroid.fdroid"),
    )
    private val rom = LibraryEntry(id = "/roms/snes/Game.sfc", title = "Game", kind = LibraryEntryKind.CONSOLE_ROM, systemId = "snes")

    private val offer = AppCatalogs.Offer("org.example.reader", "Reader", "2.1", 210, emptySet(), "F-Droid")
    private val installed = AppCatalogs.PackageFacts("org.example.reader", 200, "2.0", emptySet())

    private fun facts(update: AppCatalogs.UpdateState? = null) = AppPageFacts(
        versionName = "2.0",
        versionCode = 200,
        installedAt = now - 30 * day,
        updatedAt = now - 3 * day,
        permissions = 4,
        lastUsed = now - 2 * day - 1000,
        update = update,
        isGame = false,
    )

    private fun rows(facts: AppPageFacts, status: String? = null) = groupRowsByTab(
        consolePageRows(app, systemName = null, emulator = null, now = now, onAddSwitchContent = {}, usedElsewhere = facts.lastUsed) +
            appPageRows(app, facts, now, status) {},
        emptyList(),
    )

    @Test
    fun theFramesWordsNameAnAppOnAnAppsPage() {
        assertEquals(PageKind.APP, PageKind.of(app))
        assertEquals(PageKind.GAME, PageKind.of(rom))
        PageTab.values().forEach { tab ->
            val line = tab.emptyLine(PageKind.APP)
            assertTrue(line, line.contains("this app"))
            assertFalse(line, line.contains("game"))
            assertTrue(tab.emptyLine(PageKind.GAME).contains("this game"))
        }
        assertEquals("You have not opened this app yet", PageTab.ACTIVITY.emptyLine(PageKind.APP))
        assertEquals("You have not played this game yet", PageTab.ACTIVITY.emptyLine(PageKind.GAME))
        assertEquals("App options", PageKind.APP.optionsHint)
        assertEquals("Game options", PageKind.GAME.optionsHint)
        // Options' shared rows take the same word.
        assertEquals("3 images and videos scraped for this app", PageOptionRows.viewMedia(PageKind.APP, 3) {}.detail)
        assertEquals("Which of your collections this game is in", PageOptionRows.collections(PageKind.GAME) {}.detail)

        val play = consolePlayState(kind = PageKind.APP, emulatorsKnown = false, emulator = null, systemName = null)
        assertEquals("Open", play.verb)
        assertEquals("Play", consolePlayState(kind = PageKind.GAME, emulatorsKnown = false, emulator = null, systemName = null).verb)
        // An app with no icon is "unscraped" by the game rule, but an app's facts come from Android: no such glyph.
        val statuses = pageStatuses(app, play, runsWith = null, cloudSaves = null, saves = null, achievements = null, achievementsSignIn = false)
        assertEquals(listOf(PageStatusKind.READY), statuses.map { it.kind })
        assertEquals("Ready to open", statuses.single().label)
        assertTrue(pageStatuses(rom, play, null, null, null, null, false).any { it.kind == PageStatusKind.NOT_SCRAPED })
    }

    @Test
    fun anAppsFactsLandOnTheirTabs() {
        val byTab = rows(facts())
        assertEquals(PageTab.values().toList(), byTab.keys.toList())
        assertEquals(listOf("Installed version" to "2.0"), byTab.getValue(PageTab.VERSIONS).map { it.title to it.value })
        assertEquals("Version code 200", byTab.getValue(PageTab.VERSIONS).single().subtitle)
        // The later of droidtop's own record and Android's usage record, in an app's words.
        assertEquals(listOf("Last opened" to "2 days ago"), byTab.getValue(PageTab.ACTIVITY).map { it.title to it.value })
        val details = byTab.getValue(PageTab.DETAILS)
        assertEquals(listOf("Source", "Kind", "Package", "Installed", "Last updated", "Permissions"), details.map { it.title })
        val source = details.first { it.title == "Source" }
        assertEquals("F-Droid", source.value)
        assertEquals("org.fdroid.fdroid", source.tip)
        assertEquals("4 asked for", details.first { it.title == "Permissions" }.value)
        // Nothing gives an installed app a description, extras or a thread yet: those tabs say their lines.
        assertTrue(byTab.getValue(PageTab.OVERVIEW).isEmpty())
        assertTrue(byTab.getValue(PageTab.EXTRAS).isEmpty())
        assertTrue(byTab.getValue(PageTab.THREAD).isEmpty())
    }

    @Test
    fun aNewerVersionLeadsVersionsAndUpdatesFromIt() {
        val available = facts(AppCatalogs.UpdateState.Available(offer, installed, "fdroid.plugin", "F-Droid client"))
        val versions = rows(available).getValue(PageTab.VERSIONS)
        assertEquals(listOf(APP_UPDATE_ROW, "Installed version"), versions.map { it.title })
        assertEquals("2.1", versions.first().value)
        assertNotNull("Select starts the update", versions.first().onActivate)
        assertEquals("2.1", available.availableUpdate)
        // The status row has its update glyph from the same offer.
        val play = consolePlayState(kind = PageKind.APP, emulatorsKnown = false, emulator = null, systemName = null)
        val statuses = pageStatuses(app, play, null, null, null, null, false, update = available.availableUpdate)
        assertEquals(listOf(PageStatusKind.READY, PageStatusKind.UPDATE), statuses.map { it.kind })
        // While it runs the row says how far, and a second press does nothing.
        val running = rows(available, status = "Downloading 40%").getValue(PageTab.VERSIONS).first()
        assertEquals("Downloading 40%", running.subtitle)
        assertNull(running.onActivate)
        // A version signed by another key is said, never offered.
        val differs = facts(AppCatalogs.UpdateState.KeyDiffers(offer, installed, "F-Droid client"))
        val row = rows(differs).getValue(PageTab.VERSIONS).first()
        assertEquals(APP_UPDATE_ROW, row.title)
        assertNull(row.onActivate)
        assertNull(differs.availableUpdate)
    }
}
