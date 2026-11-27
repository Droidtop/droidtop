package dev.droidtop.shell.gamepad

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

/** One choice of a modal dialog ([DialogChoices]): its label, a short line under it (a date, what it will do), and whether it is one-way. */
class DialogChoice(val label: String, val detail: String? = null, val danger: Boolean = false)

/**
 * A modal's choices, Steam's dialog list (its power dialog, docs/SPEC.md 7k "Dialogs"): flat rows on
 * one plate, groups split by a thin dark rule ([DialogLook.RuleHeight]), the safe choices first and
 * the way out (Cancel, OK, Decide later) last in a group of its own, except when every other choice
 * is one-way: then the way out is the safe choice and comes first. The row under the cursor has a menu row's focus
 * (the accent behind it and the bar at its leading edge, mytop-project/tracker#644), so a dialog reads like the menu it
 * opened from; it was the solid black inversion, which on the rig sat oddly over the light menu it came from
 * (BlueStacks, 2026-11-27, mytop-project/tracker#967). [selected] counts rows across the groups,
 * in order; a tap chooses ([onChoose]). One component for every dialog, so they read alike.
 */
@Composable
fun DialogChoices(groups: List<List<DialogChoice>>, selected: Int, onChoose: (Int) -> Unit) {
    val window = LocalShellWindow.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Space.Sm)
            .clip(Corners.Crisp)
            .background(MenuTokens.Surface),
    ) {
        var index = 0
        groups.filter { it.isNotEmpty() }.forEachIndexed { g, group ->
            if (g > 0) Box(Modifier.fillMaxWidth().height(DialogLook.RuleHeight).background(MenuTokens.Scrim))
            group.forEach { choice ->
                val row = index++
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A row is a button here, so it is at least as big as a finger on a screen without a pad.
                        .heightIn(min = if (window.touchFirst) window.pressTarget else DialogLook.RowMinHeight)
                        .selectionFrame(row == selected, Corners.Crisp, rest = Color.Transparent, row = true)
                        .padRow { onChoose(row) }
                        .padding(horizontal = Space.Lg, vertical = Space.Sm),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        choice.label,
                        color = if (choice.danger) MenuTokens.NoticeDanger else MenuTokens.OnSurface,
                        style = TypeRole.body,
                    )
                    choice.detail?.let {
                        Text(it, color = MenuTokens.OnSurfaceMuted, style = TypeRole.supporting)
                    }
                }
            }
        }
    }
}
