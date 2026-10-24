package dev.droidtop.ui.shared

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.JobLook
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.StatusMark

/**
 * The host's status glyphs, one vocabulary for every list (Droidtop/tracker#644, docs/DESIGN-LANGUAGE.md: a status is a
 * glyph, each tone with its own shape so colour is never the only signal): working is a turning ring in the accent,
 * attention a circle with "!" in the caution ink, error an octagon with "!" in the danger ink. [accent] is the
 * surface's own.
 */
@Composable
fun StatusGlyph(mark: StatusMark, accent: Color, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val palette = LocalNoticePalette.current
    when (mark) {
        StatusMark.WORKING -> JobRing(
            -1f,
            JobLook.CHECKING,
            JobProgressColors(track = accent.copy(alpha = 0.25f), fill = accent, done = accent, failed = palette.ink(NoticeTone.Danger)),
            modifier,
            size = size,
        )
        StatusMark.ATTENTION -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Needs you", tint = palette.ink(NoticeTone.Caution), modifier = modifier.size(size))
        StatusMark.ERROR -> Icon(Icons.Outlined.Report, contentDescription = "Cannot run", tint = palette.ink(NoticeTone.Danger), modifier = modifier.size(size))
    }
}
