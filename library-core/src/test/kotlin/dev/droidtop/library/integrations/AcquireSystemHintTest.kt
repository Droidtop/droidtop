package dev.droidtop.library.integrations

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcquireSystemHintTest {
    @Test
    fun `a new system folder goes beside most of the person's system folders, else in the first games root`() {
        // A DOS zip from Abandonware was refused with "there is no games folder for dos" (Droidtop/tracker#846).
        val roots = listOf(File("/sd/Games"), File("/sd"))
        val folders = listOf(File("/sd/Roms/nsw"), File("/sd/Roms/snes"), File("/sd/Games/psx"))
        assertEquals(File("/sd/Roms"), AcquireSystemHint.placeFor(folders, roots))
        // On a tie, the first one met.
        assertEquals(File("/sd/Games"), AcquireSystemHint.placeFor(listOf(File("/sd/Games/psx"), File("/sd/Roms/nsw")), roots))
        assertEquals(File("/sd/Games"), AcquireSystemHint.placeFor(emptyList(), roots))
        assertNull(AcquireSystemHint.placeFor(emptyList(), emptyList()))
    }

    @Test
    fun `a system hint is a short lower-case id or nothing`() {
        assertEquals("dos", AcquireSystemHint.valid(" dos "))
        assertNull(AcquireSystemHint.valid("../dos"))
        assertNull(AcquireSystemHint.valid(""))
    }
}
