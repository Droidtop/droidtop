package dev.droidtop.shell.gamepad.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.kindLine
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole
import dev.droidtop.shell.gamepad.focusLift
import dev.droidtop.shell.gamepad.focusMarquee
import dev.droidtop.shell.gamepad.input.PadModality
import dev.droidtop.shell.gamepad.selectionFrame

/**
 * Steam's library capsule is 600x900, a 2:3 portrait, and every shelf and
 * grid in Steam Big Picture and on the Deck is built from it; box art
 * scraped for a PC game is the same shape. One shape for every capsule the
 * PC Games tab draws (docs/SPEC.md 7i).
 */
internal const val CAPSULE_ASPECT = 2f / 3f

/**
 * How wide a capsule is: a share of the window's HEIGHT, so a shelf and
 * the start of the next fit under the strip on the console's 432dp-tall
 * landscape window (about 120dp wide there, three rows of the Deck's own
 * proportion), and the same rule gives a phone held upright two columns.
 */
@Composable
internal fun capsuleWidth(): Dp {
    val window = LocalShellWindow.current
    return (window.heightDp * 0.28f).dp.coerceIn(104.dp, 176.dp)
}

/**
 * One game as the PC Games tab draws it: its box art, or the same plate
 * with its name when it has none, and its name under it. Nothing is
 * drawn over the art (owner, 2026-10-01: "I don't like the grid and
 * weird backing"): Steam draws no text on a capsule because the art is
 * the name, and a name a person has to read belongs beside it, not on a
 * dark plate across it.
 *
 * The capsule is not a focus target. The tab moves ONE selection through
 * its one `onPad` handler (docs/SPEC.md 6e) and tells each capsule whether
 * it is the selected one; a finger moves that same selection by tapping,
 * and a second tap on the selected capsule is the pad's A. Long-press is
 * the touch route to Y (the game's page), the convention every card in
 * this shell follows.
 */
@Composable
internal fun PcCapsule(
    entry: LibraryEntry,
    selected: Boolean,
    width: Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val ring = selected && PadModality.showsFocus
    // The one focus treatment (docs/SPEC.md "Gaming motion and focus"): the
    // capsule lifts and gains a shadow under the cursor and the rest sit
    // slightly dimmed; the title under it stays put so the row does not
    // reflow.
    val title = GameNaming.displayName(entry.title)
    Column(
        modifier = modifier
            .width(width)
            .pointerInput(entry.id) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            },
        verticalArrangement = Arrangement.spacedBy(Space.Xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CAPSULE_ASPECT)
                .focusLift(ring, shape)
                .selectionFrame(selected, shape, rest = MenuTokens.Card, restOutline = MenuTokens.CardOutline),
        ) {
            if (entry.artworkUri != null) {
                AsyncImage(
                    model = entry.artworkUri,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(shape),
                )
            } else {
                // No art: the plate carries the name, as the Deck does for
                // a non-Steam shortcut with no artwork. Never a made-up
                // cover (design language, 2026-09-17).
                Column(
                    modifier = Modifier.fillMaxSize().padding(Space.Md),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        title,
                        color = MenuTokens.OnSurface,
                        style = TypeRole.rowTitle,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        entry.kindLine(),
                        color = MenuTokens.OnSurfaceMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (entry.favorite) {
                Text(
                    "★",
                    color = MenuTokens.Favourite,
                    style = TypeRole.rowTitle,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Space.Sm),
                )
            }
            if (entry.availableUpdate != null) {
                // The one fact a capsule carries beyond its art: a newer
                // version exists (docs/SPEC.md 7g). A small affirmative
                // mark in the corner, never a plate over the art.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.Sm)
                        .background(MenuTokens.Affirmative, RoundedCornerShape(50))
                        .padding(horizontal = Space.Sm, vertical = Space.Hair),
                ) {
                    Text("Update", color = MenuTokens.OnSelected, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // The name under the art, one line, scrolling while selected (the
        // one place scrolling text is allowed, docs/SPEC.md 7k "Text in
        // rows and tiles"). Under a plate that already carries the name
        // it would say the same thing twice, so it is left out there.
        if (entry.artworkUri != null) {
            Text(
                title,
                color = if (selected) MenuTokens.OnSurface else MenuTokens.Value,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().focusMarquee(selected),
            )
        }
    }
}
