package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Title-ID classification for Switch content (docs/SPEC.md 7m, "Switch
 * content"), against the task's own examples: a title ID is 16 hex
 * characters and its LAST THREE say base (`000`) / update (`800`) /
 * add-on index -- the review of the first attempt of this change found
 * the suffix read as four characters, which sent every real ID down
 * the DLC branch and rebuilt one-char-short base IDs, so these tests
 * pin the 3-character suffix and the 16-character reconstruction.
 *
 * The PFS0 reader is exercised against synthetic containers built with
 * the REAL header layout (magic, counts, 24-byte entries, NUL-terminated
 * string table), because the reader is only honest if the writer it is
 * tested against is the format the console actually uses.
 */
class SwitchContentTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun classifyByTitleId(titleId: String) = SwitchContent.classifyByTitleId(titleId)

    @Test
    fun `a title id ending 000 is the base game, and its own base`() {
        val c = classifyByTitleId("0100123456789000")
        assertTrue(c is SwitchContent.BaseGame)
        assertEquals("0100123456789000", c!!.titleId)
        assertEquals(16, c.baseTitleId!!.length)
        assertEquals("0100123456789000", c.baseTitleId)
    }

    @Test
    fun `a title id ending 800 is the update of the base id 000`() {
        val c = classifyByTitleId("0100123456789800")
        assertTrue(c is SwitchContent.Update)
        assertEquals("0100123456789000", c!!.baseTitleId)
        assertEquals(16, c.baseTitleId!!.length)
    }

    @Test
    fun `any other suffix is dlc, and the suffix is its add-on index`() {
        val c = classifyByTitleId("0100123456789001")
        assertTrue(c is SwitchContent.Dlc)
        assertEquals(1, (c as SwitchContent.Dlc).addOnIndex)
        assertEquals("0100123456789000", c.baseTitleId)

        val letters = classifyByTitleId("0100abcdef01230d0")
        assertTrue(letters is SwitchContent.Dlc)
        assertEquals(0x0D0, (letters as SwitchContent.Dlc).addOnIndex)
        assertEquals("0100abcdef0123000", letters.baseTitleId)
    }

    @Test
    fun `lowercase hex ids classify the same`() {
        assertTrue(classifyByTitleId("0100abcdef0123800") is SwitchContent.Update)
        assertTrue(classifyByTitleId("0100abcdef0123000") is SwitchContent.BaseGame)
    }

    @Test
    fun `not a title id says nothing`() {
        assertNull(classifyByTitleId("01001234567890")) // 14 chars
        assertNull(classifyByTitleId("01001234567890000")) // 17 chars
        assertNull(classifyByTitleId("not a title id!!"))
        assertNull(classifyByTitleId(""))
    }

    @Test
    fun `the filename tag carries the title id and the vN tag the version`() {
        val update = SwitchContent.classify(File("Zelda [01007ef00011e800][v131072].nsp"))!!
        assertTrue(update is SwitchContent.Update)
        assertEquals("131072", update.version)
        assertEquals("01007ef00011e000", update.baseTitleId)

        val base = SwitchContent.classify(File("Zelda [01007ef00011e000][v65536].nsp"))!!
        assertTrue(base is SwitchContent.BaseGame)
        assertEquals("65536", base.version)

        val dlc = SwitchContent.classify(File("Zelda [01007ef00011f000][DLC].nsp"))!!
        assertTrue(dlc is SwitchContent.Dlc)
    }

    @Test
    fun `a ticket name in the pfs0 file table carries the title id when the filename says nothing`() {
        val nsp = pfs0("01007ef00011e8000000000000000004.tik", "01007ef00011e800.nca")
        val c = SwitchContent.classify(nsp)!!
        assertTrue(c is SwitchContent.Update)
        assertEquals("01007ef00011e800", c.titleId)
        assertEquals("01007ef00011e000", c.baseTitleId)
    }

    @Test
    fun `a pfs0 with no ticket and no tag says nothing`() {
        assertNull(SwitchContent.classify(pfs0("01007ef00011e800.nca")))
    }

    @Test
    fun `a wrong magic is not a pfs0`() {
        val bad = tmp.newFile("bad.nsp")
        bad.writeBytes(ByteArray(64) { 0 })
        assertNull(SwitchContent.ticketTitleId(bad))
    }

    @Test
    fun `a cartridge dump is base content, with or without a tag`() {
        assertTrue(SwitchContent.classify(File("Some Game.xci")) is SwitchContent.BaseGame)
        assertTrue(SwitchContent.classify(File("Some Game.xcz")) is SwitchContent.BaseGame)
        val tagged = SwitchContent.classify(File("Update Card [0100123456789800].xci"))!!
        assertTrue(tagged is SwitchContent.Update)
    }

    @Test
    fun `a bare DLC tag without a title id is dlc with no base to fold into`() {
        val c = SwitchContent.classify(File("Expansion Pass [DLC].xci"))!!
        assertTrue(c is SwitchContent.Dlc)
        assertNull(c.baseTitleId)
    }

    @Test
    fun `not a switch content extension says nothing`() {
        assertNull(SwitchContent.classify(File("game.z64")))
        assertNull(SwitchContent.classify(File("game.nro")))
    }

    /**
     * A PFS0 container, built with the real layout: magic, entry count,
     * string-table size, reserved u32, then one 24-byte entry per file
     * (u64 content size, u64 content offset, u32 name offset, u32
     * padding) and the NUL-terminated names.
     */
    private fun pfs0(vararg names: String): File {
        val table = ByteArrayOutputStream()
        val nameOffsets = IntArray(names.size)
        for ((index, name) in names.withIndex()) {
            nameOffsets[index] = table.size()
            table.write(name.toByteArray(Charsets.US_ASCII))
            table.write(0)
        }
        val strings = table.toByteArray()
        val header = ByteBuffer.allocate(16 + names.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        header.put("PFS0".toByteArray(Charsets.US_ASCII))
        header.putInt(names.size)
        header.putInt(strings.size)
        header.putInt(0)
        for (index in names.indices) {
            header.putLong(1) // content size: one dummy byte
            header.putLong(0) // content offset
            header.putInt(nameOffsets[index])
            header.putInt(0)
        }
        val file = tmp.newFile("test-" + names.hashCode() + ".nsp")
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(strings)
            out.write(0x42)
        }
        return file
    }
}
