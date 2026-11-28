package dev.droidtop.stores.ea

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EaOoaTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun values() = mapOf(
        "boardManufacturer" to "Board", "boardSerial" to "Serial", "biosManufacturer" to "BIOS", "biosSerial" to "BiosSerial",
        "osInstallDate" to "20260101000000.000000+000", "osSerial" to "OS", "volumeSerial" to "a1b2c3d4",
        "gpuPnpId" to "PCI\\VEN_1234\\DEVICE", "cpuVendor" to "GenuineIntel", "cpuEdx" to "01020304",
        "cpuEax" to "000006A1", "cpuEcx" to "1234abcd", "cpuBrand" to "Guest CPU", "hostname" to "GUEST",
    )

    @Test fun `machine hash preserves EA's legacy byte formatting and current full hex`() {
        val hardware = EaOoa.Hardware.fromWine(values())
        // Independently calculated from the documented field strings with SHA-1, not host hardware.
        assertEquals("87d3891eee8a6a285a6c303185a0f77a4dc058d8", EaOoa.machineHash(hardware, 1))
        assertEquals("b156b86d7ab99e91a14819a98859c7a02a6629a4", EaOoa.machineHash(hardware, 3))
        assertEquals("1d431c6e7ae911ac975b28d266512e5768a13fc2", EaOoa.machineHash(hardware, 4))
    }

    @Test fun `each activation version requires the fields it actually hashes`() {
        val noOs = EaOoa.Hardware.fromWine(values() - "osSerial")
        assertFalse(noOs.complete(1))
        assertTrue(noOs.complete(4))
        assertFalse(EaOoa.Hardware.fromWine(values() - "cpuEax").complete(4))
        assertFalse(EaOoa.Hardware.fromWine(values()).complete(5))
        assertTrue(runCatching { EaOoa.machineHash(noOs, 1) }.isFailure)
    }

    @Test fun `licence XML uses bounded shared parser and cannot name paths outside its directory`() {
        val signature = java.util.Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
        val xml = "<License xmlns=\"urn:fixture\"><ContentId>12345</ContentId><MachineHash>abc</MachineHash><GameToken>a&amp;b</GameToken></License>"
        val licence = EaOoa.parseLicense(xml, signature)
        assertEquals("a&b", licence.gameToken)
        val blob = EaOoa.licenseBlob(licence, false)
        assertArrayEquals(ByteArray(64) { it.toByte() }, blob.copyOfRange(0, 64))
        val decrypted = String(EaCrypto.licenseDecrypt(blob.copyOfRange(65, blob.size)), Charsets.UTF_8)
        assertEquals("a&b", EaOoa.parseLicense(decrypted, signature).gameToken)
        val core = temporary.newFolder("Core")
        assertEquals(listOf("${EaOoa.LICENSE_DIR}/12345.dlf", "${EaOoa.LICENSE_DIR}/12345_cached.dlf"), EaOoa.files(core, licence).map { it.first })
        for (bad in listOf(
            "<!DOCTYPE License [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><License><GameToken>&x;</GameToken></License>",
            "<License><ContentId>1</ContentId><ContentId>2</ContentId></License>",
            "<License><GameToken><Nested>secret</Nested></GameToken></License>",
        )) assertTrue(runCatching { EaOoa.parseLicense(bad, signature) }.isFailure)
        val escaping = EaOoa.parseLicense("<License><ContentId>../outside</ContentId></License>", signature)
        assertTrue(runCatching { EaOoa.files(core, escaping) }.isFailure)
    }

    @Test fun `unknown activation versions are not silently replaced with a guessed version`() {
        val core = temporary.newFolder("ActivationCore")
        assertNull(EaOoa.activationVersion(core))
        val bytes = ByteArray(52)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, 0xFEEF04BD.toInt()); b.putInt(4, 0x00010000)
        b.putInt(16, (4 shl 16) or 7); b.putInt(20, 6 shl 16)
        java.io.File(core, "activation.dll").writeBytes(bytes)
        assertEquals(3, EaOoa.activationVersion(core))
        b.putInt(4, 0)
        assertNull(EaOoa.productVersion(bytes))
    }
}
