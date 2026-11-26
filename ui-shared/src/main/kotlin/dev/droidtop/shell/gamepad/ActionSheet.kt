package dev.droidtop.shell.gamepad

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding

/** What an [ActionSheet] lists: its rows, and the headings and notes between them that the cursor passes over. */
sealed interface ActionSheetItem

/**
 * One row of an [ActionSheet]: what it is called, a line under it when the name needs one, and what A on it does;
 * null where the row states something and has nothing to do (a version already open). [danger] draws a row that
 * loses something in the danger look (docs/SPEC.md 7k).
 */
class ActionRow(
    val title: String,
    val detail: String? = null,
    val danger: Boolean = false,
    /** A job this row stands for: its bar under the title, as on every job row (show, then say: mytop-project/tracker#738). */
    val progress: Float? = null,
    val look: dev.droidtop.library.settings.JobLook? = dev.droidtop.library.settings.JobLook.of(progress),
    /** An option the row turns on or off: its state as a switch in the value column. Null: not an option. */
    val switchOn: Boolean? = null,
    // Last, so a row's action can follow it as a trailing lambda.
    val onSelect: (() -> Unit)?,
) : ActionSheetItem

/**
 * A row whose value is typed in the row itself (owner, 2026-11-19, mytop-project/tracker#954 item 12: no editor and no
 * panel): it shows [title] and [value] ([hint] while blank); A or a tap turns it into an [InPlaceTextField], and when
 * the person is done [onDone] gets the text, with the sheet still open.
 */
class ActionTextRow(
    val title: String,
    val value: String,
    val hint: String = "Type here",
    val detail: String? = null,
    val secret: Boolean = false,
    val search: Boolean = false,
    val onDone: (String) -> Unit,
) : ActionSheetItem

/** A heading over the rows that follow it, named in the section-label style. */
class ActionHeading(val title: String) : ActionSheetItem

/** A line of text between rows (a status, a fact about the thing); the cursor never stops on it. */
class ActionNote(val text: String) : ActionSheetItem

/**
 * The one sheet of choices for a thing (mytop-project/tracker#610: "a shared ActionSheet and row model"): [title], a
 * [subtitle] line when there is one, then [rows], the first row focused. Up and Down move between the [ActionRow]s, A
 * chooses, and Select or L2 closes it ([onDismiss]), L2 because L2 opens the focused thing's menu (docs/SPEC.md 7j). B
 * and the system Back go to [onBack], which is [onDismiss] unless the sheet has pages to step out of first (the game
 * menu's categories). It is a window of its own that holds the pad while open ([MenuPanel]) and keeps the system bars
 * hidden, and every row is a touch target. A choice inside a row opens a page or a task, never a second sheet, so
 * nothing built on this is deeper than one level.
 *
 * [focus] is the focused row's index among the rows; a caller passes its own when it needs to see the cursor move
 * (the game menu disarms Uninstall when it does) or put it back at the top.
 *
 * This is the centred form; [AnchoredMenu] is the same sheet opened beside the item it is the menu of.
 */
@Composable
fun ActionSheet(
    title: String,
    rows: List<ActionSheetItem>,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    onBack: () -> Unit = onDismiss,
    focus: MutableIntState = remember(rows.size) { mutableIntStateOf(0) },
    width: Dp = 480.dp,
    focusLabel: String = title,
) {
    // The centred sheet is the anchored menu with nothing to stand beside: one body, one pad handling.
    AnchoredMenu(
        anchor = null,
        title = title,
        rows = rows,
        onDismiss = onDismiss,
        subtitle = subtitle,
        onBack = onBack,
        focus = focus,
        width = width,
        focusLabel = focusLabel,
    )
}

/**
 * The body every sheet of choices draws ([ActionSheet], [AnchoredMenu]): a [MenuPanel] holding [subtitle] and [rows],
 * with the one pad handling. Up and Down move [focus] between the [ActionRow]s, A chooses, B goes to [onBack], and
 * Select or L2 closes ([onDismiss]).
 */
@Composable
internal fun ActionSheetBody(
    title: String,
    rows: List<ActionSheetItem>,
    onDismiss: () -> Unit,
    subtitle: String?,
    onBack: () -> Unit,
    focus: MutableIntState,
    focusLabel: String,
    modifier: Modifier = Modifier,
) {
    var focusIndex by focus
    val choices = rows.filter { it is ActionRow || it is ActionTextRow }
    // The text row being typed in: while it is, the pad belongs to its field.
    var typing by remember(rows) { mutableStateOf<ActionTextRow?>(null) }
    MenuPanel(
        modifier = modifier,
        focusLabel = focusLabel,
        title = title,
        hints = ACTION_SHEET_HINTS,
        onPad = { press ->
            if (typing != null) return@MenuPanel false
            when (press.action) {
                GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, choices.size, press)
                GamepadAction.A -> when (val chosen = choices.getOrNull(focusIndex)) {
                    is ActionRow -> chosen.onSelect?.invoke()
                    is ActionTextRow -> typing = chosen
                    else -> Unit
                }
                // The release of the same press cannot step out a second time; it belongs to this press (SPEC 6e).
                GamepadAction.B -> onBack()
                GamepadAction.SELECT, GamepadAction.L2 -> onDismiss()
                else -> Unit
            }
            true
        },
    ) {
        subtitle?.let { Text(it, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall) }
        var rowIndex = 0
        rows.forEach { item ->
            when (item) {
                is ActionHeading -> MenuSectionLabel(item.title)
                is ActionNote -> Text(item.text, color = MenuTokens.Value, style = MaterialTheme.typography.bodySmall)
                is ActionTextRow -> {
                    val index = rowIndex++
                    if (typing === item) {
                        InPlaceTextField(
                            initial = item.value,
                            hint = item.hint,
                            title = item.title,
                            secret = item.secret,
                            search = item.search,
                            onDone = { text ->
                                typing = null
                                item.onDone(text)
                            },
                        )
                    } else {
                        MenuRow(
                            title = item.title,
                            subtitle = item.detail?.ifBlank { null },
                            value = if (item.secret && item.value.isNotEmpty()) "••••" else item.value.ifBlank { item.hint },
                            placeholder = item.value.isBlank(),
                            selected = index == focusIndex,
                            onClick = {
                                focusIndex = index
                                typing = item
                            },
                        )
                    }
                }
                is ActionRow -> {
                    val index = rowIndex++
                    MenuRow(
                        title = item.title,
                        subtitle = item.detail?.ifBlank { null },
                        danger = item.danger,
                        selected = index == focusIndex,
                        progress = item.progress,
                        progressLook = item.look,
                        switchOn = item.switchOn,
                        onClick = {
                            focusIndex = index
                            item.onSelect?.invoke()
                        },
                    )
                }
            }
        }
    }
}

private val ACTION_SHEET_HINTS = listOf(HintBinding(GamepadAction.A, "Choose"), HintBinding(GamepadAction.B, "Close"))
