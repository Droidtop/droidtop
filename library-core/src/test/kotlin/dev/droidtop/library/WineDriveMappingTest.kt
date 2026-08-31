package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drive assignment is one shared rule precisely so the settings
 * screen's preview cannot drift from what provisioning writes into the
 * container -- these tests pin the rule itself.
 */
class WineDriveMappingTest {

    @Test
    fun `roots are assigned from D onward in order`() {
        assertEquals(
            listOf('D' to "/storage/7EF7-E477/Roms", 'E' to "/storage/emulated/0/Games"),
            WineDriveMapping.assign(listOf("/storage/7EF7-E477/Roms", "/storage/emulated/0/Games")),
        )
    }

    @Test
    fun `a path containing a colon is skipped, not encoded`() {
        // The container's drive string finds each letter by the character
        // before a ':' -- one such path would corrupt every mapping after
        // it, so it must not survive into the assignment at all.
        assertEquals(
            listOf('D' to "/clean/path"),
            WineDriveMapping.assign(listOf("/bad:path", "/clean/path")),
        )
    }

    @Test
    fun `duplicates collapse and letters stay dense`() {
        assertEquals(
            listOf('D' to "/a", 'E' to "/b"),
            WineDriveMapping.assign(listOf("/a", "/a", "/b")),
        )
    }

    @Test
    fun `assignment stops at Z rather than wrapping into nonsense`() {
        val roots = (1..30).map { "/root/$it" }
        val assigned = WineDriveMapping.assign(roots)
        assertEquals('Z' - 'D' + 1, assigned.size)
        assertEquals('Z', assigned.last().first)
    }

    @Test
    fun `no roots means no drives, not a crash`() {
        assertEquals(emptyList<Pair<Char, String>>(), WineDriveMapping.assign(emptyList()))
    }
}
