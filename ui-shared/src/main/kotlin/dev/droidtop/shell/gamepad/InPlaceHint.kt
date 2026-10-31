package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The one in-place hint (docs/DESIGN-LANGUAGE.md, "Guidance"; Droidtop/tracker#829): one short line saying how to do
 * something, in a small pill where it is done, with "Got it" ([onDismiss]) as its one touch target. It never takes the
 * cursor or the screen: the rest of the surface works around it. Whether it shows is [dev.droidtop.library.settings.Hints]'.
 */
@Composable
fun InPlaceHint(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .widthIn(max = 520.dp)
            .background(MenuTokens.Card, HintShape)
            .padding(start = 16.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        Text(
            "Got it",
            color = MenuTokens.Accent,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padRow(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 14.dp),
        )
    }
}

/**
 * The circle a hint draws around the on-screen control it is about (the owner, 2026-10-30: "Draw a circle around
 * on-screen controls when needed"): an accent ring just outside the control while [shown], nothing otherwise.
 */
fun Modifier.hintRing(shown: Boolean): Modifier = if (!shown) this else drawWithContent {
    drawContent()
    val stroke = 3.dp.toPx()
    val out = 6.dp.toPx()
    drawRoundRect(
        color = MenuTokens.Accent,
        topLeft = Offset(-out, -out),
        size = Size(size.width + out * 2, size.height + out * 2),
        cornerRadius = CornerRadius(minOf(size.width, size.height) / 2 + out),
        style = Stroke(stroke),
    )
}

private val HintShape = RoundedCornerShape(24.dp)
