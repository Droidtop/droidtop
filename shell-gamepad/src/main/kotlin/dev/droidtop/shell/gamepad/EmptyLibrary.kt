package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The one empty state of a games place (Retro Games, PC Games, Home; docs/SPEC.md 7i, "An empty library";
 * Droidtop/tracker#533): a glyph, one line, and the two things that fill it, centred, the place's own action first
 * and Get games beside it. Neither button is drawn filled: with a filled primary and the focus ring on the other,
 * two buttons looked chosen at once. The cursor starts on the first; the ring is the only highlight.
 */
@Composable
internal fun EmptyLibrary(
    line: String,
    actionLabel: String,
    onAction: () -> Unit,
    // PC Games' Get games searches for PC games; Retro Games and Home search every source.
    pc: Boolean = false,
    onChanged: () -> Unit = {},
) {
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().padding(horizontal = LocalShellWindow.current.edgePadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.Lg)) {
            Icon(Icons.Outlined.SportsEsports, contentDescription = null, tint = MenuTokens.OnSurfaceMuted, modifier = Modifier.size(EMPTY_GLYPH))
            Text(line, color = MenuTokens.OnSurface, style = TypeRole.body, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.Md), verticalAlignment = Alignment.CenterVertically) {
                ShellChip(actionLabel, modifier = Modifier.focusRequester(first), onClick = onAction)
                GetGamesChip(pc = pc, onChanged = onChanged)
            }
        }
    }
    LaunchedEffect(Unit) { requestFocusWhenAttached(first, "Empty library") }
}

/** The empty state's glyph: large enough to read as the page's picture, not as a row's icon. */
private val EMPTY_GLYPH = 48.dp
