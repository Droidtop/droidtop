package dev.droidtop.library.romdetect

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers telling a PS2 disc apart from a PS1 one.
 *
 * Both carry the literal string "PLAYSTATION" as their ISO9660 volume
 * descriptor system identifier, so the magic-number check alone reported
 * every PS2 disc as PS1. On the real device that meant all seven ISOs
 * under `/Roms/ps2/` were stored as `psx` and launched DuckStation,
 * which cannot run them.
 *
 * The images below are synthetic but reproduce the real layout the
 * detector actually depends on: the volume identifier at 0x8008, and a
 * SYSTEM.CNF boot line far enough into the file that the old 64 KB
 * search window could not have seen it. The boot lines are copied
 * verbatim from real discs read off the device.
 */
class PlayStationDiscDetectionTest {

    private companion object {
        const val VOLUME_ID_OFFSET = 0x8008

        /** Where SYSTEM.CNF really sat on the two discs this was verified against. */
        const val SYSTEM_CNF_OFFSET = 552_960

        const val IMAGE_SIZE = 3 * 1024 * 1024
    }

    private fun disc(bootLine: String): ByteArray {
        val image = ByteArray(IMAGE_SIZE)
        "PLAYSTATION".toByteArray(Charsets.US_ASCII)
            .copyInto(image, VOLUME_ID_OFFSET)
        bootLine.toByteArray(Charsets.US_ASCII)
            .copyInto(image, SYSTEM_CNF_OFFSET)
        return image
    }

    private fun detect(image: ByteArray) =
        SerialScanner.extractInfo("game.iso", ByteArrayInputStream(image))

    @Test
    fun `a disc booting through BOOT2 is PS2`() {
        // Verbatim from Sly 3 - Honor Among Thieves (USA).iso.
        val info = detect(disc("BOOT2 = cdrom0:\\SCUS_974.64;1"))
        assertEquals(SystemID.PS2, info.systemID)
    }

    @Test
    fun `a disc booting through plain BOOT is still PS1`() {
        val info = detect(disc("BOOT = cdrom:\\SLUS_006.28;1"))
        assertEquals(SystemID.PSX, info.systemID)
    }

    @Test
    fun `the PS2 serial is read, not the BOOT2 marker itself`() {
        // parsePSXSerial reads "BOOT2 = cdro" as the serial "BOOT-2" if
        // the marker is not excluded first, and the marker appears
        // before the serial in the line -- so this is the case that
        // catches that specific mistake.
        val info = detect(disc("BOOT2 = cdrom0:\\SCUS_974.64;1"))
        assertEquals("SCUS-97464", info.serial)
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
