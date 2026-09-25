package dev.droidtop.shell.gamepad

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import dev.droidtop.library.settings.CatalogIcon

/**
 * [CatalogIcon] -> a real Material Symbols glyph (`material-icons-extended`,
 * pinned by the Compose BOM like every other Compose dependency here --
 * see shell-gamepad/build.gradle.kts). One mapping, used everywhere a row
 * draws its category glyph (docs/SPEC.md 7k), so choosing an icon for a
 * catalog entry is a one-line decision in the catalog, not a per-screen
 * drawable pick.
 */
internal fun CatalogIcon.glyph(): ImageVector = when (this) {
    CatalogIcon.GLOBAL -> Icons.Outlined.Public
    CatalogIcon.MODES -> Icons.Outlined.SwapHoriz
    CatalogIcon.DATA -> Icons.Outlined.Save
    CatalogIcon.HOME_ROLE -> Icons.Outlined.Home
    CatalogIcon.GAMING -> Icons.Outlined.SportsEsports
    CatalogIcon.DESKTOP -> Icons.Outlined.DesktopWindows
    CatalogIcon.STANDARD -> Icons.Outlined.Apps
    CatalogIcon.LIBRARY -> Icons.Outlined.VideogameAsset
    CatalogIcon.SCRAPER -> Icons.Outlined.Image
    CatalogIcon.CONSOLE_SYSTEMS -> Icons.Outlined.Dashboard
    CatalogIcon.GAME_FOLDERS -> Icons.Outlined.Folder
    CatalogIcon.PLATFORMS -> Icons.Outlined.Category
    CatalogIcon.ORPHANED_MEDIA -> Icons.Outlined.DeleteSweep
    CatalogIcon.WINDOWS_GAMES -> Icons.Outlined.Laptop
    CatalogIcon.CONTAINERS -> Icons.Outlined.Dns
    CatalogIcon.ENGINEHOST -> Icons.Outlined.Memory
    CatalogIcon.APPEARANCE -> Icons.Outlined.Palette
    CatalogIcon.THEME -> Icons.Outlined.Palette
    CatalogIcon.SCREENSAVER -> Icons.Outlined.Wallpaper
    CatalogIcon.INPUT -> Icons.Outlined.Gamepad
    CatalogIcon.CONTROLLER -> Icons.Outlined.Gamepad
    CatalogIcon.KEYBOARD -> Icons.Outlined.Keyboard
    CatalogIcon.DISPLAY -> Icons.Outlined.Tv
    CatalogIcon.SYSTEM_UPDATES -> Icons.Outlined.SystemUpdate
    CatalogIcon.ANDROID_SETTINGS -> Icons.Outlined.Android
    CatalogIcon.INTEGRATIONS -> Icons.Outlined.Extension
    CatalogIcon.SEARCH -> Icons.Outlined.Search
}
