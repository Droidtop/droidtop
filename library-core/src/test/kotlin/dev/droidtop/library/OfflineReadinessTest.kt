package dev.droidtop.library

import dev.droidtop.library.OfflineReadiness.Need
import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineReadinessTest {
    private fun game(kind: LibraryEntryKind = LibraryEntryKind.RENPY, pcInfo: PcInfo? = null, missing: Boolean = false) =
        LibraryEntry(id = "/games/a", title = "A", kind = kind, pcInfo = pcInfo, missing = missing)

    @Test
    fun `what keeps a game from playing offline comes from the entry`() {
        assertEquals(Need.NONE, OfflineReadiness.need(game()))
        assertEquals(Need.NONE, OfflineReadiness.need(game(pcInfo = PcInfo(installed = true))))
        assertEquals(Need.NOT_DOWNLOADED, OfflineReadiness.need(game(pcInfo = PcInfo(storeId = "gog:1", installed = false))))
        assertEquals(Need.FILES_MISSING, OfflineReadiness.need(game(missing = true)))
        assertEquals(Need.STREAMED, OfflineReadiness.need(game(kind = LibraryEntryKind.REMOTE_STREAM)))
        assertEquals(Need.OTHER_COMPUTER, OfflineReadiness.need(game(kind = LibraryEntryKind.COMPUTER_GAME, pcInfo = PcInfo(installed = true))))
    }

    @Test
    fun `only art from the internet counts, once each`() {
        val entry = game().copy(
            artworkUri = "https://cdn.example/box.jpg",
            heroUri = "/storage/emulated/0/ES-DE/media/fanart/a.png",
            logoUri = "https://cdn.example/box.jpg",
        )
        assertEquals(listOf("https://cdn.example/box.jpg"), OfflineReadiness.remoteArt(entry))
    }

    @Test
    fun `the row says the answer and what to do`() {
        assertEquals("Ready" to null, OfflineReadiness.line(Need.NONE, artNotSaved = 0))
        assertEquals("Plays offline", OfflineReadiness.line(Need.NONE, artNotSaved = 2).first)
        assertEquals("Download it before going offline", OfflineReadiness.line(Need.NOT_DOWNLOADED, 0).second)
    }
}
