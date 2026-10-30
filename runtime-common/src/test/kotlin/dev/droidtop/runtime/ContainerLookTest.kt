package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A container's scale and theme (docs/SPEC.md 3d "Scale and theme", Droidtop/tracker#787 slice 5). */
class ContainerLookTest {
    @Test
    fun `it reads back as written, and anything else is the default`() {
        val look = ContainerLook(scale = 1.5f, followTheme = false)
        assertEquals(look, ContainerLook.decode(look.encode()))
        assertEquals(ContainerLook(), ContainerLook.decode(""))
        assertEquals(ContainerLook(), ContainerLook.decode("scale=3.7\ntheme=maybe"))
    }

    @Test
    fun `as the desktop sets no scale, and any other scale sets Qt's and GTK's`() {
        assertEquals(emptyMap<String, String>(), ContainerLayout.scaleEnvironment(1f))
        assertEquals(mapOf("QT_SCALE_FACTOR" to "1.25", "GDK_DPI_SCALE" to "1.25"), ContainerLayout.scaleEnvironment(1.25f))
    }

    @Test
    fun `a container that keeps its image's look gets no theme`() {
        val follows = ContainerLayout.clientEnvironment(null)
        val own = ContainerLayout.clientEnvironment(null, look = ContainerLook(followTheme = false))
        assertTrue("ADW_DEBUG_COLOR_SCHEME" in follows)
        assertFalse("ADW_DEBUG_COLOR_SCHEME" in own)
        assertFalse("GTK_THEME" in own)
    }
}
