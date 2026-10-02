package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scanned PC folder game (id `folder:CUSTOM_GAME_n`, install folder in
 * [PcInfo]) groups into games the way an engine game's path does
 * (docs/SPEC.md 7n; Droidtop/tracker#264). Names are placeholders.
 */
class FolderGameGroupingTest {

    private val roots = listOf("/games")

    private fun folderGame(n: Int, installPath: String, gameName: String? = null, title: String? = null) = LibraryEntry(
        id = "folder:CUSTOM_GAME_$n",
        title = title ?: installPath.substringAfterLast('/'),
        kind = LibraryEntryKind.WINE_PROFILE,
        systemId = "pc",
        gameName = gameName,
        pcInfo = PcInfo(source = "Folder", installed = true, installPath = installPath),
    )

    private fun group(vararg entries: LibraryEntry, finished: Set<String> = emptySet()) =
        LibraryGrouping.group(entries.toList(), finished, roots)

    // --- one parent, ordered parts ---------------------------------------

    private val books = arrayOf(
        folderGame(1, "/games/Some Game/book1"),
        folderGame(2, "/games/Some Game/book2"),
        folderGame(3, "/games/Some Game/book3"),
    )

    @Test
    fun `ordered part folders under a parent are one entry titled after the parent`() {
        val groups = group(*books)
        assertEquals(listOf("Some Game"), groups.map { it.game.name })
        val game = groups.single()
        assertEquals(listOf("book1", "book2", "book3"), game.game.segments.map { it.label })
        assertEquals(listOf(1, 2, 3), game.game.segments.map { it.order })
        assertEquals(3, game.folders)
        assertTrue(game.hasChoices)
        // The card is the first part's entry, titled with the game.
        assertEquals("folder:CUSTOM_GAME_1", game.displayEntry.id)
        assertEquals("Some Game", game.displayEntry.title)
        // Nothing is titled with the part's name.
        assertTrue(groups.none { it.game.name.startsWith("book") })
    }

    @Test
    fun `parts arriving in any order group the same way`() {
        assertEquals(group(*books), group(*books.reversedArray()))
    }

    @Test
    fun `every part marker shape groups under the parent`() {
        val leaves = listOf("episode1", "Episode 2", "Chapter 3", "Part 4", "Volume 5", "Vol 6", "Season 7", "Act II", "Book Three")
        val entries = leaves.mapIndexed { i, leaf -> folderGame(i, "/games/Some Game/$leaf") }.toTypedArray()
        val groups = group(*entries)
        assertEquals(listOf("Some Game"), groups.map { it.game.name })
        assertEquals(leaves.size, groups.single().game.segments.size)
    }

    @Test
    fun `a single part folder is still titled after its parent`() {
        val groups = group(folderGame(1, "/games/Some Game/book1"))
        assertEquals("Some Game", groups.single().game.name)
        assertFalse(groups.single().hasChoices)
    }

    @Test
    fun `Play continues with the first part that is not finished`() {
        assertEquals("folder:CUSTOM_GAME_1", group(*books).single().continueEntry?.id)
        assertEquals("folder:CUSTOM_GAME_2", group(*books, finished = setOf("folder:CUSTOM_GAME_1")).single().continueEntry?.id)
        assertEquals(
            "folder:CUSTOM_GAME_3",
            group(*books, finished = setOf("folder:CUSTOM_GAME_1", "folder:CUSTOM_GAME_2")).single().continueEntry?.id,
        )
        // Finishing out of order still continues with the first one that is not.
        assertEquals("folder:CUSTOM_GAME_1", group(*books, finished = setOf("folder:CUSTOM_GAME_2")).single().continueEntry?.id)
        // Everything finished: start again rather than have nothing to start.
        val all = books.map { it.id }.toSet()
        assertEquals("folder:CUSTOM_GAME_1", group(*books, finished = all).single().continueEntry?.id)
        // The card does not move while parts are finished.
        assertEquals("folder:CUSTOM_GAME_1", group(*books, finished = all - books[0].id).single().displayEntry.id)
    }

    @Test
    fun `a game with no parts continues with itself`() {
        val single = group(folderGame(1, "/games/Plain Game")).single()
        assertEquals("folder:CUSTOM_GAME_1", single.continueEntry?.id)
        assertEquals(single.displayEntry.id, single.continueEntry?.id)
    }

    // --- sequels, versions ------------------------------------------------

    @Test
    fun `separate sequels stay separate games and share a series`() {
        val groups = group(folderGame(1, "/games/Series Name"), folderGame(2, "/games/Series Name 2"), folderGame(3, "/games/Series Name 3"))
        assertEquals(listOf("Series Name", "Series Name 2", "Series Name 3"), groups.map { it.game.name })
        assertEquals(
            setOf("Series Name"),
            groups.drop(1).mapNotNull { GameTitleParser.parseName(it.game.name).seriesTitle }.toSet(),
        )
    }

    @Test
    fun `two versions of one game are one game with two versions, newest first`() {
        val groups = group(folderGame(1, "/games/Sample-0.9-pc"), folderGame(2, "/games/Sample-1.0-pc"))
        val game = groups.single()
        assertEquals("Sample", game.game.name)
        assertEquals(listOf("1.0", "0.9"), game.game.versions.map { it.version })
    }

    // --- engine folders and the games root --------------------------------

    @Test
    fun `an engine folder inside a game is that game, and a bare one is unidentified`() {
        val groups = group(
            folderGame(1, "/games/Some Game/game"),
            folderGame(2, "/games/game"),
            folderGame(3, "/games/data"),
        )
        assertEquals(listOf("Some Game", GameNaming.UNIDENTIFIED, GameNaming.UNIDENTIFIED), groups.map { it.game.name }.sorted())
        // Two unknown folders are two entries, never one game with two copies.
        val unknown = groups.filter { it.game.name == GameNaming.UNIDENTIFIED }
        assertEquals(2, unknown.size)
        assertTrue(unknown.all { it.folders == 1 })
    }

    @Test
    fun `the games root is never a game's title`() {
        val withRoot = LibraryGrouping.group(listOf(folderGame(1, "/games/game")), emptySet(), roots)
        assertEquals(GameNaming.UNIDENTIFIED, withRoot.single().game.name)
        val without = LibraryGrouping.group(listOf(folderGame(1, "/games/game")))
        assertEquals("games", without.single().game.name)
    }

    // --- the person's own say ---------------------------------------------

    @Test
    fun `an own title wins and survives, and a part can be taken out of its game`() {
        val renamed = group(books[0], books[1].copy(gameName = "Some Game book2"), books[2])
        assertEquals(listOf("Some Game", "Some Game book2"), renamed.map { it.game.name })
        assertEquals(2, renamed.first().folders)

        val all = group(*books.map { it.copy(gameName = "My Own Title") }.toTypedArray())
        assertEquals(listOf("My Own Title"), all.map { it.game.name })
        assertEquals(3, all.single().game.segments.size)
    }

    @Test
    fun `two folders the person called one game are one game`() {
        val merged = group(
            folderGame(1, "/games/Alpha", gameName = "Same Game"),
            folderGame(2, "/games/Beta", gameName = "Same Game"),
        )
        assertEquals(listOf("Same Game"), merged.map { it.game.name })
        assertEquals(2, merged.single().folders)
    }

    @Test
    fun `a folder game whose folder is unknown is its own game under its own title`() {
        val gone = LibraryEntry(id = "folder:CUSTOM_GAME_9", title = "Raw Title", kind = LibraryEntryKind.WINE_PROFILE)
        assertEquals("Raw Title", LibraryGrouping.group(listOf(gone)).single().game.name)
        assertEquals("Mine", LibraryGrouping.group(listOf(gone.copy(gameName = "Mine"))).single().game.name)
        assertNull(gone.groupingPath())
    }

    @Test
    fun `an engine game's own path still groups as it did`() {
        val engine = listOf("book1", "book2").map { part ->
            LibraryEntry(id = "/games/renpy/Some Game/$part", title = part, kind = LibraryEntryKind.RENPY)
        }
        val groups = LibraryGrouping.group(engine)
        assertEquals(listOf("Some Game"), groups.map { it.game.name })
        assertEquals(2, groups.single().folders)
    }

    // --- not scraped --------------------------------------------------------

    @Test
    fun `a game nothing has matched is unscraped, and one with art or text or a source is not`() {
        val plain = folderGame(1, "/games/Plain Game")
        assertTrue(plain.isUnscraped())
        assertFalse(plain.copy(artworkUri = "/x.png").isUnscraped())
        assertFalse(plain.copy(description = "About it").isUnscraped())
        assertFalse(plain.copy(fieldSources = mapOf("description" to "IGDB")).isUnscraped())
        assertFalse(plain.copy(missing = true).isUnscraped())
    }
}
