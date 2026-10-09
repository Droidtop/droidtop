package dev.droidtop.runtime.windows

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Which drive letter an installer's folder is reached at (docs/SPEC.md 7c, "Install a new game"). */
class PrefixDrivesTest {
    private val drives = "D:/storage/emulated/0/DownloadE:/data/data/dev.droidtop.app/storage"

    @Test
    fun `the drives string is read letter and folder at a time`() {
        assertEquals(
            listOf('D' to "/storage/emulated/0/Download", 'E' to "/data/data/dev.droidtop.app/storage"),
            PrefixDrives.entries(drives),
        )
        assertEquals(emptyList<Pair<Char, String>>(), PrefixDrives.entries(""))
    }

    @Test
    fun `a folder inside a mapped drive uses that drive and adds none`() {
        val mapped = PrefixDrives.withFolder(drives, File("/storage/emulated/0/Download/GOG/setup"))!!
        assertEquals('D', mapped.letter)
        assertFalse(mapped.added)
        assertEquals(drives, mapped.drives)
    }

    @Test
    fun `a folder outside every drive gets the first free letter`() {
        val mapped = PrefixDrives.withFolder(drives, File("/storage/1234-ABCD/Installers"))!!
        assertEquals('F', mapped.letter)
        assertTrue(mapped.added)
        assertEquals("$drives" + "F:/storage/1234-ABCD/Installers", mapped.drives)
        assertEquals('F' to "/storage/1234-ABCD/Installers", PrefixDrives.entries(mapped.drives).last())
    }

    @Test
    fun `a folder next to a mapped one is not taken for being inside it`() {
        val mapped = PrefixDrives.withFolder(drives, File("/storage/emulated/0/Download2"))!!
        assertTrue(mapped.added)
    }

    @Test
    fun `a path with a colon is refused, it would corrupt the drives after it`() {
        assertNull(PrefixDrives.withFolder(drives, File("/storage/a:b")))
    }

    @Test
    fun `no free letter means no mapping`() {
        val full = ('D'..'Y').joinToString("") { "$it:/x$it" }
        assertNull(PrefixDrives.withFolder(full, File("/somewhere/else")))
    }
}
