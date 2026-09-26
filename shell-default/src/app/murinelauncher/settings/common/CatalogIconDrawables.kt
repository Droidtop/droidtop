package app.murinelauncher.settings.common

import androidx.annotation.DrawableRes
import com.android.launcher3.R
import dev.droidtop.library.settings.CatalogIcon

/**
 * [CatalogIcon] -> a real drawable resource, the Preference/touch
 * surface's half of docs/SPEC.md 7k's shared row language. The Gaming
 * shell's `CatalogIconGlyphs.kt` maps the same enum to a Compose
 * `material-icons-extended` glyph; this is the same Material Symbols
 * Outlined family (google/material-design-icons, Apache-2.0), fetched as
 * real Android `<vector>` resources (`res/drawable/ic_catalog_*.xml`,
 * `_24px` "regular weight, no fill" variant) rather than added as a
 * second Compose dependency to this forked launcher3 tree just to
 * rasterize one glyph per row. Same icon choice per category as
 * `CatalogIconGlyphs.glyph()`, so a setting reads the same shape on
 * either surface -- `THEME` and `CONTROLLER` intentionally share their
 * sibling's drawable, exactly as they share a glyph there.
 */
@DrawableRes
internal fun CatalogIcon.drawableRes(): Int = when (this) {
    CatalogIcon.GLOBAL -> R.drawable.ic_catalog_global
    CatalogIcon.MODES -> R.drawable.ic_catalog_modes
    CatalogIcon.DATA -> R.drawable.ic_catalog_data
    CatalogIcon.HOME_ROLE -> R.drawable.ic_catalog_home_role
    CatalogIcon.GAMING -> R.drawable.ic_catalog_gaming
    CatalogIcon.DESKTOP -> R.drawable.ic_catalog_desktop
    CatalogIcon.STANDARD -> R.drawable.ic_catalog_standard
    CatalogIcon.LIBRARY -> R.drawable.ic_catalog_library
    CatalogIcon.SCRAPER -> R.drawable.ic_catalog_scraper
    CatalogIcon.CONSOLE_SYSTEMS -> R.drawable.ic_catalog_console_systems
    CatalogIcon.GAME_FOLDERS -> R.drawable.ic_catalog_game_folders
    CatalogIcon.PLATFORMS -> R.drawable.ic_catalog_platforms
    CatalogIcon.ORPHANED_MEDIA -> R.drawable.ic_catalog_orphaned_media
    CatalogIcon.WINDOWS_GAMES -> R.drawable.ic_catalog_windows_games
    CatalogIcon.CONTAINERS -> R.drawable.ic_catalog_containers
    CatalogIcon.ENGINEHOST -> R.drawable.ic_catalog_enginehost
    CatalogIcon.APPEARANCE -> R.drawable.ic_catalog_appearance
    CatalogIcon.THEME -> R.drawable.ic_catalog_appearance
    CatalogIcon.SCREENSAVER -> R.drawable.ic_catalog_screensaver
    CatalogIcon.INPUT -> R.drawable.ic_catalog_input
    CatalogIcon.CONTROLLER -> R.drawable.ic_catalog_input
    CatalogIcon.KEYBOARD -> R.drawable.ic_catalog_keyboard
    CatalogIcon.DISPLAY -> R.drawable.ic_catalog_display
    CatalogIcon.SYSTEM_UPDATES -> R.drawable.ic_catalog_system_updates
    CatalogIcon.ANDROID_SETTINGS -> R.drawable.ic_catalog_android_settings
    CatalogIcon.INTEGRATIONS -> R.drawable.ic_catalog_integrations
    CatalogIcon.SEARCH -> R.drawable.ic_catalog_search
}
