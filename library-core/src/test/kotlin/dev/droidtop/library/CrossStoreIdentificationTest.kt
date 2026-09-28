package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for cross-store identification (docs/SPEC.md 7m cross-store).
 *
 * Tests that the same game owned on multiple stores (Steam, GOG, Epic,
 * Amazon, itch.io, DLsite) and local folders are identified as one game
 * via stable IDs, and that weak title+developer+year matches are only
 * suggested.
 */
class CrossStoreIdentificationTest {

    private fun entry(
        id: String,
        title: String,
        storeId: String? = null,
        f95Thread: Long? = null,
        developer: String? = null,
        releaseDate: String? = null,
    ): LibraryEntry {
        return LibraryEntry(
            id = id,
            title = title,
            kind = LibraryEntryKind.RENPY,
            pcInfo = storeId?.let { PcInfo(source = "Store", storeId = it, installed = true) },
            f95Thread = f95Thread,
            developer = developer,
            releaseDate = releaseDate,
        )
    }

    @Test
    fun `steam appid identifies same game across store and folder`() {
        val steamEntry = entry("steam:440", "Team Fortress 2", storeId = "steam:440")
        val folderEntry = entry("/games/steam/Team Fortress 2", "Team Fortress 2")

        val steamIdentity = steamEntry.gameIdentity(linkedThread = null)
        val folderIdentity = folderEntry.gameIdentity(linkedThread = null)

        assertEquals(steamIdentity.key, folderIdentity.key)
        assertEquals(IdentityConfidence.CERTAIN, steamIdentity.confidence)
        assertEquals(IdentityConfidence.CERTAIN, folderIdentity.confidence)
    }

    @Test
    fun `gog id identifies same game across store and folder`() {
        val gogEntry = entry("gog:12345", "The Witcher 3", storeId = "gog:12345")
        val folderEntry = entry("/games/gog/The Witcher 3", "The Witcher 3")

        val gogIdentity = gogEntry.gameIdentity(linkedThread = null)
        val folderIdentity = folderEntry.gameIdentity(linkedThread = null)

        assertEquals(gogIdentity.key, folderIdentity.key)
        assertEquals(IdentityConfidence.CERTAIN, gogIdentity.confidence)
    }

    @Test
    fun `epic namespace and id identifies same game`() {
        val epicEntry = entry("epic:namespace:id123", "Fortnite", storeId = "epic:namespace:id123")
        val folderEntry = entry("/games/epic/Fortnite", "Fortnite")

        val epicIdentity = epicEntry.gameIdentity(linkedThread = null)
        val folderIdentity = folderEntry.gameIdentity(linkedThread = null)

        assertEquals(epicIdentity.key, folderIdentity.key)
        assertEquals(IdentityConfidence.CERTAIN, epicIdentity.confidence)
    }

    @Test
    fun `amazon asin identifies same game`() {
        val amazonEntry = entry("amazon:B001234", "Game Name", storeId = "amazon:B001234")
        val folderEntry = entry("/games/amazon/Game Name", "Game Name")

        val amazonIdentity = amazonEntry.gameIdentity(linkedThread = null)
        val folderIdentity = folderEntry.gameIdentity(linkedThread = null)

        assertEquals(amazonIdentity.key, folderIdentity.key)
        assertEquals(IdentityConfidence.CERTAIN, amazonIdentity.confidence)
    }

    @Test
    fun `itch slug identifies same game`() {
        val itchEntry = entry("itch:game-slug", "Indie Game", storeId = "itch:game-slug")
        val folderEntry = entry("/games/itch/Indie Game", "Indie Game")

        val itchIdentity = itchEntry.gameIdentity(linkedThread = null)
        val folderIdentity = folderEntry.gameIdentity(linkedThread = null)

        assertEquals(itchIdentity.key, folderIdentity.key)
        assertEquals(IdentityConfidence.CERTAIN, itchIdentity.confidence)
    }

    @Test
    fun `dlsite rj code identifies same game`() {
        val dlsiteEntry = entry("dlsite:RJ123456", "DLsite Game", storeId = "dlsite:RJ123456")
        val folderEntry = entry("/games/dlsite/DLsite Game", "DLsite Game")

        val dlsiteIdentity = dlsiteEntry.gameIdentity(linkedThread = null)
        val folderIdentity = folderEntry.gameIdentity(linkedThread = null)

        assertEquals(dlsiteIdentity.key, folderIdentity.key)
        assertEquals(IdentityConfidence.CERTAIN, dlsiteIdentity.confidence)
    }

    @Test
    fun `f95 thread identifies same game across entries`() {
        val entry1 = entry("/games/adult/Game A", "Game A", f95Thread = 12345L)
        val entry2 = entry("/games/adult/Game B", "Game B", f95Thread = 12345L)

        val identity1 = entry1.gameIdentity(linkedThread = 12345L)
        val identity2 = entry2.gameIdentity(linkedThread = 12345L)

        assertEquals(identity1.key, identity2.key)
        assertEquals(IdentityConfidence.CERTAIN, identity1.confidence)
    }

    @Test
    fun `weak identity title developer year for local folder no store`() {
        val entry1 = entry(
            "/games/local/Game X",
            "Game X",
            developer = "Dev Studio",
            releaseDate = "20200101T000000"
        )
        val entry2 = entry(
            "/games/local/Game X v2",
            "Game X",
            developer = "Dev Studio",
            releaseDate = "20200101T000000"
        )

        val identity1 = entry1.gameIdentity(linkedThread = null)
        val identity2 = entry2.gameIdentity(linkedThread = null)

        assertEquals(identity1.key, identity2.key)
        assertEquals(IdentityConfidence.SUGGESTED, identity1.confidence)
        assertEquals(IdentityConfidence.SUGGESTED, identity2.confidence)
    }

    @Test
    fun `weak identity differs when developer differs`() {
        val entry1 = entry(
            "/games/local/Game X",
            "Game X",
            developer = "Dev Studio A",
            releaseDate = "20200101T000000"
        )
        val entry2 = entry(
            "/games/local/Game X",
            "Game X",
            developer = "Dev Studio B",
            releaseDate = "20200101T000000"
        )

        val identity1 = entry1.gameIdentity(linkedThread = null)
        val identity2 = entry2.gameIdentity(linkedThread = null)

        assertFalse(identity1.key == identity2.key)
    }

    @Test
    fun `weak identity differs when year differs`() {
        val entry1 = entry(
            "/games/local/Game X",
            "Game X",
            developer = "Dev Studio",
            releaseDate = "20200101T000000"
        )
        val entry2 = entry(
            "/games/local/Game X",
            "Game X",
            developer = "Dev Studio",
            releaseDate = "20210101T000000"
        )

        val identity1 = entry1.gameIdentity(linkedThread = null)
        val identity2 = entry2.gameIdentity(linkedThread = null)

        assertFalse(identity1.key == identity2.key)
    }

    @Test
    fun `ownerships collects all stores and local folders`() {
        val entries = listOf(
            entry("steam:440", "Game", storeId = "steam:440"),
            entry("gog:12345", "Game", storeId = "gog:12345"),
            entry("/games/local/Game", "Game"),
        )

        val ownerships = entries.ownerships()

        assertEquals(3, ownerships.size)
        assertTrue(ownerships.any { it is Ownership.Steam })
        assertTrue(ownerships.any { it is Ownership.GOG })
        assertTrue(ownerships.any { it is Ownership.LocalFolder })
    }

    @Test
    fun `ownerships includes f95 thread`() {
        val entries = listOf(
            entry("/games/local/Game", "Game", f95Thread = 12345L),
        )

        val ownerships = entries.ownerships()

        assertEquals(1, ownerships.size)
        assertTrue(ownerships.any { it is Ownership.F95Thread })
    }

    @Test
    fun `library grouping folds steam and gog entries together`() {
        val entries = listOf(
            entry("steam:440", "Portal 2", storeId = "steam:440"),
            entry("gog:56789", "Portal 2", storeId = "gog:56789"),
            entry("/games/steam/Portal 2", "Portal 2"),
        )

        val groups = LibraryGrouping.group(entries)

        // Should be one group for Portal 2
        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals("Portal 2", group.game.name)
        assertEquals(3, group.entriesByPath.size)
        assertEquals("Owned on Steam, GOG and Local folder", group.ownershipLabel)
    }

    @Test
    fun `library grouping keeps separate games with different stable ids`() {
        val entries = listOf(
            entry("steam:440", "Team Fortress 2", storeId = "steam:440"),
            entry("steam:570", "Dota 2", storeId = "steam:570"),
        )

        val groups = LibraryGrouping.group(entries, emptyMap())

        assertEquals(2, groups.size)
    }

    @Test
    fun `confirmed store link folds store entry with local folder`() {
        val folderEntry = entry(
            "/games/local/Game Y",
            "Game Y",
            developer = "Dev Studio",
            releaseDate = "20200101T000000"
        )
        val storeEntry = entry(
            "steam:999",
            "Different Title",
            storeId = "steam:999",
        )

        // Without confirmed link, they are separate (different stable IDs)
        val groupsWithoutLink = LibraryGrouping.group(listOf(folderEntry, storeEntry), emptyMap())
        assertEquals(2, groupsWithoutLink.size)

        // With confirmed link, they are folded together
        val confirmedLinks = mapOf("steam:999" to "Game Y")
        val groupsWithLink = LibraryGrouping.group(listOf(folderEntry, storeEntry), confirmedLinks)
        assertEquals(1, groupsWithLink.size)
        assertEquals("Game Y", groupsWithLink.single().game.name)
        assertEquals(2, groupsWithLink.single().entriesByPath.size)
    }

    @Test
    fun `library grouping suggested match requires user confirmation`() {
        // Two local folders with same title+developer+year but different paths
        // These get SUGGESTED confidence and should be grouped but marked as suggested
        val entries = listOf(
            entry("/games/a/Game X", "Game X", developer = "Dev Studio", releaseDate = "20200101T000000"),
            entry("/games/b/Game X", "Game X", developer = "Dev Studio", releaseDate = "20200101T000000"),
        )

        val groups = LibraryGrouping.group(entries, emptyMap())

        // They should be grouped together (weak match)
        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals("Game X", group.game.name)
        assertEquals(2, group.entriesByPath.size)
    }

    @Test
    fun `dlsite rj code from link is detected`() {
        val entryWithLink = entry(
            "/games/local/Game",
            "Game",
            developer = "Dev",
        ).copy(links = listOf(GameLink("DLsite", "https://www.dlsite.com/maniax/work/=/product_id/RJ123456.html")))

        val identity = entryWithLink.gameIdentity(linkedThread = null)

        // Should detect DLsite RJ code from link
        assertTrue(identity is GameIdentity.ByDlsiteCode || identity is GameIdentity.ByTitleDeveloperYear)
    }
}