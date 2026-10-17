package dev.droidtop.shell.gamepad

import dev.droidtop.ui.shared.NoticeCard
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.library.settings.Notice
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.PcScraper
import dev.droidtop.shell.gamepad.input.GamepadAction

/**
 * The PC and engine games a scrape left at "needs a pick", worked through with the pad (docs/SPEC.md 7h, "Games
 * that need your pick"): Up and Down move, A opens the game's [ManualMatchPicker], B closes. A game the person
 * matches leaves the list, since the match replaces its [PcScraper.NEEDS_PICK]; the list is read again each time a
 * picker closes, and focus stays on the same place so the next game is under it.
 */
@Composable
internal fun ChooseMatchesList(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var games by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var focusIndex by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<LibraryEntry?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    // Bumped when a picker closes, so the list is read again.
    var reads by remember { mutableIntStateOf(0) }

    LaunchedEffect(reads) {
        val now = PcScraper.needingPick(context)
        games = now
        focusIndex = focusIndex.coerceAtMost((now.size - 1).coerceAtLeast(0))
    }

    val open = picking
    if (open != null) {
        ManualMatchPicker(
            entry = open,
            onApplied = { status = it },
            onDismiss = {
                picking = null
                reads++
            },
        )
        return
    }

    BackHandler { onDismiss() }
    Dialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(620.dp)),
            focusLabel = PcScraper.CHOOSE_MATCHES,
            onPad = { press ->
                val list = games.orEmpty()
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> focusIndex = menuMove(focusIndex, list.size, press)
                    GamepadAction.A -> list.getOrNull(focusIndex)?.let { picking = it }
                    GamepadAction.B, GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                PcScraper.CHOOSE_MATCHES,
                style = MaterialTheme.typography.titleMedium,
                color = MenuTokens.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            val list = games
            when {
                list == null -> MenuRow(title = "Reading the list...")
                list.isEmpty() -> MenuRow(title = "No game is waiting for a pick")
                else -> list.forEachIndexed { index, entry ->
                    MenuRow(
                        title = entry.title,
                        subtitle = "Candidates came back, none exact",
                        selected = index == focusIndex,
                        onClick = {
                            focusIndex = index
                            picking = entry
                        },
                    )
                }
            }
            status?.let { NoticeCard(Notice(NoticeTone.Info, it), NoticePlacement.Group) }
        }
    }
}
