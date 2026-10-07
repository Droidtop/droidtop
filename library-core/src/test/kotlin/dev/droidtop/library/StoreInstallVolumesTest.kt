package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free-space answer a store install or update is asked with
 * (Droidtop/tracker#227): the fit check and the consent sheet's own
 * wording. The game-folder list and the StatFs read behind [installVolumes]
 * need a device; what is tested here is the decision and the sentence,
 * both pure.
 */
class StoreInstallVolumesTest {

    private val format: (Long) -> String = { "$it GB" }

    // Game folders the person named (Settings > Game folders), never droidtop's Android/data folder.
    private val card = InstallVolume("SD card", "/storage/ABCD-1234", 95L, 128L)
    private val phone = InstallVolume("Internal storage", "/storage/emulated/0", 12L, 32L)

    @Test
    fun `a download that fits passes, one past the edge does not`() {
        assertTrue(fits(12L, 12L))
        assertTrue(fits(11L, 12L))
        assertFalse(fits(13L, 12L))
    }

    @Test
    fun `an unknown size is never refused`() {
        assertTrue(fits(0L, 12L))
        assertTrue(fits(-1L, 12L))
    }

    @Test
    fun `the free space line names both numbers`() {
        assertEquals("95 GB free of 128 GB", freeSpaceLine(card, format))
    }

    @Test
    fun `a download that fits draws no warning`() {
        assertNull(notEnoughRoomLine(10L, card, format))
        assertNull(notEnoughRoomLine(95L, card, format))
    }

    @Test
    fun `an unknown size draws no warning`() {
        assertNull(notEnoughRoomLine(0L, phone, format))
    }

    @Test
    fun `a download that does not fit names both sizes and the volume`() {
        assertEquals(
            "Not enough room on Internal storage: the download is 60 GB but only 12 GB is free",
            notEnoughRoomLine(60L, phone, format),
        )
    }

    @Test
    fun `the install offer says the size, the room, and the warning`() {
        assertEquals(
            listOf(
                "Downloads 60 GB.",
                "12 GB free of 32 GB on Internal storage.",
                "Not enough room on Internal storage: the download is 60 GB but only 12 GB is free",
            ),
            storeInstallOfferLines(false, 60L, phone, format),
        )
    }

    @Test
    fun `an install that fits has no warning line`() {
        assertEquals(
            listOf(
                "Downloads 60 GB.",
                "95 GB free of 128 GB on SD card.",
            ),
            storeInstallOfferLines(false, 60L, card, format),
        )
    }

    @Test
    fun `an install of unknown size says so, without a warning`() {
        assertEquals(
            listOf(
                "The store does not name a size yet.",
                "12 GB free of 32 GB on Internal storage.",
            ),
            storeInstallOfferLines(false, 0L, phone, format),
        )
    }

    @Test
    fun `an update names the free space but never warns`() {
        // sizeBytes here is the on-disk size of the installed game, not
        // the update's size: the sheet must not call it a download.
        assertEquals(
            listOf(
                "A newer build is available; the store names its size as the download starts.",
                "12 GB free of 32 GB on Internal storage.",
            ),
            storeInstallOfferLines(true, 60L, phone, format),
        )
    }

    @Test
    fun `steam picks its own location, so its offer names the size and no room`() {
        assertEquals(listOf("Downloads 60 GB."), storeInstallOfferLines(false, 60L, null, format))
    }

    @Test
    fun `a store installs to its remembered game folder while it is still one, else the first`() {
        val folders = listOf("/storage/emulated/0/Games", "/storage/ABCD-1234/Games")
        assertEquals("/storage/emulated/0/Games", installFolderFor("/storage/emulated/0/Games", folders))
        assertEquals("/storage/ABCD-1234/Games", installFolderFor(null, folders))
        // A folder the person took off the list is not installed to.
        assertEquals("/storage/ABCD-1234/Games", installFolderFor("/storage/emulated/0/Android/data/dev.droidtop.app/files", folders))
        assertNull(installFolderFor("/storage/emulated/0/Games", emptyList()))
    }

    @Test
    fun `a folder is named by its place, not its path`() {
        assertEquals("Internal storage / Games / Cool Game", friendlyLocation("/storage/emulated/0/Games/Cool Game"))
        assertEquals("SD card / Games / Cool Game", friendlyLocation("/storage/1A2B-3C4D/Games/Cool Game"))
        assertEquals("SD card / Games / … / B / Cool Game", friendlyLocation("/storage/1A2B-3C4D/Games/A/B/Cool Game"))
        assertEquals("windows / Games / Cool Game", friendlyLocation("/mnt/windows/Games/Cool Game"))
        assertEquals("Internal storage", friendlyLocation("/storage/emulated/0"))
    }
}
