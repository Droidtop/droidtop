package dev.droidtop.library.scraper

import dev.droidtop.library.consoles.RomScanWalk
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scraper's honesty, tested where it can actually be tested.
 *
 * On 2026-09-01 a full pass over the user's real library returned HTTP
 * 403 for all 46 requests and told them "no match for 46, 0 failed",
 * which reads as "ScreenScraper does not have your games". Every case
 * below exists to keep some part of that sentence from being sayable
 * again. None of it needs a network: the refusal/miss distinction and
 * the summary that reports it are pure.
 */
class ScrapeReportingTest {

    // ---- a refusal is not a miss ----------------------------------------

    @Test
    fun `a total refusal is reported as an outage, not as an empty library`() {
        val summary = formatScrapeSummary(
            systemName = "Nintendo 3DS",
            targeted = 46,
            attempted = 46,
            found = 0,
            hashMatched = 0,
            thumbnailed = 0,
            miximaged = 0,
            failed = 0,
            refused = 46,
            lastRefusal = ScreenScraperLookup.Refused(403, "Application non autorisee"),
        )
        // The exact regression: this sentence may never claim a miss.
        assertFalse(summary, summary.contains("no match"))
        assertTrue(summary, summary.contains("refused every request"))
        assertTrue(summary, summary.contains("HTTP 403"))
        assertTrue(summary, summary.contains("Application non autorisee"))
        assertTrue(summary, summary.contains("says nothing about whether your games are in the database"))
    }

    @Test
    fun `a genuine miss is still reported as a miss`() {
        val summary = formatScrapeSummary(
            systemName = "Nintendo 3DS",
            targeted = 2,
            attempted = 2,
            found = 0,
            hashMatched = 0,
            thumbnailed = 0,
            miximaged = 0,
            failed = 0,
            refused = 0,
            lastRefusal = null,
        )
        assertTrue(summary, summary.contains("no match for 2"))
        assertFalse(summary, summary.contains("refused"))
    }

    @Test
    fun `refusals mixed into a working pass are counted apart from misses`() {
        // Nine asked for: four found, three the database really lacks,
        // two the server refused. The old arithmetic would have called
        // five of those a miss.
        val summary = formatScrapeSummary(
            systemName = "Mega Drive",
            targeted = 9,
            attempted = 9,
            found = 4,
            hashMatched = 4,
            thumbnailed = 0,
            miximaged = 0,
            failed = 0,
            refused = 2,
            lastRefusal = ScreenScraperLookup.Refused(429, "Quota atteint"),
        )
        assertTrue(summary, summary.contains("no match for 3"))
        assertTrue(summary, summary.contains("2 refused by the server"))
        assertTrue(summary, summary.contains("HTTP 429: Quota atteint"))
    }

    @Test
    fun `giving up early says how many were actually asked for`() {
        val summary = formatScrapeSummary(
            systemName = "Mega Drive",
            targeted = 40,
            attempted = 6,
            found = 1,
            hashMatched = 0,
            thumbnailed = 0,
            miximaged = 0,
            failed = 0,
            refused = 5,
            lastRefusal = ScreenScraperLookup.Refused(403, "Application non autorisee"),
        )
        assertTrue(summary, summary.contains("of 40 targeted"))
        assertTrue(summary, summary.contains("6 asked for before giving up"))
        // Five refusals and one hit: exactly zero real misses.
        assertTrue(summary, summary.contains("no match for 0"))
    }

    @Test
    fun `a refusal with no body still tells the reader where to look`() {
        val summary = formatScrapeSummary(
            systemName = "PSP",
            targeted = 3,
            attempted = 3,
            found = 0,
            hashMatched = 0,
            thumbnailed = 0,
            miximaged = 0,
            failed = 0,
            refused = 3,
            lastRefusal = ScreenScraperLookup.Refused(403, null),
        )
        assertTrue(summary, summary.contains("HTTP 403"))
        assertTrue(summary, summary.contains("droidtop.Scraper"))
    }

    // ---- the reason the server gave -------------------------------------

    @Test
    fun `the server's own explanation survives to the log line`() {
        assertEquals(
            "Erreur de login : Verifier vos identifiants developpeur !",
            ScreenScraperClient.summarizeErrorBody(
                "\n  Erreur de login : Verifier vos identifiants developpeur !\n",
                emptyList(),
            ),
        )
    }

    @Test
    fun `an HTML error page from an intermediary is reduced to its text`() {
        val summary = ScreenScraperClient.summarizeErrorBody(
            "<html><head><title>403 Forbidden</title></head><body><h1>403 Forbidden</h1></body></html>",
            emptyList(),
        )
        assertEquals("403 Forbidden 403 Forbidden", summary)
    }

    @Test
    fun `no credential value can ride out in an error body`() {
        // The one thing a refusal body must never be able to do: echo
        // back what was sent to earn it.
        val summary = ScreenScraperClient.summarizeErrorBody(
            "Bad password hunter2hunter2 for user hunter2",
            listOf("hunter2", "hunter2hunter2"),
        )
        assertEquals("Bad password [redacted] for user [redacted]", summary)
        assertFalse(summary!!, summary!!.contains("hunter2"))
    }

    @Test
    fun `blank credentials are not treated as something to redact`() {
        assertEquals(
            "Application non autorisee",
            ScreenScraperClient.summarizeErrorBody("Application non autorisee", listOf("", "  ")),
        )
    }

    @Test
    fun `an empty or markup-only body reads as no explanation at all`() {
        assertNull(ScreenScraperClient.summarizeErrorBody("", emptyList()))
        assertNull(ScreenScraperClient.summarizeErrorBody("   \n\t ", emptyList()))
        assertNull(ScreenScraperClient.summarizeErrorBody("<html><body></body></html>", emptyList()))
    }

    @Test
    fun `an oversized body is bounded rather than logged whole`() {
        val summary = ScreenScraperClient.summarizeErrorBody("x".repeat(5_000), emptyList())!!
        assertTrue(summary.length.toString(), summary.length <= 203)
        assertTrue(summary.endsWith("..."))
    }

    @Test
    fun `the 403 hint names both candidate causes and no others`() {
        val hint = ScreenScraperClient.refusalHint(403)!!
        assertTrue(hint, hint.contains("approved manually"))
        assertTrue(hint, hint.contains("softname=\"droidtop\""))
        // No invented status-code table: only 403 has anything known.
        assertNull(ScreenScraperClient.refusalHint(429))
        assertNull(ScreenScraperClient.refusalHint(500))
    }

    // ---- one DLC folder is not twelve games ------------------------------

    @Test
    fun `add-on directory names are recognised however they are punctuated`() {
        listOf(
            "DLC", "dlc", "DLCs", "Rune Factory 5 (DLC)", "Rune Factory 5 [DLC]",
            "Rune Factory 5 - DLC", "Updates", "update", "Zelda - Updates",
            "_patches", "Add-On", "add_on", "addons", "BIOS", "firmware",
        ).forEach { assertTrue(it, RomScanWalk.isAddOnDirectoryName(it)) }
    }

    @Test
    fun `real game directories and rom hacks are left alone`() {
        // "mods"/"romhacks" are pointedly not markers: a ROM hack is a
        // playable game and hiding it would be worse than the bug.
        listOf(
            "Rune Factory 5", "A - F", "Collections", "Mods", "romhacks",
            "Hacks", "Updated Edition", "Patchwork Heroes", "Extras",
        ).forEach { assertFalse(it, RomScanWalk.isAddOnDirectoryName(it)) }
    }

    @Test
    fun `a DLC folder beside a base game yields one game, not twelve`() {
        val system = Files.createTempDirectory("n3ds").toFile()
        File(system, "Rune Factory 5.cia").writeText("rom")
        val dlc = File(system, "Rune Factory 5 (DLC)").apply { mkdirs() }
        repeat(12) { File(dlc, "dlc_$it.cia").writeText("addon") }

        val result = RomScanWalk.walk(system, setOf("cia"))

        assertEquals(listOf("Rune Factory 5.cia"), result.files.map { it.name })
        assertEquals(listOf(dlc.name), result.skipped.map { it.first.name })
        assertTrue(result.skipped.single().second.contains("add-on"))
    }

    @Test
    fun `an ordinary subfolder is still walked, because organising a library is not hiding it`() {
        val system = Files.createTempDirectory("megadrive").toFile()
        val byLetter = File(system, "S").apply { mkdirs() }
        File(byLetter, "Sonic.md").writeText("rom")
        File(system, "Altered Beast.md").writeText("rom")

        assertEquals(
            setOf("Sonic.md", "Altered Beast.md"),
            RomScanWalk.walk(system, setOf("md")).files.map { it.name }.toSet(),
        )
    }

    @Test
    fun `ES-DE's own noload marker hides a directory the naming rule would keep`() {
        val system = Files.createTempDirectory("psx").toFile()
        val staging = File(system, "Unsorted").apply { mkdirs() }
        File(staging, "noload.txt").writeText("")
        File(staging, "Something.bin").writeText("rom")
        File(system, "Real Game.bin").writeText("rom")

        val result = RomScanWalk.walk(system, setOf("bin"))

        assertEquals(listOf("Real Game.bin"), result.files.map { it.name })
        assertTrue(result.skipped.single().second.contains("noload.txt"))
    }

    @Test
    fun `the system folder itself is never excluded by its own name`() {
        // A system folder literally called "bios" would otherwise vanish
        // entirely. Rule 3 in RomScanWalk: never empty out a system.
        // The directory must be named exactly "bios" for this to mean
        // anything, so it is created by name under a temp parent.
        val system = File(Files.createTempDirectory("roots").toFile(), "bios").apply { mkdirs() }
        File(system, "game.bin").writeText("rom")
        assertEquals(1, RomScanWalk.walk(system, setOf("bin")).files.size)
    }
}
