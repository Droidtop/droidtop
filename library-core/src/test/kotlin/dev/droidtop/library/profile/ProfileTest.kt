package dev.droidtop.library.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which name droidtop shows for the person (ProfileStore.resolve, Droidtop/tracker#586). */
class ProfileTest {
    private val steam = ProfileCandidate("social:steam", "Gabe", "Steam")
    private val itch = ProfileCandidate("store:itch", "gabe_itch", "itch.io")

    @Test
    fun `from your accounts is the first account's name`() {
        assertEquals("Gabe", ProfileStore.resolve("auto", "", listOf(steam, itch), null).name)
    }

    @Test
    fun `a picked account wins, and a gone one falls back to the first`() {
        assertEquals("gabe_itch", ProfileStore.resolve("store:itch", "", listOf(steam, itch), null).name)
        assertEquals("Gabe", ProfileStore.resolve("store:epic", "", listOf(steam, itch), null).name)
    }

    @Test
    fun `your own name overrides every account, and blank is no name`() {
        assertEquals("Me", ProfileStore.resolve("own", "Me", listOf(steam), null).name)
        assertNull(ProfileStore.resolve("own", "", listOf(steam), null).name)
        assertNull(ProfileStore.resolve("auto", "", emptyList(), null).name)
    }
}
