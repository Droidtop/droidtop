package dev.droidtop.app

import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.PinnedControls
import dev.droidtop.library.settings.UiMode
import dev.droidtop.runtime.tasks.AppsInsights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Apps tab's row line and app pins (CompanionTasks.kt, CompanionAppPins.kt; slice C20). */
class CompanionAppsTest {
    @Test fun `each row says its figure, recent data use and permissions`() {
        assertEquals("12% processor · Used data lately · Camera", appFacts(AppsInsights.Sort.CPU, 12.4, usedData = true, permissions = listOf("Camera")))
        assertEquals("180 MB", appFacts(AppsInsights.Sort.MEMORY, 184_320.0, usedData = false, permissions = emptyList()))
        assertEquals("24 MB data", appFacts(AppsInsights.Sort.DATA, 24_000_000.0, usedData = true, permissions = emptyList()))
        assertNull(appFacts(AppsInsights.Sort.RECENT, null, usedData = false, permissions = emptyList()))
    }

    @Test fun `app and shortcut pins`() {
        assertEquals("com.example.browser" to "new_tab", AppPins.parseShortcut(AppPins.shortcutId("com.example.browser", "new_tab")))
        assertNull(AppPins.parseShortcut("app:com.example.browser"))
        val browser = PinnedControls.appId("com.example.browser")
        val pins = listOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME, browser)
        // Drawn while installed (offered), never in Kid.
        assertEquals(pins, PinnedControls.visible(pins, UiMode.FULL, setOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME, browser)))
        assertEquals(listOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME), PinnedControls.visible(pins, UiMode.KID, setOf(GamingSettingsCatalog.ID_SYSTEM_VOLUME, browser)))
    }
}
