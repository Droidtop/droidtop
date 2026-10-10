package dev.droidtop.library.lutris

import org.junit.Assert.assertEquals
import org.junit.Test

/** [componentLabel]: the ids the table knows read as words; any other id is shown as it is. */
class ComponentLabelTest {
    @Test
    fun `known component ids are named for a person`() {
        assertEquals("Direct3D extras (d3dx9, d3dx10, d3dx11, d3dcompiler)", componentLabel("direct3d"))
        assertEquals("DirectInput 8", componentLabel("directinput8"))
        assertEquals("DirectInput", componentLabel("directinput"))
        assertEquals("XAudio and XACT", componentLabel("xaudio"))
        assertEquals("Visual C++ 2010 runtime", componentLabel("vcrun2010"))
    }

    @Test
    fun `an id the table does not know is shown as it is`() {
        assertEquals("corefonts", componentLabel("corefonts"))
    }
}
