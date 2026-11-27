package dev.droidtop.stores.ea

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class EaWineHardwareTest {
    @Test fun `Wine output keeps Unicode firmware values`() {
        val text = "Manufacturer\r\nFabricant é\r\n"
        assertEquals("Fabricant é", EaWineHardware.wmiValue(EaWineHardware.decode(text.toByteArray(Charsets.UTF_16))))
        assertEquals("Fabricant é", EaWineHardware.wmiValue(EaWineHardware.decode(text.toByteArray(Charsets.UTF_16LE))))
        assertEquals("", EaWineHardware.wmiValue("Manufacturer\r\n"))
    }

    @Test fun `CPUID parser uses emulated registers and refuses incomplete output`() {
        val raw = ByteArray(80)
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        "Genu".toByteArray().copyInto(raw, 4)
        "ineI".toByteArray().copyInto(raw, 12)
        "ntel".toByteArray().copyInto(raw, 8)
        b.putInt(16, 0x000006A1); b.putInt(24, 0x1234ABCD); b.putInt(28, 0x01020304)
        "Guest CPU".toByteArray().copyInto(raw, 32)
        val values = EaWineHardware.cpuidValues(raw)
        assertEquals("GenuineIntel", values["cpuVendor"])
        assertEquals("000006A1", values["cpuEax"])
        assertEquals("1234abcd", values["cpuEcx"])
        assertEquals("01020304", values["cpuEdx"])
        assertEquals("Guest CPU", values["cpuBrand"])
        assertTrue(runCatching { EaWineHardware.cpuidValues(raw.copyOf(79)) }.isFailure)
        assertTrue(runCatching { EaWineHardware.cpuidValues(ByteArray(80)) }.isFailure)
    }

    @Test fun `probe image is a PE32 console with actual CPUID instructions and stdout imports`() {
        val image = EaGuestPrograms.cpuid()
        val b = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1024, image.size)
        assertEquals("MZ", String(image, 0, 2))
        assertEquals(0x40, b.getInt(0x3C))
        assertEquals(0x14C, b.getShort(0x44).toInt())
        assertEquals(0x10B, b.getShort(0x58).toInt())
        assertEquals(3, b.getShort(0x58 + 68).toInt())
        assertEquals(5, (0x200 until 0x2FF).count { image[it] == 0x0F.toByte() && image[it + 1] == 0xA2.toByte() })
        assertTrue(String(image, Charsets.ISO_8859_1).contains("WriteFile"))
    }
}
