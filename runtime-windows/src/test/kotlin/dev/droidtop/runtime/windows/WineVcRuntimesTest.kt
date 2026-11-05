package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineVcRuntimesTest {
    @Test
    fun `each runtime present gets its record in both views`() {
        val keys = WineVcRuntimes.keysFor(has64 = true, has32 = true)
        assertEquals(4, keys.size)
        assertTrue("Software\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64" in keys)
        assertTrue("Software\\Wow6432Node\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x86" in keys)
    }

    @Test
    fun `a runtime that is not in the prefix gets no record`() {
        assertEquals(
            listOf("Software\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64", "Software\\Wow6432Node\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64"),
            WineVcRuntimes.keysFor(has64 = true, has32 = false),
        )
        assertEquals(emptyList<String>(), WineVcRuntimes.keysFor(has64 = false, has32 = false))
    }
}
