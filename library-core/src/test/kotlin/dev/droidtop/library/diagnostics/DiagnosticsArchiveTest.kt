package dev.droidtop.library.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsArchiveTest {
    @Test
    fun everyCredentialKeyDroidtopStoresIsLeftOut() {
        listOf(
            "droidtop_screenscraper_devpassword",
            "droidtop_screenscraper_sspassword",
            "droidtop_screenscraper_ssid",
            "droidtop_screenscraper_devid",
            "droidtop_igdb_client_id",
            "droidtop_igdb_client_secret",
            "droidtop_thegamesdb_apikey",
            "droidtop_steamgriddb_apikey",
            "droidtop_github_token",
        ).forEach { assertTrue(it, DiagnosticsArchive.isCredentialKey(it)) }
    }

    @Test
    fun ordinarySettingsStay() {
        listOf(
            "droidtop_games_root_paths",
            "droidtop_active_theme",
            "droidtop_scrape_filter",
            "droidtop_pc_scraper_source",
            "pref_platform_database_base_url",
            "droidtop_mode_enabled_gaming",
        ).forEach { assertFalse(it, DiagnosticsArchive.isCredentialKey(it)) }
    }
}
