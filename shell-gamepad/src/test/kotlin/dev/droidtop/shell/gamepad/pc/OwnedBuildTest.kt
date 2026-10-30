package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.LibraryEntryKind
import dev.droidtop.library.PcInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source's newest release that is a store's own build, on a card that holds that store's copy (docs/SPEC.md 7m,
 * "A store's copy and a local copy", Droidtop/tracker#745): F95zone's Bare Witness "Steam Build".
 */
class OwnedBuildTest {
    private val steam = LibraryEntry(id = "steam:1", title = "Bare Witness", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(storeId = "steam:1", installed = false))
    private val gog = LibraryEntry(id = "gog:7", title = "Bare Witness", kind = LibraryEntryKind.WINE_PROFILE, pcInfo = PcInfo(storeId = "gog:7", installed = false))

    @Test
    fun `a release named for the store is that store's build`() {
        assertTrue(namesStore("Steam Build", steam))
        assertTrue(namesStore("v0.6 (steam)", steam))
        assertTrue(namesStore("GOG release", gog))
    }

    @Test
    fun `a version that only contains the letters is not`() {
        assertFalse(namesStore("0.6", steam))
        assertFalse(namesStore("Steamy Nights 0.2", steam))
        assertFalse(namesStore("Steam Build", gog))
    }
}
