package dev.droidtop.app.settings

import dev.droidtop.pluginhost.PluginConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A plugin's approval list names the mode a point shows in, and never the internal placeholder (Droidtop/tracker#512). */
class PluginPlacementTest {
    @Test
    fun `a point with a mode shows it and a point with no particular place shows nothing`() {
        assertEquals("In Gaming", AppSettingsCatalogs.placementWords("Gaming"))
        assertEquals("In Mobile", AppSettingsCatalogs.placementWords("Mobile"))
        assertEquals("In Desktop", AppSettingsCatalogs.placementWords("Desktop"))
        assertNull(AppSettingsCatalogs.placementWords(PluginConsent.MODE_ANY))
    }
}
