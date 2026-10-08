package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The one strip above a library grid (docs/SPEC.md 7i and 7j): a tab pill
 * per view (Steam's tabs: small bold capitals with the view's count, the
 * shown one on a quiet plate) with L1 and R1 at its ends, and the active
 * filter as ONE pill at its end. PC Games and Apps both draw it; the page owns the shoulders
 * ([OwnShoulders]) and the strip only draws the glyphs.
 *
 * [active] is the view the list shows (-1 when none, e.g. on the shelves);
 * [focused] is the chip a page that moves its own cursor has on the strip
 * (null for a page whose strip is touch and L1/R1 only, as Apps). [pill] is
 * the text of a filter no view stands for, cleared by [onClearPill].
 */
@Composable
internal fun ViewStrip(
    labels: List<String>,
    active: Int,
    focused: Int?,
    pill: String?,
    onSelect: (Int) -> Unit,
    onClearPill: () -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
) {
    val window = LocalShellWindow.current
    val shoulderGlyphs = window.showsShoulderGlyphs()
    // The strip runs along the top edge, where the status cluster floats:
    // it ends short of the cluster so R1 is never under the clock
    // (Droidtop/tracker#292). The cluster's width already holds its margins.
    val clusterWidth = StatusClusterRoom.size.width
    val end = if (clusterWidth > 0.dp) clusterWidth else window.edgePadding
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = window.edgePadding, end = end),
    ) {
        // A shoulder with nowhere to go at this end is drawn at half strength (Steam's strip ends).
        if (shoulderGlyphs) ShoulderGlyph("L1", badge = true, dimmed = active <= 0, modifier = Modifier.padding(end = Space.Sm))
        LazyRow(
            state = state,
            contentPadding = PaddingValues(vertical = Space.Xs),
            horizontalArrangement = Arrangement.spacedBy(Space.Sm),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(count = labels.size, key = { "chip:$it" }) { index ->
                ShellChip(
                    labels[index],
                    on = active == index,
                    tab = true,
                    selected = focused == index,
                    onClick = { onSelect(index) },
                )
            }
        }
        pill?.let { text ->
            // Touch's one-press clear; the pad reaches the filters through X.
            // Never a D-pad stop.
            ShellChip(
                "$text  ✕",
                primary = true,
                selected = false,
                modifier = Modifier.padding(start = Space.Sm).widthIn(max = (window.widthDp * 0.4f).dp),
                onClick = onClearPill,
            )
        }
        if (shoulderGlyphs) {
            ShoulderGlyph("R1", badge = true, dimmed = active >= labels.size - 1, modifier = Modifier.padding(start = Space.Sm))
        }
    }
}
