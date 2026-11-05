package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WineXAudioRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val broken = """
        |[Software\\Classes\\CLSID\\{5A508685-A254-4FBA-9B82-9A24B00306AF}\\InprocServer32] 1730000000
        |#time=1db0000000000000
        |@="C:\\windows\\system32\\xaudio2_7.dll"
        |"ThreadingModel"="Both"
        |
        |[Software\\Classes\\Wow6432Node\\CLSID\\{5A508685-A254-4FBA-9B82-9A24B00306AF}\\InprocServer32] 1730000000
        |#time=1db0000000000000
        |@="C:\\windows\\system32\\xapofx1_5.dll"
        |"ThreadingModel"="Both"
        |
        |[Software\\Classes\\Wow6432Node\\CLSID\\{c1e3f122-a2ea-442c-854f-20d98f8357a1}\\InprocServer32] 1730000000
        |@="C:\\windows\\system32\\xapofx1_1.dll"
        |
        |[Software\\Classes\\Wow6432Node\\CLSID\\{00000000-0000-0000-0000-000000000001}\\InprocServer32] 1730000000
        |@="C:\\windows\\system32\\xapofx1_5.dll"
        |""".trimMargin()

    @Test
    fun `an XAudio2 class registered to xapofx goes back to its own xaudio2 DLL, in either case of GUID`() {
        val (text, fixed) = WineXAudioRegistry.repair(broken)
        assertEquals(2, fixed)
        assertTrue(text.contains("[Software\\\\Classes\\\\Wow6432Node\\\\CLSID\\\\{5A508685-A254-4FBA-9B82-9A24B00306AF}\\\\InprocServer32] 1730000000\n#time=1db0000000000000\n@=\"C:\\\\windows\\\\system32\\\\xaudio2_7.dll\""))
        assertTrue(text.contains("{c1e3f122-a2ea-442c-854f-20d98f8357a1}\\\\InprocServer32] 1730000000\n@=\"C:\\\\windows\\\\system32\\\\xaudio2_1.dll\""))
    }

    @Test
    fun `other classes and the rest of the file stay as they were`() {
        val (text, _) = WineXAudioRegistry.repair(broken)
        assertTrue(text.endsWith("{00000000-0000-0000-0000-000000000001}\\\\InprocServer32] 1730000000\n@=\"C:\\\\windows\\\\system32\\\\xapofx1_5.dll\"\n"))
        assertEquals(broken.length, text.length) // xapofx1_N.dll and xaudio2_N.dll are the same length
    }

    @Test
    fun `a prefix that needs nothing is not rewritten`() {
        val reg = tmp.newFile("system.reg").apply { writeText("[Software\\\\Wine] 1\n@=\"x\"\n") }
        val before = reg.lastModified()
        assertEquals(0, WineXAudioRegistry.repair(reg))
        assertEquals(before, reg.lastModified())
        assertFalse(tmp.root.resolve("system.reg.xaudio").exists())
    }

    @Test
    fun `a broken prefix's file is repaired in place`() {
        val reg = tmp.newFile("system.reg").apply { writeText(broken) }
        assertEquals(2, WineXAudioRegistry.repair(reg))
        assertEquals(0, WineXAudioRegistry.repair(reg.readText()).second)
    }
}
