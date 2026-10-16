package dev.droidtop.pluginhost

import dev.droidtop.library.theme.GamingThemeColors
import dev.droidtop.library.theme.GamingThemeMapping
import dev.droidtop.library.theme.RoleTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G7, theme tokens (docs/plugin-api.md 3, Droidtop/tracker#559): what `ui.get_theme` answers. */
class ThemeTokensApiTest {
    @Test
    fun `the answer names the theme and every token as RRGGBB`() {
        val dark = RoleTokens.toJson(RoleTokens.of(GamingThemeColors.DEFAULT))
        assertEquals("dark", dark.getString("theme"))
        val tokens = dark.getJSONObject("tokens")
        assertEquals(RoleTokens.NAMES.toSet(), tokens.keys().asSequence().toSet())
        RoleTokens.NAMES.forEach { assertTrue(it, tokens.getString(it).matches(Regex("#[0-9A-F]{6}"))) }
        assertEquals("light", RoleTokens.toJson(RoleTokens.of(GamingThemeMapping.defaultFor(light = true))).getString("theme"))
    }

    @Test
    fun `the op is served without a permission and named by the rule`() {
        val op = HostApis.find("ui", "get_theme")
        assertNotNull(op)
        assertNull(op!!.permission)
        assertNull(HostOpNames.check("get_theme", readOnly = true))
        assertEquals(PluginEvent.THEME_CHANGED, PluginEvent.fromId("host.theme_changed"))
    }
}
