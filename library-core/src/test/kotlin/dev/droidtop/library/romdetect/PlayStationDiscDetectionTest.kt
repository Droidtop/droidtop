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
 * The images built here are synthetic but reproduce the structures the
 * detector actually walks: a typed PVD at sector 16, a root directory
 * record, and SYSTEM.CNF content at its own extent -- in plain 2048-byte
 * sectors AND in raw 2352-byte layouts, because the first reader only
 * understood 2048 and silently returned null for every raw `.bin`, the
 * most common PS1 dump format. Boot lines are verbatim from real discs,
 * including the one that writes `BOOT2=` with no spaces.
 *
 * SYSTEM.CNF is placed well past where the abandoned scanning approach
 * could see (it sat at 3.9 GB on one real disc), and its content is
 * sized to span a sector boundary so the sector-by-sector read is
 * actually exercised -- in a raw image a single long read would
 * interleave sync headers into the text.
 */
class PlayStationDiscDetectionTest {

    private val temporaryFiles = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryFiles.forEach { it.delete() }
    }

    private companion object {
        const val PVD_SECTOR = 16L
        const val ROOT_DIRECTORY_SECTOR = 20L

        /** Sector 2000 -- far past any window the scanning approach used. */
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

    /**
     * Builds an image in the given sector layout. [sectorSize]/
     * [dataOffset] pairs mirror the reader's own table: (2048, 0) for a
     * plain .iso, (2352, 24) for raw Mode 2 (what PS1 discs are),
     * (2352, 16) for raw Mode 1.
     */
    private fun disc(
        systemCnf: String?,
        sectorSize: Long = 2048,
        dataOffset: Long = 0,
        typeByte: Byte = 1,
        volumeMagic: String = "CD001",
    ): File {
        val file = File.createTempFile("disc", ".iso").also { temporaryFiles += it }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength((SYSTEM_CNF_SECTOR + 4) * sectorSize)

            fun writeSector(lba: Long, data: ByteArray) {
                raf.seek(lba * sectorSize + dataOffset)
                raf.write(data)
            }

            // The PVD carries the same "PLAYSTATION" identifier a PS1
            // disc has -- the whole reason the two cannot be separated
            // without reading further into the image.
            val pvd = ByteArray(2048)
            pvd[0] = typeByte
            volumeMagic.toByteArray(Charsets.US_ASCII).copyInto(pvd, 1)
            "PLAYSTATION".toByteArray(Charsets.US_ASCII).copyInto(pvd, 8)
            directoryRecord(" ", ROOT_DIRECTORY_SECTOR, 2048).copyInto(pvd, 156)
            writeSector(PVD_SECTOR, pvd)

            if (systemCnf != null) {
                val content = systemCnf.toByteArray(Charsets.US_ASCII)
                val directory = ByteArray(2048)
                directoryRecord("SYSTEM.CNF;1", SYSTEM_CNF_SECTOR, content.size)
                    .copyInto(directory, 0)
                writeSector(ROOT_DIRECTORY_SECTOR, directory)

                // Sector by sector, matching how a real raw image lays
                // file content out -- a contiguous write here would let a
                // broken contiguous READ pass the test.
                var written = 0
                var lba = SYSTEM_CNF_SECTOR
                while (written < content.size) {
                    val chunk = content.copyOfRange(written, minOf(written + 2048, content.size))
                    writeSector(lba, chunk)
                    written += chunk.size
                    lba += 1
                }
            }
        }
        return file
    }

    /** Real Sly 3 boot line, padded past one sector so the read spans two. */
    private fun ps2Cnf(): String =
        "BOOT2 = cdrom0:\\SCUS_974.64;1\nVER = 1.00\nVMODE = NTSC\n" +
            "# ".repeat(1100) + "\n"

    @Test
    fun `a plain iso booting through BOOT2 is PS2`() {
        assertEquals(SystemID.PS2, PlayStationDiscType.detect(disc(ps2Cnf())))
    }

    @Test
    fun `a raw Mode 2 bin is read too -- what PS1 dumps actually are`() {
        // The first reader only understood 2048-byte sectors, so every
        // raw .bin silently returned null and fell back to a guess.
        val file = disc(ps2Cnf(), sectorSize = 2352, dataOffset = 24)
        assertEquals(SystemID.PS2, PlayStationDiscType.detect(file))
    }

    @Test
    fun `a raw Mode 1 bin is read as well`() {
        val file = disc(ps2Cnf(), sectorSize = 2352, dataOffset = 16)
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
        val file = disc(ps2Cnf(), volumeMagic = "XXXXX")
        assertNull(PlayStationDiscType.detect(file))
    }

    @Test
    fun `a wrong descriptor type is rejected, not just a wrong magic`() {
        // The probe checks three offsets in arbitrary files; the type
        // byte is the second lock on the door.
        val file = disc(ps2Cnf(), typeByte = 0)
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
