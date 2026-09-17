package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.LibraryEntry
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap

/**
 * One game in the PC grid, whatever found it.
 *
 * A opens the game rather than launching it: a PC game's runner may be a
 * named setup action away, and the detail screen is where that is said.
 * A card that launched straight into a black screen would be the exact
 * dishonesty §7i exists to remove.
 *
 * The card carries the game's source, its install state and its
 * compatibility line when reports exist. Compatibility is evidence, never
 * a verdict and never a gate (directed 2026-09-01): it is shown and it
 * changes nothing about ordering or availability.
 */
@Composable
internal fun PcGameCard(
    entry: LibraryEntry,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val pc = entry.pcInfo
    Box(
        modifier = modifier
            .size(width = 220.dp, height = 260.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            // Ahead of the focus targets, not after them: see [GameCard].
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                if (GamepadKeyMap.actionFor(event.key) == GamepadAction.A) {
                    onOpen()
                    true
                } else {
                    false
                }
            }
            .focusable()
            .clickable(onClick = onOpen)
            // The shell's ONE selection idiom (MenuTokens), the same
            // ring the menus, the game cards and the app tiles draw. This
            // card kept its own white rectangle over a hand-picked grey,
            // which read as a different kind of selection on the surface
            // a phone user spends most of their time in (rig, build 546).
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) MenuTokens.Accent else Color(0x1FFFFFFF),
                shape = RoundedCornerShape(12.dp),
            )
            .background(
                if (focused) MenuTokens.SurfaceSelected else MenuTokens.Surface,
                RoundedCornerShape(12.dp),
            ),
    ) {
        if (entry.artworkUri != null) {
            AsyncImage(
                model = entry.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                entry.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    // A game the walk no longer finds says ONLY that: where
                    // it came from and what engine it is are facts about a
                    // folder that is not there, and reading them beside the
                    // name would say the game is here (docs/SPEC.md 7g).
                    if (entry.missing) {
                        append("broken - missing")
                    } else {
                        append(entry.sourceLabel())
                        entry.engineLabel()?.let { append(" - ").append(it) }
                        if (pc?.installed == false) append(" - not installed")
                    }
                },
                color = if (entry.missing) MenuTokens.Danger else Color.LightGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            pc?.compatibility?.takeIf { it.hasBeenTried }?.let {
                Text(it.summary(), color = Color(0xFFB0BEC5), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
