package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WineIcuTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun build(vararg dirs: String): File {
        val lib = tmp.newFolder("lib", "wine")
        for (dir in dirs) WineIcu.DLLS.forEach { File(lib, "$dir/$it").apply { parentFile.mkdirs(); writeText("$dir/$it") } }
        return lib
    }

    @Test
    fun `a prefix without ICU gets each architecture's DLLs in its own folder`() {
        val lib = build("x86_64-windows", "i386-windows")
        val windows = tmp.newFolder("windows").apply { File(this, "system32").mkdirs(); File(this, "syswow64").mkdirs() }
        assertTrue(WineIcu.ensure(lib, windows, arm64ec = false, win64 = true))
        assertEquals("x86_64-windows/icuuc68.dll", File(windows, "system32/icuuc68.dll").readText())
        assertEquals("i386-windows/icudt68.dll", File(windows, "syswow64/icudt68.dll").readText())
    }

    @Test
    fun `arm64ec takes its 64-bit DLLs from aarch64-windows`() {
        val lib = build("aarch64-windows", "i386-windows")
        val windows = tmp.newFolder("windows").apply { File(this, "system32").mkdirs(); File(this, "syswow64").mkdirs() }
        assertTrue(WineIcu.ensure(lib, windows, arm64ec = true, win64 = true))
        assertEquals("aarch64-windows/icuin68.dll", File(windows, "system32/icuin68.dll").readText())
    }

    @Test
    fun `a file already in the prefix is kept`() {
        val lib = build("x86_64-windows", "i386-windows")
        val windows = tmp.newFolder("windows")
        val own = File(windows, "system32/icuuc68.dll").apply { parentFile.mkdirs(); writeText("the prefix's own") }
        File(windows, "syswow64").mkdirs()
        assertTrue(WineIcu.ensure(lib, windows, arm64ec = false, win64 = true))
        assertEquals("the prefix's own", own.readText())
    }

    @Test
    fun `a Wine build without ICU leaves the prefix incomplete`() {
        val lib = tmp.newFolder("lib", "wine")
        val windows = tmp.newFolder("windows").apply { File(this, "system32").mkdirs(); File(this, "syswow64").mkdirs() }
        assertFalse(WineIcu.ensure(lib, windows, arm64ec = false, win64 = true))
        assertFalse(File(windows, "system32/icuuc68.dll").exists())
    }

    @Test
    fun `a 32-bit build puts its DLLs in system32`() {
        val lib = build("i386-windows")
        val windows = tmp.newFolder("windows").apply { File(this, "system32").mkdirs() }
        assertTrue(WineIcu.ensure(lib, windows, arm64ec = false, win64 = false))
        assertEquals("i386-windows/icuuc68.dll", File(windows, "system32/icuuc68.dll").readText())
    }
}
