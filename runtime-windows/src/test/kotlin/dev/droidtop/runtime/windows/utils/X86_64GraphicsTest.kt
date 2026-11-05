package dev.droidtop.runtime.windows.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class X86_64GraphicsTest {
    @Test
    fun `lavapipe gets one DXVK compiler thread unless a count is already set`() {
        assertEquals("dxvk.numCompilerThreads = 1", X86_64Graphics.withOneCompilerThread(null))
        assertEquals("dxvk.hud = fps; dxvk.numCompilerThreads = 1", X86_64Graphics.withOneCompilerThread("dxvk.hud = fps;"))
        assertEquals("dxvk.numCompilerThreads = 4", X86_64Graphics.withOneCompilerThread("dxvk.numCompilerThreads = 4"))
    }

    @Test
    fun `the setting goes inside the quotes DXVKHelper writes`() {
        assertEquals(
            "\"dxgi.maxDeviceMemory = 4096; dxvk.numCompilerThreads = 1\"",
            X86_64Graphics.withOneCompilerThread("\"dxgi.maxDeviceMemory = 4096\n\""),
        )
        assertEquals("\"; dxvk.numCompilerThreads = 1\"", X86_64Graphics.withOneCompilerThread("\"\""))
    }
}
