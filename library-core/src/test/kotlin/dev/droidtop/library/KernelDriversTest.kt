package dev.droidtop.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The kernel drivers read from a Wine prefix (docs/SPEC.md 5b, "Kernel drivers"; Droidtop/tracker#607). Read only. */
class KernelDriversTest {
    @get:Rule val tmp = TemporaryFolder()

    private val systemReg = """
        WINE REGISTRY Version 2
        ;; All keys relative to \\Machine

        [System\\CurrentControlSet\\Services\\EasyAntiCheat] 1700000000
        #time=1da0000000000000
        "DisplayName"="EasyAntiCheat"
        "ErrorControl"=dword:00000001
        "ImagePath"=str(2):"\\??\\C:\\windows\\system32\\drivers\\EasyAntiCheat.sys"
        "Start"=dword:00000003
        "Type"=dword:00000001

        [System\\CurrentControlSet\\Services\\EasyAntiCheat\\Security] 1700000000
        "Type"=dword:00000001

        [System\\CurrentControlSet\\Services\\MountMgr] 1700000000
        "ImagePath"=str(2):"C:\\windows\\system32\\drivers\\mountmgr.sys"
        "Start"=dword:00000002
        "Type"=dword:00000001

        [System\\CurrentControlSet\\Services\\PlugPlay] 1700000000
        "ImagePath"=str(2):"C:\\windows\\system32\\plugplay.exe"
        "Type"=dword:00000020

        [System\\CurrentControlSet\\Services\\sfdrv01] 1700000000
        "ImagePath"="System32\\drivers\\sfdrv01.sys"
        "Start"=dword:00000004
        "Type"=dword:00000002
    """.trimIndent()

    @Test
    fun `kernel and file-system services are read, user-mode services and subkeys are not`() {
        val found = KernelDrivers.parse(systemReg)
        assertEquals(listOf("EasyAntiCheat", "MountMgr", "sfdrv01"), found.map { it.name })
        val eac = found.first()
        assertEquals(1, eac.type)
        assertEquals(3, eac.start)
        assertEquals("EasyAntiCheat.sys", eac.fileName)
        assertEquals("EasyAntiCheat", eac.product)
        assertEquals("easyanticheat.sys", eac.key)
        assertEquals("StarForce", found.last().product)
        assertEquals(4, found.last().start)
    }

    @Test
    fun `an image path resolves inside the prefix whatever its form and case`() {
        val prefix = tmp.newFolder("wine")
        val drivers = File(prefix, "drive_c/Windows/System32/Drivers").apply { mkdirs() }
        val sys = File(drivers, "EasyAntiCheat.sys").apply { writeText("x") }
        assertEquals(sys, KernelDrivers.fileIn(prefix, "\\??\\C:\\windows\\system32\\drivers\\EasyAntiCheat.sys"))
        assertEquals(sys, KernelDrivers.fileIn(prefix, "\\SystemRoot\\System32\\drivers\\easyanticheat.sys"))
        assertEquals(sys, KernelDrivers.fileIn(prefix, "System32\\drivers\\EasyAntiCheat.sys"))
        assertNull(KernelDrivers.fileIn(prefix, "D:\\elsewhere\\x.sys"))
        assertNull(KernelDrivers.fileIn(prefix, "System32\\..\\..\\x.sys"))
    }

    @Test
    fun `Wine's own drivers are told by their file's mark, or by name when the file is not there`() {
        val prefix = tmp.newFolder("wine")
        val drivers = File(prefix, "drive_c/windows/system32/drivers").apply { mkdirs() }
        val wine = File(drivers, "mountmgr.sys").apply { writeBytes(ByteArray(0x40) + "Wine builtin DLL".toByteArray() + ByteArray(16)) }
        val foreign = File(drivers, "EasyAntiCheat.sys").apply { writeBytes(ByteArray(0x80) { 0x4D }) }
        val found = KernelDrivers.parse(systemReg)
        assertTrue(KernelDrivers.isWineOwn(found[1], wine))
        assertFalse(KernelDrivers.isWineOwn(found[0], foreign))
        assertTrue(KernelDrivers.isWineOwn(KernelDriver("winebus", 1, 3, null), null))
        assertFalse(KernelDrivers.isWineOwn(KernelDriver("BEService", 1, 3, null), null))

        File(prefix, "system.reg").writeText(systemReg)
        assertEquals(listOf("EasyAntiCheat", "sfdrv01"), KernelDrivers.scan(prefix).map { it.name })
    }

    @Test
    fun `the record line names the driver, its product and whether it is approved`() {
        val eac = KernelDrivers.parse(systemReg).first()
        assertEquals(
            "EasyAntiCheat (EasyAntiCheat.sys, EasyAntiCheat): not approved; droidtop does not yet keep it out of the prefix",
            KernelDrivers.recordLine(eac, approved = false),
        )
        assertEquals("EasyAntiCheat (EasyAntiCheat.sys, EasyAntiCheat): approved", KernelDrivers.recordLine(eac, approved = true))
    }

    @Test
    fun `a stored driver reads back as it was`() {
        KernelDrivers.parse(systemReg).forEach { driver ->
            assertEquals(driver, KernelDriverPrefs.decode(KernelDriverPrefs.encode(driver)))
        }
        assertNull(KernelDriverPrefs.decode("only-a-name"))
        assertNull(KnownDrivers.productOf("unknown.sys"))
        assertEquals("BattlEye", KnownDrivers.productOf("BEDaisy.sys"))
    }
}
