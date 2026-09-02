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
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.library.consoles.ConsoleSystemsRepository
import dev.droidtop.library.scraper.PcMatch
import dev.droidtop.library.scraper.PcScraper
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
 *
 * One picker for both libraries, not two: a ROM's candidates come from
 * TheGamesDB by platform id, a PC or engine game's from the PC scraper
 * source (Lutris or IGDB) by cleaned folder name, and past that the job
 * is identical -- show real names, let a person choose, write only what
 * they chose. For a PC or engine game the picker is doing more than
 * correcting a bad guess: the automatic pass deliberately applies
 * nothing it is not certain of, so this is the normal way those games
 * get their metadata at all.
 */
@Composable
internal fun ManualMatchPicker(
    entry: LibraryEntry,
    onApplied: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var candidates by remember(entry) { mutableStateOf<List<MatchCandidate>?>(null) }
    var status by remember(entry) { mutableStateOf<String?>(null) }
    var focusIndex by remember(entry) { mutableIntStateOf(0) }
    var applying by remember(entry) { mutableStateOf(false) }

    LaunchedEffect(entry) {
        if (entry.isPcOrEngineGame) {
            // The credential-shaped failures land in Unavailable: its
            // message names the setting to fill in, so an unconfigured
            // IGDB reads as an instruction rather than an empty list.
            when (val found = PcScraper.candidates(context, entry)) {
                is PcScraper.Candidates.Unavailable -> {
                    status = found.message
                    candidates = emptyList()
                }
                is PcScraper.Candidates.Found -> candidates = found.matches.map { MatchCandidate.Pc(it) }
            }
            return@LaunchedEffect
        }
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
        candidates = found.map { MatchCandidate.Rom(it) }
        if (found.isEmpty()) status = "No candidates came back for this name."
    }

    fun apply(candidate: MatchCandidate) {
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
                        subtitle = candidate.subtitle,
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
        val applied = when (candidate) {
            is MatchCandidate.Rom -> withContext(Dispatchers.IO) {
                dev.droidtop.library.scraper.applyManualMatch(context, entry, candidate.candidate.id)
            }
            is MatchCandidate.Pc -> PcScraper.apply(context, entry, candidate.match)
        }
        applying = false
        onApplied(applied)
        onDismiss()
    }
}

/**
 * One row in the picker, whichever library it came from. The picker
 * shows [name] and [subtitle] and hands the whole candidate back to the
 * source that produced it, rather than an id to look up again -- nothing
 * can be re-matched to a different row between being shown and being
 * applied.
 */
internal sealed interface MatchCandidate {
    val name: String
    val subtitle: String?

    data class Rom(val candidate: TheGamesDbClient.Candidate) : MatchCandidate {
        override val name: String get() = candidate.name
        override val subtitle: String? get() = candidate.releaseYear?.let { "Released $it" }
    }

    data class Pc(val match: PcMatch) : MatchCandidate {
        override val name: String get() = match.name
        override val subtitle: String
            get() = listOfNotNull(match.year?.let { "Released $it" }, match.sourceLabel).joinToString(" from ")
    }
}
