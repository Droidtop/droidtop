package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.GameLinks
import dev.droidtop.library.SourceAnswer
import dev.droidtop.library.SourceLink
import dev.droidtop.library.UpdateSources
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
        val rows = recordRows(record, RecordTab.DOWNLOADS) { "" }
        assertEquals("MEGA", rows.first().title)
        assertEquals("PIXELDRAIN · Part 1", rows[2].title)
        assertTrue(rows.all { it.action is RecordAction.Download })
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
    }
}
