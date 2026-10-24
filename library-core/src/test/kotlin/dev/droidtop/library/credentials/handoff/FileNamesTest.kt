package dev.droidtop.library.credentials.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules for names and sizes of transferred files (docs/SPEC.md, "Type from another device", file transfer, Droidtop/tracker#645). */
class FileNamesTest {
    @Test
    fun `a name keeps only its last part, whatever separator the phone used`() {
        assertEquals("passwd", FileNames.sanitise("../../etc/passwd"))
        assertEquals("x.txt", FileNames.sanitise("a/b/x.txt"))
        assertEquals("x.txt", FileNames.sanitise("C:\\Users\\me\\x.txt"))
        assertEquals("evil.sh", FileNames.sanitise("..\\..\\evil.sh"))
        assertEquals("photo.jpg", FileNames.sanitise("photo.jpg"))
    }

    @Test
    fun `nothing hidden, no dot names, no control characters, no characters storage rejects`() {
        assertEquals("bashrc", FileNames.sanitise(".bashrc"))
        assertEquals(FileNames.FALLBACK, FileNames.sanitise(".."))
        assertEquals(FileNames.FALLBACK, FileNames.sanitise("."))
        assertEquals(FileNames.FALLBACK, FileNames.sanitise(""))
        assertEquals(FileNames.FALLBACK, FileNames.sanitise("   "))
        assertEquals(FileNames.FALLBACK, FileNames.sanitise("dir/"))
        assertEquals("a_b_c.txt", FileNames.sanitise("a\u0000b\nc.txt"))
        assertEquals("a_b_c_d_e_f_g.txt", FileNames.sanitise("a:b*c?d\"e<f|g.txt"))
        assertEquals("name", FileNames.sanitise("name. . "))
        assertEquals("caf\u00e9 \u30c6\u30b9\u30c8.png", FileNames.sanitise("caf\u00e9 \u30c6\u30b9\u30c8.png"))
    }

    @Test
    fun `a long name is cut to 120 bytes of whole characters and keeps its extension`() {
        val long = "a".repeat(300) + ".zip"
        val cut = FileNames.sanitise(long)
        assertTrue(cut.endsWith(".zip"))
        assertEquals(120, cut.toByteArray().size)
        val wide = FileNames.sanitise("\u00e9".repeat(200) + ".bin")
        assertTrue(wide.toByteArray().size <= 120)
        assertTrue(wide.endsWith(".bin"))
        assertFalse(wide.contains('\ufffd'))
        // A surrogate pair is never split.
        val emoji = FileNames.sanitise("\uD83C\uDFAE".repeat(100) + ".bin")
        assertTrue(emoji.toByteArray().size <= 120)
        assertEquals(emoji, String(emoji.toByteArray(), Charsets.UTF_8))
    }

    @Test
    fun `a taken name becomes Name (2), then (3), before the extension`() {
        val taken = mutableSetOf<String>()
        assertEquals("photo.jpg", FileNames.unique("photo.jpg") { it in taken })
        taken += "photo.jpg"
        assertEquals("photo (2).jpg", FileNames.unique("photo.jpg") { it in taken })
        taken += "photo (2).jpg"
        assertEquals("photo (3).jpg", FileNames.unique("photo.jpg") { it in taken })
        taken += "notes"
        assertEquals("notes (2)", FileNames.unique("notes") { it in taken })
        taken += "x.tar.gz"
        assertEquals("x (2).tar.gz", FileNames.unique("x.tar.gz") { it in taken })
    }

    @Test
    fun `a name that is taken without end gives up instead of looping`() {
        assertNull(FileNames.unique("a.txt") { true })
    }

    @Test
    fun `sizes are said in 1024 steps with one decimal`() {
        assertEquals("0 B", FileNames.size(0))
        assertEquals("812 B", FileNames.size(812))
        assertEquals("1.0 KB", FileNames.size(1024))
        assertEquals("1.5 MB", FileNames.size(1_572_864))
        assertEquals("4.0 GB", FileNames.size(FileNames.MAX_BYTES))
    }

    @Test
    fun `the download header cannot be broken by a name and carries the real one`() {
        val header = HandoffServer.contentDisposition("a\"b\r\nX-Evil: 1%.txt")
        assertFalse(header.contains('\r') || header.contains('\n'))
        assertTrue(header.startsWith("attachment; filename=\"a_b__X-Evil: 1_.txt\"; "))
        assertEquals("attachment; filename=\"caf_.txt\"; filename*=UTF-8''caf%C3%A9.txt", HandoffServer.contentDisposition("caf\u00e9.txt"))
    }

    @Test
    fun `a chosen folder is said in words`() {
        assertEquals("Download/Games", FileNames.folderLabel("primary:Download/Games"))
        assertEquals("Internal storage", FileNames.folderLabel("primary:"))
        assertEquals("1A2B-3C4D: Roms/GBA", FileNames.folderLabel("1A2B-3C4D:Roms/GBA"))
        assertEquals("1A2B-3C4D", FileNames.folderLabel("1A2B-3C4D:"))
        assertEquals("raw", FileNames.folderLabel("raw"))
    }
}
