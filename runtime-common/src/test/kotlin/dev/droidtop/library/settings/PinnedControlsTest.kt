package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's pins and status line (PinnedControls.kt). */
class PinnedControlsTest {
    private val withoutProvider = setOf(
        GamingSettingsCatalog.ID_SYSTEM_VOLUME,
        GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS,
        GamingSettingsCatalog.ID_SYSTEM_NETWORK,
        GamingSettingsCatalog.ID_SYSTEM_DND,
        GamingSettingsCatalog.ID_SYSTEM_MIC_MUTE,
        GamingSettingsCatalog.ID_SYSTEM_BLUETOOTH,
    )

    @Test
    fun `the default pins draw in order`() {
        assertEquals(PinnedControls.DEFAULT, PinnedControls.visible(PinnedControls.DEFAULT, UiMode.FULL, withoutProvider))
    }

    @Test
    fun `a pin that needs the helper app is not drawn without it`() {
        val pins = PinnedControls.DEFAULT + GamingSettingsCatalog.ID_SYSTEM_WIFI + GamingSettingsCatalog.ID_SYSTEM_SLEEP
        val shown = PinnedControls.visible(pins, UiMode.FULL, withoutProvider)
        assertFalse(GamingSettingsCatalog.ID_SYSTEM_WIFI in shown)
        assertFalse(GamingSettingsCatalog.ID_SYSTEM_SLEEP in shown)
        assertTrue(GamingSettingsCatalog.ID_SYSTEM_WIFI in PinnedControls.visible(pins, UiMode.FULL, withoutProvider + GamingSettingsCatalog.ID_SYSTEM_WIFI))
    }

    @Test
    fun `a pin whose grant is missing shows its grant row`() {
        val available = withoutProvider - GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS + GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS_GRANT
        assertEquals(
            GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS_GRANT,
            PinnedControls.visible(PinnedControls.DEFAULT, UiMode.FULL, available)[1],
        )
    }

    @Test
    fun `Kid and Kiosk keep only volume and brightness`() {
        val expected = listOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME, GamingSettingsCatalog.ID_SYSTEM_BRIGHTNESS)
        assertEquals(expected, PinnedControls.visible(PinnedControls.DEFAULT, UiMode.KID, withoutProvider))
        assertEquals(expected, PinnedControls.visible(PinnedControls.DEFAULT, UiMode.KIOSK, withoutProvider))
    }

    @Test
    fun `the provider line shows only without a provider, outside Kid and Kiosk, until dismissed`() {
        assertTrue(PinnedControls.showsProviderLine(hasProvider = false, mode = UiMode.FULL, dismissed = false))
        assertFalse(PinnedControls.showsProviderLine(hasProvider = true, mode = UiMode.FULL, dismissed = false))
        assertFalse(PinnedControls.showsProviderLine(hasProvider = false, mode = UiMode.KID, dismissed = false))
        assertFalse(PinnedControls.showsProviderLine(hasProvider = false, mode = UiMode.FULL, dismissed = true))
    }

    @Test
    fun `active recordings read as Mic in use, each indicator its own words`() {
        assertEquals(listOf("Mic in use"), StatusIndicators.lines(micMuted = false, vpn = false, activeRecordings = 1, camerasInUse = 0))
        assertEquals(
            listOf("Mic muted", "VPN", "Mic in use", "Camera in use"),
            StatusIndicators.lines(micMuted = true, vpn = true, activeRecordings = 2, camerasInUse = 1),
        )
        assertTrue(StatusIndicators.lines(false, false, 0, 0).isEmpty())
    }

    @Test
    fun `stat tiles pin like controls, and only the clock runs the sampler`() {
        val pins = listOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME, PinnedControls.STAT_TEMPERATURE, PinnedControls.STAT_CLOCK)
        assertEquals(pins, PinnedControls.visible(pins, UiMode.FULL, withoutProvider))
        assertEquals(listOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME), PinnedControls.visible(pins, UiMode.KID, withoutProvider))
        assertTrue(PinnedControls.needsSampler(pins))
        assertFalse(PinnedControls.needsSampler(listOf(PinnedControls.STAT_TEMPERATURE, PinnedControls.STAT_WATTS)))
        assertFalse(PinnedControls.needsSampler(PinnedControls.visible(pins, UiMode.KID, withoutProvider)))
    }
}
