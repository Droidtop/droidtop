package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Droidtop/tracker#891, from the console: after a run, "Love & Sex: Second Base" (a Steam install of a Ren'Py game)
 * showed on Home as "Love Sex Second Base", its install folder's name, with another genre and art, and a later walk
 * put it back. The engine walk that rebuilt the entry ran before the stores had answered, so it had no store install
 * for the folder. Now it keeps the store facts the folder's own record last carried until the stores answer.
 */
class StoreTitleAfterRestartTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val steamTitle = "Love & Sex: Second Base"

    /** The folder Steam made: its name with the characters a card refuses dropped. */
    private fun install(): File = File(tmp.root, "Steam/Love  Sex Second Base").apply { mkdirs() }

    private fun steamInfo(folder: File) = PcInfo(storeId = "steam:1234", installed = true, installPath = folder.absolutePath)

    /** The entry the engine walk builds before any store facts: titled by its folder, as GameEngineDetector does. */
    private fun bare(folder: File) = LibraryEntry(id = folder.absolutePath, title = folder.name, kind = LibraryEntryKind.RENPY)

    /** What the record held after a walk that did know the store: the store's title, id and art. */
    private fun recorded(folder: File) = bare(folder).withStoreInstall(StoreInstall(folder, steamInfo(folder), "https://cdn/steam.jpg", steamTitle))

    @Test
    fun `a walk before the stores answer keeps the store's title, id and art from the folder's record`() {
        val folder = install()
        val record = recorded(folder)
        val entry = bare(folder).withStoreInstall(emptyMap<String, StoreInstall>().storeInstallFor(folder, installsKnown = false) { id ->
            record.takeIf { id == folder.absolutePath }
        })
        assertEquals(steamTitle, entry.title)
        assertEquals("steam:1234", entry.pcInfo?.storeId)
        assertEquals("https://cdn/steam.jpg", entry.artworkUri)
    }

    @Test
    fun `the stores' own list wins once it is known, and a game no store has any more keeps nothing`() {
        val folder = install()
        val record = recorded(folder)
        val live = StoreInstall(folder, steamInfo(folder), null, "Love and Sex: Second Base")
        assertEquals(live, listOf(live).byInstallDir().storeInstallFor(folder, installsKnown = true) { record })
        assertNull(emptyMap<String, StoreInstall>().storeInstallFor(folder, installsKnown = true) { record })
    }

    @Test
    fun `a folder game no store ever had borrows nothing, and its record is not read once the stores answered`() {
        val folder = install()
        val plain = bare(folder)
        assertNull(emptyMap<String, StoreInstall>().storeInstallFor(folder, installsKnown = false) { plain })
        var asked = 0
        emptyMap<String, StoreInstall>().storeInstallFor(folder, installsKnown = true) { asked++; plain }
        assertEquals(0, asked)
    }
}
