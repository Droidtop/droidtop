package dev.droidtop.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import dev.droidtop.app.settings.AppSettingsCatalogs
import dev.droidtop.library.consoles.ConsoleSystemDef
import dev.droidtop.library.EsDeArtwork
import dev.droidtop.library.consoles.RomDatabase
import dev.droidtop.library.consoles.GameMetadataEntity
import dev.droidtop.library.scraper.ScraperSource
import dev.droidtop.library.scraper.ScraperSourcePrefs
import dev.droidtop.library.scraper.ScreenScraperClient
import dev.droidtop.library.scraper.ScreenScraperPrefs
import dev.droidtop.library.scraper.ScreenScraperSystemIds
import dev.droidtop.library.scraper.TheGamesDbClient
import dev.droidtop.library.scraper.TheGamesDbPrefs
import dev.droidtop.library.scraper.TheGamesDbSystemIds
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.CatalogNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Console-systems management, rendered from the shared settings catalog
 * (docs/SPEC.md settings architecture): the actual screen DATA -- folder
 * list, per-folder system/player choices, scraping, platform CRUD, ROM
 * folders, scraper credentials -- lives in
 * [dev.droidtop.app.settings.AppSettingsCatalogs] and is chromed here by
 * the same [CatalogNavigator] the Handheld shell's own Settings section
 * uses, so reaching this from anywhere looks and drives exactly like the
 * rest of settings instead of a one-off hand-rolled screen (which this
 * used to be -- reported directly as "ew" on sight). This Activity is
 * just a host for entry points that aren't already inside a settings
 * surface.
 */
class ConsoleSystemsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettingsCatalogs.ensureRegistered()
        val screen = SettingsScreenRegistry.get(AppSettingsCatalogs.SCREEN_CONSOLE_SYSTEMS)!!
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    CatalogNavigator(root = screen, onExit = { finish() })
                }
            }
        }
    }
}

/**
 * Real ROM metadata/cover-art scrape -- uses exactly ONE scraper source,
 * matching real ES-DE's own actual architecture (confirmed against real
 * source, `es-app/src/scrapers/Scraper.cpp`): ES-DE has no automatic
 * multi-source fallback/priority chain at all, just a real single
 * user-selected source ([ScraperSourcePrefs], default "screenscraper",
 * ES-DE's own real default too). Both real scrapers ported directly from
 * real ES-DE source (see [ScreenScraperClient]/[TheGamesDbClient]'s own
 * doc comments) -- Lutris/IGDB are deliberately NOT used here: they're
 * droidtop's real scrapers for PC/Wine/Linux/engine games, a different
 * real content category from console ROMs.
 *
 * Persists BOTH the cover image (existing `downloaded_media` layout
 * [EsDeArtwork] already reads) AND real per-game metadata (via
 * [GameMetadataEntity]/[RomDao.upsertGameMetadata] -- see that entity's
 * own doc comment for why it's a separate, rescan-durable table). Skips a
 * ROM only when it already has BOTH real artwork AND real metadata,
 * matching a real "actually still missing something" check rather than
 * artwork alone. Internal: invoked by the settings catalog's per-folder
 * scrape action (AppSettingsCatalogs).
 */
