package dev.droidtop.app.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 10c privacy boundary of Share diagnostics: every real credential
 * key of the settings file (7h) must be left out of the shared export,
 * and no other setting may be, or the archive stops being either safe
 * or useful.
 */
class ShareDiagnosticsTest {

    @Test
    fun `every credential key the settings file really has is omitted`() {
        for (key in listOf(
            "droidtop_igdb_client_id",
            "droidtop_igdb_client_secret",
            "droidtop_screenscraper_devid",
            "droidtop_screenscraper_devpassword",
            "droidtop_screenscraper_ssid",
            "droidtop_screenscraper_sspassword",
            "droidtop_thegamesdb_apikey",
            "droidtop_steamgriddb_apikey",
        )) {
            assertTrue(key, ShareDiagnostics.isCredentialKey(key))
        }
    }

    @Test
    fun `no ordinary setting is omitted`() {
        for (key in listOf(
            "droidtop_active_theme",
            "droidtop_default_mode",
            "droidtop_games_root_paths",
            "droidtop_rom_scraper_source",
            "droidtop_scrape_videos",
            "droidtop_desktop_primary_image_id",
            "droidtop_custom_players_gba",
            "droidtop_alternative_launcher_target",
            "droidtop_mode_enabled_gaming",
            "pref_desktop_taskbar_top",
        )) {
            assertFalse(key, ShareDiagnostics.isCredentialKey(key))
        }
    }
}
