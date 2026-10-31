package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.GameLinks
import dev.droidtop.library.SourceAnswer
import dev.droidtop.library.SourceLink
import dev.droidtop.library.UpdateSources
import dev.droidtop.library.RecordAction
import dev.droidtop.library.RecordRow
import dev.droidtop.library.checkRow
import dev.droidtop.library.downloadSystems
import dev.droidtop.library.groupedDownloads
import dev.droidtop.library.partsLine
import dev.droidtop.library.pressedState
import dev.droidtop.library.ratingLine
import dev.droidtop.library.signInRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record page's tabs (docs/SPEC.md 7g, "Getting an update"). */
class SourceRecordPageTest {
    private fun d(section: String, host: String, label: String = host) = UpdateSources.Download(section, host, label, "$section/$host/$label")

    private val record = UpdateSources.Record(
        externalId = "18978",
        title = "Sample Game",
        url = "https://f95zone.to/threads/18978",
        version = "0.6",
        developer = "Sample Dev",
        status = "Ongoing",
        rating = 0.86,
        votes = 1234,
        tags = listOf("2dcg", "sandbox"),
        overview = "A story.",
        downloads = listOf(d("Win/Linux", "MEGA"), d("Win/Linux", "PIXELDRAIN", "Part 1"), d("Mac", "MEGA"), d("Win/Linux", "mega", "Mirror")),
    )

    @Test
    fun downloadsKeepTheSourcesSectionsAndGroupByHostWithinOne() {
        val order = groupedDownloads(record.downloads).map { "${it.section}|${it.host}|${it.label}" }
        assertEquals(listOf("Win/Linux|MEGA|MEGA", "Win/Linux|mega|Mirror", "Win/Linux|PIXELDRAIN|Part 1", "Mac|MEGA|MEGA"), order)
        val all = recordRows(record, RecordTab.DOWNLOADS) { "" }
        // droidtop's own pick leads (Droidtop/tracker#838); the links follow for picking by hand.
        assertEquals(RecordAction.GetBest, all.first().action)
        // One row per heading (Droidtop/tracker#838): Win/Linux's three links tried in turn, Mac's one link as itself.
        val rows = all.drop(1)
        assertEquals(listOf("Win/Linux", "MEGA"), rows.map { it.title })
        assertEquals("MEGA, PIXELDRAIN", rows.first().subtitle)
        assertEquals(3, (rows.first().action as RecordAction.Group).downloads.size)
        assertTrue(rows[1].action is RecordAction.Download)
    }

    @Test
    fun aSystemIsPickedAndOnlyItsLinksShowAndAndroidIsFlagged() {
        fun s(section: String, host: String, vararg systems: String) = UpdateSources.Download(section, host, host, "$section/$host", systems = systems.toList())
        val tagged = record.copy(
            downloads = listOf(
                s("Win/Linux", "MEGA", "windows", "linux"), s("Mac", "PIXELDRAIN", "mac"), s("Android", "GOFILE", "android"),
                s("Walkthrough", "WALKTHROUGH"), s("Compressed", "MIXDROP"),
            ),
        )
        // The thread's own groupings (Droidtop/tracker#845): Win/Linux is one choice, not two.
        assertEquals(listOf("windows+linux", "mac", "android"), downloadSystems(tagged))
        // Windows first; the links for it and the ones for any system.
        val first = recordRows(tagged, RecordTab.DOWNLOADS) { "" }.also { assertEquals(RecordAction.GetBest, it.first().action) }.drop(1)
        assertEquals(RecordRow("System", "Windows/Linux", "Left and Right show another system's links", RecordAction.PickSystem), first.first())
        // The links for any system follow under their own headings, each with its description.
        assertEquals(listOf("MEGA", "Extras", "WALKTHROUGH", "Other", "MIXDROP"), first.drop(1).map { it.title })
        assertEquals("Walkthrough", first.first { it.title == "WALKTHROUGH" }.subtitle)
        assertNull(first.first { it.title == "Extras" }.action)
        // Android: droidtop's word first, then only Android's link.
        val android = recordRows(tagged, RecordTab.DOWNLOADS, "android") { "" }.drop(1)
        assertEquals("This will not be trackable by us. Windows or Linux is recommended.", android[1].title)
        assertNull(android[1].action)
        assertEquals(listOf("GOFILE", "Extras", "WALKTHROUGH", "Other", "MIXDROP"), android.drop(2).map { it.title })
        assertEquals("windows+linux", nextSystem(downloadSystems(tagged), "android", 1))
        // A pick kept from before the groupings ("windows") still lands on the choice that holds it.
        assertEquals("mac", nextSystem(downloadSystems(tagged), "windows", 1))
        assertEquals("android", nextSystem(downloadSystems(tagged), null, -1))
        // Links that are all for one system: no picker, but Android still says so.
        val onlyAndroid = record.copy(downloads = listOf(s("Android", "GOFILE", "android")))
        val rows = recordRows(onlyAndroid, RecordTab.DOWNLOADS) { "" }.drop(1)
        assertEquals(listOf("This will not be trackable by us. Windows or Linux is recommended.", "GOFILE"), rows.map { it.title })
    }

    @Test
    fun infoSaysWhatTheSourceKnowsAndOffersTheLinkAndItsChange() {
        val rows = recordRows(record, RecordTab.INFO) { "17 Oct 2026" }
        assertEquals("4.3 of 5 (1,234 votes)", rows.first { it.title == "Rating" }.value)
        assertEquals("2dcg, sandbox", rows.first { it.title == "Tags" }.subtitle)
        assertEquals(RecordAction.Open("https://f95zone.to/threads/18978"), rows.first { it.title == OPEN_SOURCE_ROW }.action)
        assertEquals(RecordAction.Relink, rows.last().action)
        assertNull(ratingLine(null, 5))
    }

    @Test
    fun emptyTabsSaySoAndTheUpdateFindsTheSourceThatNamedIt() {
        val bare = UpdateSources.Record("1", "Bare")
        assertEquals("The source gives no changelog", recordRows(bare, RecordTab.CHANGELOG) { "" }.single().subtitle)
        assertEquals("No downloads listed", recordRows(bare, RecordTab.DOWNLOADS) { "" }.single().title)
        val forum = UpdateSources.Source("forum", "Forum thread", null)
        val feed = UpdateSources.Source("feed", "Release feed", null)
        val links = GameLinks(
            sources = listOf(
                SourceLink("forum", "7", SourceAnswer("0.5", 0L)),
                SourceLink("feed", "9", SourceAnswer("v0.6", 0L)),
            ),
        )
        assertEquals(feed to "9", updateSourceOf(listOf(forum, feed), links, "0.6"))
        assertNull(updateSourceOf(listOf(forum, feed), links, "0.7"))
        assertNull(updateSourceOf(listOf(forum, feed), links, null))
        // The rig's row (Droidtop/tracker#577): the badge said "v0.37.dv" and the source wrote it otherwise.
        val f95 = GameLinks(sources = listOf(SourceLink("forum", "18978", SourceAnswer("0.37.DV", 0L))))
        assertEquals(forum to "18978", updateSourceOf(listOf(forum, feed), f95, "v0.37.dv"))
        // The only linked source named the update, whatever it wrote.
        val one = GameLinks(sources = listOf(SourceLink("forum", "18978", SourceAnswer("Episode 3", 0L))))
        assertEquals(forum to "18978", updateSourceOf(listOf(forum, feed), one, "v0.37.dv"))
    }

    @Test
    fun `signed out with links that have no address, Downloads starts with the source's sign-in`() {
        // Droidtop/tracker#599: F95zone gives download links to members only; "no link now" was all a guest got.
        val row = signInRow("F95zone", record, signedOut = true)
        assertEquals(RecordRow("Sign in to F95zone", subtitle = "to get download links", action = RecordAction.SignIn), row)
        assertNull("signed in, no row", signInRow("F95zone", record, signedOut = false))
        val addressed = record.copy(downloads = record.downloads.map { it.copy(url = "https://pixeldrain.com/u/a") })
        assertNull("every link has its address: nothing to sign in for", signInRow("F95zone", addressed, signedOut = true))
    }

    @Test
    fun `a site waiting for its browser check says so first, with the check as its action`() {
        // Droidtop/tracker#600: F95zone's Cloudflare check, passed in droidtop's web view.
        assertEquals(RecordRow("Open F95zone to pass its check", subtitle = "The site checks the browser first", action = RecordAction.PassCheck), checkRow("F95zone"))
    }

    @Test
    fun theSourcesControlsAreOverviewRowsAboveTheDescription() {
        val watch = UpdateSources.Control("watch", UpdateSources.Control.Kind.TOGGLE, "Watch this thread", on = true)
        val thread = UpdateSources.Control("thread", UpdateSources.Control.Kind.LINK, "Thread", "f95zone.to", url = "https://f95zone.to/threads/18978")
        val moved = UpdateSources.Control("moved", UpdateSources.Control.Kind.NOTICE, "The thread was moved")
        val rows = recordRows(record.copy(controls = listOf(watch, thread, moved)), RecordTab.OVERVIEW) { "" }
        assertEquals(listOf("Version", "Status", "Watch this thread", "Thread", "The thread was moved", "About"), rows.map { it.title })
        assertEquals(true, rows[2].on)
        assertNull(rows[2].value)
        assertEquals(RecordAction.Control(watch), rows[2].action)
        assertEquals(RecordAction.Open("https://f95zone.to/threads/18978"), rows[3].action)
        // A notice with nothing to run only says.
        assertNull(rows[4].action)
        assertEquals(false, pressedState(watch))
        assertNull(pressedState(thread))
    }

    @Test
    fun aReleaseInPartsIsOneRowThatCountsItsParts() {
        val gb = 1_000_000_000L // ValueFormat counts decimal gigabytes
        val parts = (1..4).map { UpdateSources.Download.Part("Part $it", "https://mega.nz/$it", null, 3 * gb) }
        val split = UpdateSources.Download("Win", "MEGA", "MEGA", "", parts = parts)
        assertEquals("4 parts, 12.0 GB", partsLine(split))
        // Without every part's size, only the count: a total would be wrong.
        assertEquals("2 parts", partsLine(split.copy(parts = listOf(parts[0], parts[1].copy(size = null)))))
        assertNull(partsLine(d("Win", "MEGA")))
        val rows = recordRows(record.copy(downloads = listOf(split)), RecordTab.DOWNLOADS) { "" }.drop(1)
        assertEquals(1, rows.size)
        assertEquals("4 parts, 12.0 GB", rows.single().value)
        assertEquals(RecordAction.Download(split), rows.single().action)
    }

}
