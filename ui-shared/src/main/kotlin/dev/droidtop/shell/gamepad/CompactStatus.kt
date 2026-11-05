package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import dev.droidtop.library.settings.JobIcon

/**
 * The compact status (owner, 2026-11-05, Droidtop/tracker#886; docs/DESIGN-LANGUAGE.md "Status is an icon and a
 * value"): what a job does as an icon, its words for a screen reader.
 */
@Composable
fun JobIconGlyph(icon: JobIcon, tint: Color, modifier: Modifier = Modifier) {
    Icon(vectorOf(icon), contentDescription = icon.words, tint = tint, modifier = modifier)
}

/**
 * A status value ("1.2/3.4 GB", "12/40", "42%") in a box as wide as the widest value of its shape, right-aligned, so
 * the bar beside it keeps its length and nothing in the row moves while it counts. [style] should carry tabular
 * figures.
 */
@Composable
fun CompactValue(value: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val template = compactTemplate(value)
    val width = remember(template, style, density) { with(density) { measurer.measure(template, style).size.width.toDp() } }
    Box(modifier.width(width), contentAlignment = Alignment.CenterEnd) {
        Text(value, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * The widest value of [value]'s shape, which its box is sized to: a size fraction reserves "8888 MB/888.8 GB", a count
 * fraction or a percent its own widest digits. Pure.
 */
internal fun compactTemplate(value: String): String = when {
    value.endsWith("%") -> "100%"
    value.any { it.isLetter() } -> "8888 MB/888.8 GB"
    '/' in value -> value.substringAfter('/').let { total -> "${"8".repeat(total.length)}/${"8".repeat(total.length)}" }
    else -> "8".repeat(value.length)
}

private fun vectorOf(icon: JobIcon): ImageVector = when (icon) {
    JobIcon.DOWNLOAD -> Icons.Outlined.Download
    JobIcon.UPLOAD -> Icons.Outlined.Upload
    JobIcon.INSTALL -> Icons.Outlined.InstallMobile
    JobIcon.UPDATE -> Icons.Outlined.SystemUpdate
    JobIcon.VERIFY -> Icons.Outlined.FactCheck
    JobIcon.SYNC -> Icons.Outlined.Sync
    JobIcon.SCAN -> Icons.Outlined.FindInPage
    JobIcon.WORK -> Icons.Outlined.HourglassTop
}
