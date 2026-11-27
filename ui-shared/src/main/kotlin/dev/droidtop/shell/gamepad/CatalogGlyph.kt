package dev.droidtop.shell.gamepad

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Group
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
 * see ui-shared/build.gradle.kts). One mapping, used everywhere a row
 * draws its category glyph (docs/SPEC.md 7k), so choosing an icon for a
 * catalog entry is a one-line decision in the catalog, not a per-screen
 * drawable pick.
 */
fun CatalogIcon.glyph(): ImageVector = when (this) {
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
    CatalogIcon.SOCIAL -> Icons.Outlined.Group
    CatalogIcon.DOWNLOADS -> Icons.Outlined.Download
}

/**
 * A plugin facet's icon name (docs/plugin-api.md 1.10, "Icons are names", `Facet.ICONS`) as droidtop's own glyph for it:
 * a plugin picks a meaning, droidtop draws it. Null for a name outside the set.
 */
fun facetGlyph(name: String?): ImageVector? = when (name) {
    "add" -> Icons.Outlined.Add
    "app" -> Icons.Outlined.Apps
    "battery" -> Icons.Outlined.BatteryFull
    "bluetooth" -> Icons.Outlined.Bluetooth
    "check" -> Icons.Outlined.Check
    "clipboard" -> Icons.Outlined.ContentPaste
    "close" -> Icons.Outlined.Close
    "cloud" -> Icons.Outlined.Cloud
    "download" -> Icons.Outlined.Download
    "error" -> Icons.Outlined.ErrorOutline
    "file" -> Icons.Outlined.InsertDriveFile
    "folder" -> Icons.Outlined.Folder
    "info" -> Icons.Outlined.Info
    "lock" -> Icons.Outlined.Lock
    "mic" -> Icons.Outlined.Mic
    "network" -> Icons.Outlined.Lan
    "next" -> Icons.Outlined.SkipNext
    "notification" -> Icons.Outlined.Notifications
    "open" -> Icons.AutoMirrored.Outlined.OpenInNew
    "pause" -> Icons.Outlined.Pause
    "play" -> Icons.Outlined.PlayArrow
    "previous" -> Icons.Outlined.SkipPrevious
    "remove" -> Icons.Outlined.Remove
    "search" -> Icons.Outlined.Search
    "send" -> Icons.AutoMirrored.Outlined.Send
    "settings" -> Icons.Outlined.Settings
    "share" -> Icons.Outlined.Share
    "star" -> Icons.Outlined.StarOutline
    "stop" -> Icons.Outlined.Stop
    "sync" -> Icons.Outlined.Sync
    "terminal" -> Icons.Outlined.Terminal
    "unlock" -> Icons.Outlined.LockOpen
    "upload" -> Icons.Outlined.Upload
    "volume" -> Icons.AutoMirrored.Outlined.VolumeUp
    "vpn" -> Icons.Outlined.VpnKey
    "warning" -> Icons.Outlined.WarningAmber
    "wifi" -> Icons.Outlined.Wifi
    "window" -> Icons.Outlined.WebAsset
    else -> null
}

/**
 * A catalog row's leading picture: [iconFile] (a store's cached mark, mytop-project/tracker#503) when there is one, drawn
 * by the image loader off the main thread and in its own colours; else [icon]'s glyph, tinted. Nothing for neither.
 */
@androidx.compose.runtime.Composable
fun CatalogGlyph(
    icon: CatalogIcon?,
    iconFile: String?,
    tint: androidx.compose.ui.graphics.Color,
    modifier: androidx.compose.ui.Modifier,
    glyphName: String? = null,
) {
    val named = if (icon == null && iconFile == null) facetGlyph(glyphName) else null
    when {
        iconFile != null -> coil3.compose.AsyncImage(
            // A file in droidtop's storage, or a picture's address (CatalogItem.iconFile), which Coil fetches and caches.
            model = if (iconFile.startsWith("https://") || iconFile.startsWith("http://")) iconFile else java.io.File(iconFile),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = modifier,
        )
        icon != null -> androidx.compose.material3.Icon(icon.glyph(), contentDescription = null, tint = tint, modifier = modifier)
        named != null -> androidx.compose.material3.Icon(named, contentDescription = null, tint = tint, modifier = modifier)
    }
}
