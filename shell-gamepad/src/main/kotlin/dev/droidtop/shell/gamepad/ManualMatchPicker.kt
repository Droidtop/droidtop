package dev.droidtop.shell.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.scraper.TheGamesDbClient
import dev.droidtop.library.scraper.TheGamesDbPrefs
import dev.droidtop.library.scraper.TheGamesDbSystemIds
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pick the right match by hand when automatic scraping picked wrong.
 *
 * This exists because automatic matching is wrong often enough to
 * matter: on a real device pass "Pokemon - Crystal Version" matched a
 * fan game called "Pokemon Black and White 3: Genesis", purely because
 * that is what the API returned first. Ranking by title similarity
 * helps and still cannot know which of five plausible rows is the game
 * on this card. A person looking at the list knows immediately.
 */
@Composable
internal fun ManualMatchPicker(
    entry: LibraryEntry,
    onApplied: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var candidates by remember(entry) { mutableStateOf<List<TheGamesDbClient.Candidate>?>(null) }
    var status by remember(entry) { mutableStateOf<String?>(null) }
    var focusIndex by remember(entry) { mutableIntStateOf(0) }
    var applying by remember(entry) { mutableStateOf(false) }

    LaunchedEffect(entry) {
        val apiKey = TheGamesDbPrefs.apiKey(context)
        if (apiKey.isBlank()) {
            status = "TheGamesDB needs its API key before a search can run."
            candidates = emptyList()
            return@LaunchedEffect
        }
        val systemsById = ConsoleSystemsRepository.allSystems(context).associateBy { it.id }
        val system = entry.systemId?.let { systemsById[it] }
        val tgdbId = system?.let { TheGamesDbSystemIds.forSystemId(it.id) }
        if (tgdbId == null) {
            status = "TheGamesDB has no platform id for this system."
            candidates = emptyList()
            return@LaunchedEffect
        }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                TheGamesDbClient.searchCandidates(apiKey, tgdbId, java.io.File(entry.id).nameWithoutExtension)
            }.getOrDefault(emptyList())
        }
        candidates = found
        if (found.isEmpty()) status = "No candidates came back for this name."
    }

    fun apply(candidate: TheGamesDbClient.Candidate) {
        if (applying) return
        applying = true
        status = "Applying ${candidate.name}..."
    }

    BackHandler { onDismiss() }
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(620.dp),
            focusLabel = "Manual match",
            onKey = { event ->
                val list = candidates.orEmpty()
                if (event.type != KeyEventType.KeyUp || list.isEmpty()) {
                    false
                } else {
                    when (GamepadKeyMap.actionFor(event.key)) {
                        GamepadAction.UP -> {
                            focusIndex = (focusIndex - 1 + list.size) % list.size
                            EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.DOWN -> {
                            focusIndex = (focusIndex + 1) % list.size
                            EsDeNavigationSounds.play("scroll")
                            true
                        }
                        GamepadAction.A -> {
                            list.getOrNull(focusIndex)?.let { apply(it) }
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
                "Match ${entry.title}",
                style = MaterialTheme.typography.titleMedium,
                color = MenuTokens.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            when {
                candidates == null -> MenuRow(title = "Searching...")
                candidates!!.isEmpty() -> MenuRow(title = status ?: "Nothing found")
                else -> candidates!!.forEachIndexed { index, candidate ->
                    MenuRow(
                        title = candidate.name,
                        subtitle = candidate.releaseYear?.let { "Released $it" },
                        selected = index == focusIndex,
                        onClick = {
                            focusIndex = index
                            apply(candidate)
                        },
                    )
                }
            }
            status?.let { MenuHint(it) }
            MenuHint("Up/Down moves, A picks, B closes")
        }
    }

    // The write happens outside the key handler so a slow network call
    // never blocks input handling.
    LaunchedEffect(applying) {
        if (!applying) return@LaunchedEffect
        val candidate = candidates?.getOrNull(focusIndex) ?: return@LaunchedEffect
        val applied = withContext(Dispatchers.IO) {
            dev.droidtop.library.scraper.applyManualMatch(context, entry, candidate.id)
        }
        applying = false
        onApplied(applied)
        onDismiss()
    }
}
