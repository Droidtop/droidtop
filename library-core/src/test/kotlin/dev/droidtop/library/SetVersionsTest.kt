package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The version a folder game shows (docs/SPEC.md 7i, "The game page",
 * Droidtop/tracker#397 slice G): the person's own wins until cleared, a
 * store marker's build beats the folder's name, and the folder's name is the
 * same parse the title is stripped by.
 */
class SetVersionsTest {

    private fun folder(path: String, marker: StoreMarker? = null) = LibraryEntry(
        id = "folder:CUSTOM_GAME_1",
        title = "x",
        kind = LibraryEntryKind.WINE_PROFILE,
        pcInfo = PcInfo(storeId = "folder:CUSTOM_GAME_1", installed = true, installPath = path, marker = marker),
    )

    @Test
    fun `the folder name speaks until a version is set, and again once it is cleared`() {
        val game = folder("/sd/Games/Game v0.5")
        assertEquals("0.5" to SetVersions.Origin.FOLDER, SetVersions.shown(game, null))
        assertEquals("0.6" to SetVersions.Origin.SET, SetVersions.shown(game, "0.6"))
        assertEquals("0.5" to SetVersions.Origin.FOLDER, SetVersions.shown(game, " "))
    }

    @Test
    fun `a store marker's build beats the folder name but not the person`() {
        val game = folder("/sd/Games/Witcher v1.2", StoreMarker("gog", "1", buildId = "5132"))
        assertEquals("5132" to SetVersions.Origin.STORE, SetVersions.shown(game, null))
        assertEquals("1.5" to SetVersions.Origin.SET, SetVersions.shown(game, "1.5"))
    }

    @Test
    fun `a folder that names no version and has none set shows none`() {
        assertNull(SetVersions.shown(folder("/sd/Games/Far Cry 5"), null))
    }

    @Test
    fun `the game's files speak only when the folder name names no version`() {
        assertEquals("0.9" to SetVersions.Origin.FILES, SetVersions.shown(folder("/sd/Games/Some Game"), null, "0.9"))
        assertEquals("0.5" to SetVersions.Origin.FOLDER, SetVersions.shown(folder("/sd/Games/Game v0.5"), null, "0.9"))
        assertEquals("1.0" to SetVersions.Origin.SET, SetVersions.shown(folder("/sd/Games/Some Game"), "1.0", "0.9"))
    }

    @Test
    fun `the files' version is the one the walk kept on the entry`() {
        val game = folder("/sd/Games/Some Game").copy(filesVersion = "0.9")
        assertEquals("0.9" to SetVersions.Origin.FILES, SetVersions.shown(game, null))
        assertNull(SetVersions.shown(folder("/sd/Games/Some Game"), null))
    }
}
