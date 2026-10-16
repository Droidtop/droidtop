package dev.droidtop.library.integrations

import dev.droidtop.library.consoles.BiosFileSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a BIOS file from Game sources goes (Droidtop/tracker#558): the games folder's bios folder, by the database's name. */
class AcquireBiosTest {
    private val ps2 = listOf(
        BiosFileSpec("bios/ps2-0230a-20080220.bin", listOf("1AD977BB539FC9448A08AB276A836BBC")),
        BiosFileSpec("bios/scph39001.bin", listOf("d5ce2c7d119f563ce04bc04dbc3a323e")),
    )

    @Test
    fun `a file whose md5 the database lists is that entry, whatever it is called`() {
        assertEquals(ps2[0], AcquireBios.entryFor(ps2, "renamed.bin", "1ad977bb539fc9448a08ab276a836bbc", providesBios = false))
    }

    @Test
    fun `a name alone counts only from a plugin that offers BIOS files`() {
        assertEquals(ps2[1], AcquireBios.entryFor(ps2, "SCPH39001.BIN", null, providesBios = true))
        assertNull("a game source naming a file like a BIOS is not trusted on the name", AcquireBios.entryFor(ps2, "scph39001.bin", null, providesBios = false))
        assertNull(AcquireBios.entryFor(ps2, "Game (USA).iso", "0123456789abcdef0123456789abcdef", providesBios = true))
    }

    @Test
    fun `the target is the bios folder of the games folder the screen's folder is in`() {
        val roots = listOf(File("/storage/7EF7-E477/Roms"), File("/storage/emulated/0/Games"))
        // The rig's case: the screen named the games root itself.
        val atRoot = AcquireBios.rootFor(roots, File("/storage/7EF7-E477/Roms"))
        assertEquals(File("/storage/7EF7-E477/Roms/bios/ps2-0230a-20080220.bin"), AcquireBios.targetFor(atRoot, ps2[0]))
        // A system folder inside it.
        assertEquals(File("/storage/7EF7-E477/Roms"), AcquireBios.rootFor(roots, File("/storage/7EF7-E477/Roms/ps2")))
        // A folder under no known root: its parent.
        assertEquals(File("/other"), AcquireBios.rootFor(roots, File("/other/ps2")))
        // A root that only shares a prefix is not this one.
        assertEquals(File("/storage/7EF7-E477"), AcquireBios.rootFor(roots, File("/storage/7EF7-E477/Roms2")))
    }
}
