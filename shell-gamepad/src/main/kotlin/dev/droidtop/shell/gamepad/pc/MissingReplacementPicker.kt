package dev.droidtop.shell.gamepad.pc

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.width
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.MissingGames
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuHint
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.launch

/**
 * "This game IS that one": the one picker behind both directions of the
 * fold (docs/SPEC.md 7g).
 *
 * From a detected game it lists the missing games it could be the
 * replacement of; from a missing game it lists the detected games that
 * could have replaced it. The list is the same list either way, in the
 * same order -- same derived name first, then the names that are at
 * least 0.6 alike, most alike first (Pythia's `find_candidates`, ported
 * in [MissingGames]) -- because it is one question asked from two sides,
 * and a person seeing two different orders on two screens would have to
 * work out which one to trust.
 *
 * Choosing folds: the missing entry's play history, favourite, metadata
 * and collection memberships move to the detected game's id and the
 * missing entry leaves the library. That is a real, one-way change to
 * what the library knows, so each row says what it will do and nothing
 * happens until A or a tap on the row the person means.
 */
@Composable
internal fun MissingReplacementPicker(
    entry: LibraryEntry,
    among: List<LibraryEntry>,
    library: Library,
    onFolded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val candidates = remember(entry, among) {
        MissingGames.candidates(target = entry, among = among.filter { it.missing != entry.missing })
    }
    var focusIndex by remember(entry) { mutableIntStateOf(0) }
    var folding by remember(entry) { mutableStateOf(false) }

    fun fold(candidate: MissingGames.Candidate) {
        if (folding) return
        folding = true
        scope.launch {
            // Whichever side this screen is on, the missing entry is the
            // one that goes and the present one is the one that stays.
            val missing = if (entry.missing) entry else candidate.entry
            val replacement = if (entry.missing) candidate.entry else entry
            val folded = library.replaceMissing(missing, replacement)
            folding = false
            onFolded(
                if (folded) {
                    "${missing.title} is now ${replacement.title}."
                } else {
                    "${missing.title} could not be folded into ${replacement.title}."
                },
            )
        }
    }

    BackHandler { onDismiss() }
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(620.dp)),
            focusLabel = if (entry.missing) "Find its replacement" else "This replaces a missing game",
            onKey = { event ->
                if (event.type != KeyEventType.KeyUp || candidates.isEmpty()) {
                    false
                } else {
                    when (GamepadKeyMap.actionFor(event.key)) {
                        GamepadAction.UP -> {
                            focusIndex = (focusIndex - 1 + candidates.size) % candidates.size
                            EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.DOWN -> {
                            focusIndex = (focusIndex + 1) % candidates.size
                            EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.A -> {
                            candidates.getOrNull(focusIndex)?.let { fold(it) }
                            true
                        }
                        GamepadAction.B, GamepadAction.BACK -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                }
            },
        ) {
            Text(
                if (entry.missing) {
                    "Which game replaced ${entry.title}?"
                } else {
                    "Which missing game is ${entry.title}?"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MenuTokens.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            if (candidates.isEmpty()) {
                MenuRow(title = "No game here is named anything like this one")
            } else {
                candidates.forEachIndexed { index, candidate ->
                    MenuRow(
                        title = candidate.entry.title,
                        subtitle = candidate.line(),
                        selected = index == focusIndex,
                        onClick = {
                            focusIndex = index
                            fold(candidate)
                        },
                    )
                }
            }
            if (folding) MenuHint("Moving this game's history across...")
            MenuHint("Up/Down moves, A picks, B closes")
        }
    }
}

/**
 * What the row says under the name: whether this is the same game by
 * name or a suggestion, and where it is. Never a bare percentage --
 * "0.73" is not a reason a person can act on, and the path is.
 */
private fun MissingGames.Candidate.line(): String {
    val where = entry.id.takeIf { it.startsWith("/") }
    val why = if (certain) "The same name" else "A similar name"
    return listOfNotNull(why, where).joinToString(" - ")
}
