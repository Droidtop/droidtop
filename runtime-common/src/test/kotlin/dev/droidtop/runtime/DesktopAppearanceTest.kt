package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Linux desktop's look against droidtop's Light or dark (docs/SPEC.md 2b "Desktop appearance", Droidtop/tracker#694). */
class DesktopAppearanceTest {
    @Test
    fun `dark puts GTK 3 and libadwaita dark, light leaves Adwaita as it is`() {
        assertEquals("Adwaita:dark", DesktopAppearance.environment(dark = true)["GTK_THEME"])
        assertEquals("prefer-dark", DesktopAppearance.environment(dark = true)["ADW_DEBUG_COLOR_SCHEME"])
        assertFalse("GTK_THEME" in DesktopAppearance.environment(dark = false))
        assertEquals("prefer-light", DesktopAppearance.environment(dark = false)["ADW_DEBUG_COLOR_SCHEME"])
    }

    @Test
    fun `every program the desktop starts gets the look`() {
        val before = DesktopAppearance.dark
        try {
            DesktopAppearance.dark = true
            assertEquals("Adwaita:dark", ContainerLayout.clientEnvironment(null)["GTK_THEME"])
            DesktopAppearance.dark = false
            assertFalse("GTK_THEME" in ContainerLayout.clientEnvironment(null))
        } finally {
            DesktopAppearance.dark = before
        }
    }

    @Test
    fun `the background is a colour sway takes, droidtop's ground by default`() {
        assertEquals(DesktopAppearance.Background.MATCH, DesktopAppearance.Background.fromId(null))
        assertEquals(DesktopAppearance.Background.BLACK, DesktopAppearance.Background.fromId("black"))
        val config = DesktopPanel.swayConfig(background = DesktopAppearance.Background.MATCH.color(dark = true)).lines()
        assertTrue("output * bg #1E1F24 solid_color" in config)
        assertFalse(DesktopPanel.swayConfig().contains("output * bg"))
        // Only a colour goes into sway's config.
        assertFalse(DesktopPanel.swayConfig(background = "#123456; exec rm").contains("output"))
    }

    @Test
    fun `a picture fills the output over the colour`() {
        val config = DesktopPanel.swayConfig(background = "#1E1F24", picture = true).lines()
        assertTrue("output * bg /run/droidtop-app-storage/desktop-launcher/panel/background.png fill #1E1F24" in config)
        assertFalse(config.any { it.endsWith("solid_color") })
        assertEquals(DesktopAppearance.Background.PICTURE, DesktopAppearance.Background.fromId("picture"))
    }

    @Test
    fun `a picture whose file is gone falls back to the colour`() {
        val dir = java.nio.file.Files.createTempDirectory("panel").toFile()
        try {
            DesktopPanel.writeConfigs(dir, DesktopPanel.NONE, background = "#1E1F24", picture = true)
            val config = java.io.File(DesktopPanel.hostDir(dir), "sway.config").readText()
            assertTrue(config.contains("output * bg #1E1F24 solid_color"))
            assertFalse(config.contains("background.png"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
