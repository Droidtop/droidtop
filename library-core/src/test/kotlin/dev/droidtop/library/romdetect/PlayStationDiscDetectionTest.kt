package dev.droidtop.library.romdetect

import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers telling a PS2 disc apart from a PS1 one.
 *
 * Both carry "PLAYSTATION" as their ISO9660 volume descriptor system
 * identifier, so magic numbers cannot separate them, and droidtop
 * reported every PS2 disc as PS1 -- launching those games through an
 * emulator that cannot run them.
 *
 * The images built here are synthetic but reproduce the parts the
 * detector actually depends on: a PVD at sector 16, a root directory
 * record pointing at a real directory, and SYSTEM.CNF content at its own
 * extent. The boot lines are copied verbatim from the user's real discs,
 * including the one that writes `BOOT2=` with no spaces at all.
 *
 * SYSTEM.CNF is deliberately placed well past where the earlier scanning
 * approach could see. On the real discs it sat as far in as 3.9 GB,
 * which is what made scanning unfixable and reading the filesystem
 * necessary.
 */
class PlayStationDiscDetectionTest {

    private val temporaryFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryFiles.forEach { it.delete() }
    }

    private companion object {
        const val SECTOR = 2048L
        const val PVD_SECTOR = 16L
        const val ROOT_DIRECTORY_SECTOR = 20L

        /** Byte 4,096,000 -- past any window the scanning approach used. */
        const val SYSTEM_CNF_SECTOR = 2000L
    }

    /** One directory record, laid out the way ISO9660 specifies it. */
    private fun directoryRecord(name: String, lba: Long, length: Int): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        var recordLength = 33 + nameBytes.size
        if (recordLength % 2 != 0) recordLength++
        val record = ByteArray(recordLength)
        record[0] = recordLength.toByte()
        writeLittleEndian(record, 2, lba)
        writeLittleEndian(record, 10, length.toLong())
        record[32] = nameBytes.size.toByte()
        nameBytes.copyInto(record, 33)
        return record
    }

    private fun writeLittleEndian(target: ByteArray, at: Int, value: Long) {
        target[at] = (value and 0xFF).toByte()
        target[at + 1] = ((value shr 8) and 0xFF).toByte()
        target[at + 2] = ((value shr 16) and 0xFF).toByte()
        target[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun disc(systemCnf: String?, volumeMagic: String = "CD001"): File {
        val file = File.createTempFile("disc", ".iso").also { temporaryFiles += it }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength((SYSTEM_CNF_SECTOR + 4) * SECTOR)

            // The PVD carries the same "PLAYSTATION" identifier a PS1
            // disc has -- the whole reason the two cannot be separated
            // without reading further into the image.
            val pvd = ByteArray(SECTOR.toInt())
            volumeMagic.toByteArray(Charsets.US_ASCII).copyInto(pvd, 1)
            "PLAYSTATION".toByteArray(Charsets.US_ASCII).copyInto(pvd, 8)
            directoryRecord(" ", ROOT_DIRECTORY_SECTOR, SECTOR.toInt()).copyInto(pvd, 156)
            raf.seek(PVD_SECTOR * SECTOR)
            raf.write(pvd)

            if (systemCnf != null) {
                val content = systemCnf.toByteArray(Charsets.US_ASCII)
                val directory = ByteArray(SECTOR.toInt())
                directoryRecord("SYSTEM.CNF;1", SYSTEM_CNF_SECTOR, content.size)
                    .copyInto(directory, 0)
                raf.seek(ROOT_DIRECTORY_SECTOR * SECTOR)
                raf.write(directory)

                raf.seek(SYSTEM_CNF_SECTOR * SECTOR)
                raf.write(content)
            }
        }
        return file
    }

    @Test
    fun `a disc booting through BOOT2 is PS2`() {
        // Verbatim from Sly 3 - Honor Among Thieves (USA).iso.
        val file = disc("BOOT2 = cdrom0:\\SCUS_974.64;1\nVER = 1.00\nVMODE = NTSC\n")
        assertEquals(SystemID.PS2, PlayStationDiscType.detect(file))
    }

    @Test
    fun `BOOT2 with no spaces is still PS2`() {
        // Verbatim from 007 - Agent Under Fire (USA).iso, which writes the
        // line without a single space -- matching "BOOT2 =" would miss it.
        val file = disc("BOOT2=cdrom0:\\SLUS_202.65;1\nVER=1.00\nVMODE=NTSC\n")
        assertEquals(SystemID.PS2, PlayStationDiscType.detect(file))
    }

    @Test
    fun `a disc booting through plain BOOT is PS1`() {
        val file = disc("BOOT = cdrom:\\SLUS_006.28;1\nTCB = 4\nEVENT = 10\n")
        assertEquals(SystemID.PSX, PlayStationDiscType.detect(file))
    }

    @Test
    fun `a non-ISO9660 file yields null so the caller falls back`() {
        val file = disc("BOOT2 = cdrom0:\\SCUS_974.64;1\n", volumeMagic = "XXXXX")
        assertNull(PlayStationDiscType.detect(file))
    }

    @Test
    fun `an ISO9660 image with no SYSTEM CNF yields null rather than a guess`() {
        assertNull(PlayStationDiscType.detect(disc(null)))
    }

    @Test
    fun `PS2 maps to the ps2 console system id`() {
        assertEquals("ps2", SystemID.PS2.toConsoleSystemId())
    }

    @Test
    fun `PSX still maps to psx, so nothing regressed`() {
        assertEquals("psx", SystemID.PSX.toConsoleSystemId())
    }
}
