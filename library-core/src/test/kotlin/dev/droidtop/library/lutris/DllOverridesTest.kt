package dev.droidtop.library.lutris

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wine's own WINEDLLOVERRIDES string is the one form the prefix state
 * and the importer's preview share: the runtime reads the prefix back
 * through DllOverrides.parse and writes an import's overrides through
 * DllOverrides.format (DroidtopPcGameRuntime), so the two directions
 * must be each other's inverse on the shapes that really occur.
 */
class DllOverridesTest {

    @Test
    fun `parse reads Wine's own multi-dll form`() {
        assertEquals(
            mapOf("d3d11" to "n,b", "dxgi" to "n,b", "ddraw" to ""),
            DllOverrides.parse("d3d11,dxgi=n,b;ddraw="),
        )
    }

    @Test
    fun `parse trims whitespace and skips entries without a mode`() {
        assertEquals(mapOf("a" to "n"), DllOverrides.parse(" junk ; a = n "))
    }

    @Test
    fun `format and parse round trip through the one string form`() {
        val overrides = mapOf("d3d11" to "n", "mscms" to "b,n", "ddraw" to "")
        assertEquals("d3d11=n;mscms=b,n;ddraw=", DllOverrides.format(overrides))
        assertEquals(overrides, DllOverrides.parse(DllOverrides.format(overrides)))
    }
}
