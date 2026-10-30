package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A local copy and a store's row of the same game are one card (docs/SPEC.md 7m, "A store's copy and a local
 * copy", Droidtop/tracker#745): Bare Witness owned on Steam and kept as a folder.
 */
class LinkedCopiesTest {

    private fun folder(n: Int, path: String, sameAs: String? = null, storeIds: List<String> = emptyList()) = LibraryEntry(
        id = "folder:CUSTOM_GAME_$n",
        title = path.substringAfterLast('/'),
        kind = LibraryEntryKind.WINE_PROFILE,
        pcInfo = PcInfo(storeId = "folder:CUSTOM_GAME_$n", installed = true, installPath = path),
        sameAs = sameAs,
        storeIds = storeIds,
    )

    private fun store(id: String, title: String) = LibraryEntry(
        id = id,
        title = title,
        kind = LibraryEntryKind.WINE_PROFILE,
        pcInfo = PcInfo(storeId = id, installed = false),
    )

    private fun groups(vararg entries: LibraryEntry) = LibraryGrouping.group(entries.toList(), roots = listOf("/sd/Games"))

    @Test
    fun `a scraped Steam id and an equal name make the folder the Steam row's local copy`() {
        val local = folder(1, "/sd/Games/Bare Witness v0.5", storeIds = listOf("steam:2111111"))
        val steam = store("steam:2111111", "Bare Witness")
        val card = groups(local, steam).single()
        assertEquals("Bare Witness", card.game.name)
        assertEquals(setOf(local.id, steam.id), card.entriesByPath.keys)
        // The folder's own version stays a version of the card; the Steam copy is listed after it.
        assertEquals(listOf("0.5", ""), card.game.allVersions.map { it.version })
        assertTrue(SimilarGames.linkedToStore(card))
    }

    @Test
    fun `a scraped Steam id alone does not link a folder whose name differs`() {
        val local = folder(2, "/sd/Games/Some Other Game", storeIds = listOf("steam:2111111"))
        assertEquals(2, groups(local, store("steam:2111111", "Bare Witness")).size)
    }

    @Test
    fun `the person's link joins whatever the names say`() {
        val local = folder(3, "/sd/Games/BW_build", sameAs = "steam:2111111")
        val card = groups(local, store("steam:2111111", "Bare Witness")).single()
        assertEquals("Bare Witness", card.game.name)
    }

    @Test
    fun `not the same game stops a scraped id from linking it again`() {
        val local = folder(4, "/sd/Games/Bare Witness", sameAs = GameLinks.NOT_THE_SAME, storeIds = listOf("steam:2111111"))
        val result = groups(local, store("steam:2111111", "Bare Witness"))
        assertEquals(2, result.size)
        assertFalse(result.any { SimilarGames.linkedToStore(it) })
    }

    @Test
    fun `a store marker in the folder still joins even after not the same game`() {
        val local = folder(5, "/sd/Games/witcher", sameAs = GameLinks.NOT_THE_SAME)
            .let { it.copy(pcInfo = it.pcInfo!!.copy(marker = StoreMarker("gog", "9"))) }
        val card = groups(local, store("gog:9", "The Witcher")).single()
        assertEquals(setOf(local.id, "gog:9"), card.entriesByPath.keys)
        // A marker's join is the store's own, not a link the person can undo.
        assertFalse(SimilarGames.linkedToStore(card))
    }

    @Test
    fun `The same game as offers a store row of an equal name first, saying the store`() {
        val all = groups(
            folder(6, "/sd/Games/Bare Witness"),
            store("steam:2111111", "Bare Witness!"),
            folder(7, "/sd/Games/Bare Witnesses"),
            store("gog:5", "Unrelated Game"),
        )
        val target = all.first { "folder:CUSTOM_GAME_6" in it.entriesByPath }
        val candidates = SimilarGames.candidates(target, all)
        assertEquals("steam:2111111", candidates.first().store)
        assertEquals(1.0, candidates.first().score, 0.0)
        assertNull(candidates.first { "folder:CUSTOM_GAME_7" in it.group.entriesByPath }.store)
        assertFalse(candidates.any { "gog:5" in it.group.entriesByPath })
    }

    @Test
    fun `a linked card offers no same-game candidates, only the unlink`() {
        val all = groups(folder(8, "/sd/Games/Bare Witness", sameAs = "steam:1"), store("steam:1", "Bare Witness"))
        assertTrue(SimilarGames.candidates(all.single(), all).isEmpty())
        assertTrue(SimilarGames.linkedToStore(all.single()))
    }
}
