package dev.droidtop.shell.gamepad

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.ui.graphics.vector.ImageVector

/** The glyph a left-menu row draws, from the same Material set (docs/SPEC.md 7j, "Gaming controls"). */
internal fun LeftMenuEntry.glyph(): ImageVector = when (section) {
    GamingSection.HOME -> Icons.Outlined.Home
    GamingSection.PC_GAMES -> Icons.Outlined.Laptop
    GamingSection.GAMES -> Icons.Outlined.SportsEsports
    GamingSection.APPS -> Icons.Outlined.Apps
    GamingSection.STORES -> Icons.Outlined.Storefront
    GamingSection.SOCIAL -> Icons.Outlined.Group
    GamingSection.DOWNLOADS -> Icons.Outlined.Download
    GamingSection.UPDATES -> Icons.Outlined.SystemUpdate
    GamingSection.NEW_GAMES -> Icons.Outlined.Explore
    GamingSection.PLUGINS -> Icons.Outlined.Extension
    GamingSection.SETTINGS -> Icons.Outlined.Settings
}
