package dev.droidtop.shell.gamepad

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.droidtop.library.settings.ChoiceItem
import dev.droidtop.shell.gamepad.input.GamepadAction

/**
 * A short choice's options in a small pop-up over the page, where a tap on its row opens them (owner, 2026-11-05,
 * Droidtop/tracker#673, option 1: "A tap opens a small pop-up over the row, never a full page, and never changes the
 * value by itself"). The list is the dialogs' one component ([DialogChoices]), the current option under the cursor;
 * a tap or A on an option picks it, B or a tap outside leaves the value as it was. A choice of text sizes
 * ([ChoiceItem.sampleScale]) shows a sample line at the option under the cursor. Long choices keep the picker.
 */
@Composable
internal fun CatalogChoicePopup(item: ChoiceItem, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val options = item.options
    var selected by remember { mutableIntStateOf(options.indexOfFirst { it.value == item.current }.coerceAtLeast(0)) }
    val groups = remember(options) { listOf(options.map { DialogChoice(it.label) }) }
    val window = LocalShellWindow.current
    ShellDialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(360.dp)),
            focusLabel = item.title,
            title = item.title,
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, options.size, press)
                    GamepadAction.A -> options.getOrNull(selected)?.let { onPick(it.value) }
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            item.sampleScale?.let { scaleOf ->
                val shown = options.getOrNull(selected)?.value?.let(scaleOf)
                val now = item.current?.let(scaleOf)
                if (shown != null) ChoiceSample(sampleRatio(shown, now), TypeRole.body)
            }
            DialogChoices(groups, selected) { index ->
                selected = index
                options.getOrNull(index)?.let { onPick(it.value) }
            }
        }
    }
}

/** The sample line for a text size, [ratio] times the size droidtop's text is drawn at now. */
@Composable
internal fun ChoiceSample(ratio: Float, base: TextStyle) {
    Text(
        SAMPLE_TEXT,
        style = base.copy(
            fontSize = if (base.fontSize.isSpecified) base.fontSize * ratio else base.fontSize,
            lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * ratio else base.lineHeight,
        ),
        color = MenuTokens.OnSurface,
    )
}

/** How much bigger [shown] draws than what is in force now ([now]); 1 when nothing is in force. Pure. */
internal fun sampleRatio(shown: Float, now: Float?): Float =
    if (now == null || now <= 0f) 1f else (shown / now).coerceIn(0.5f, 3f)

internal const val SAMPLE_TEXT = "The quick brown fox jumps over the lazy dog"
