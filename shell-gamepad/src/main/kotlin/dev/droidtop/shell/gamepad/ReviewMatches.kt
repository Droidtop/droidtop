package dev.droidtop.shell.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import dev.droidtop.library.GameTitleParser
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.identity.MatchQueue
import dev.droidtop.library.scraper.LibraryScrapeJob
import dev.droidtop.library.scraper.PcScraper
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.settings.NoticeTone
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.ui.shared.NoticeCard
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tabs' names, in the order L1 and R1 step through them (Droidtop/tracker#318 §5.1). */
private val TAB_NAMES = mapOf(
    MatchQueue.Tab.LIKELY to "Likely",
    MatchQueue.Tab.UNSURE to "Unsure",
    MatchQueue.Tab.NOT_FOUND to "Not found",
)

private val REVIEW_HINTS = listOf(
    HintBinding(GamepadAction.A, "Accept"),
    HintBinding(GamepadAction.X, "Local only"),
    HintBinding(GamepadAction.Y, "Details"),
    HintBinding(GamepadAction.L2, "Search again"),
    HintBinding(GamepadAction.B, "Close"),
)

/**
 * Review Matches (docs/SPEC.md 7h, "Review Matches"; Droidtop/tracker#318 §5): the games a scrape did not take a match
 * for, one card per game in three tabs by effort, Likely, Unsure and Not found. A card shows the game as it is on disk
 * on the left and the source's best five answers on the right, the best chosen, each with its cover and the score's
 * pips. Up and Down choose, A applies the chosen one and moves to the next card, Left and Right move between cards,
 * L1 and R1 between tabs, X marks the game Local only (never asked about again), Y shows the chosen answer's
 * description, L2 searches the source again in the match picker, B closes. Games that searched for the same name are one
 * card, and a pick applies to all of them. A game a pass marked before the queue kept answers searches again on A.
 * Everything is read from the queue, so it opens at once and offline.
 */
@Composable
internal fun ReviewMatches(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<MatchQueue.Card>?>(null) }
    var entries by remember { mutableStateOf<Map<String, LibraryEntry>>(emptyMap()) }
    var tab by remember { mutableStateOf(MatchQueue.Tab.LIKELY) }
    var cardIndex by remember { mutableIntStateOf(0) }
    var choiceIndex by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf<LibraryEntry?>(null) }
    // Bumped after anything that changes the queue, so it is read again.
    var reads by remember { mutableIntStateOf(0) }

    LaunchedEffect(reads) {
        val loaded = withContext(Dispatchers.IO) {
            val games = LibraryScrapeJob.libraryGames(context).associateBy { it.id }
            val queued = MatchQueue.items(context).filter { it.gameId in games }
            // A game a pass marked "needs a pick" before the queue kept answers: a card that searches again.
            val known = queued.map { it.gameId }.toSet()
            val older = PcScraper.needingPick(context)
                .filter { it.id !in known && !MatchQueue.isLocalOnly(context, it.id) }
                .map { MatchQueue.Item(it.id, MatchQueue.Tab.LIKELY, "", it.title, emptyList(), 0) }
            games to MatchQueue.cards(queued + older)
        }
        entries = loaded.first
        cards = loaded.second
    }

    val all = cards.orEmpty()
    val inTab = all.filter { it.tab == tab }
    val card = inTab.getOrNull(cardIndex.coerceAtMost((inTab.size - 1).coerceAtLeast(0)))
    val choices = card?.choices.orEmpty()

    val open = searching
    if (open != null) {
        ManualMatchPicker(
            entry = open,
            onApplied = { status = it },
            onDismiss = {
                searching = null
                reads++
            },
        )
        return
    }

    fun moveTab(step: Int) {
        val tabs = MatchQueue.Tab.entries
        tab = tabs[(tabs.indexOf(tab) + step + tabs.size) % tabs.size]
        cardIndex = 0
        choiceIndex = 0
        details = false
    }

    fun moveCard(step: Int) {
        if (inTab.isEmpty()) return
        cardIndex = (cardIndex + step).coerceIn(0, inTab.size - 1)
        choiceIndex = 0
        details = false
    }

    fun gamesOf(c: MatchQueue.Card): List<LibraryEntry> = c.items.mapNotNull { entries[it.gameId] }

    fun accept() {
        val c = card ?: return
        if (busy) return
        val choice = choices.getOrNull(choiceIndex)
        if (choice == null) {
            // Nothing kept for this card: ask the source again.
            searching = gamesOf(c).firstOrNull()
            return
        }
        busy = true
        status = "Applying ${choice.match.name}..."
        scope.launch {
            val lines = gamesOf(c).map { PcScraper.apply(context, it, choice.match) }
            status = lines.singleOrNull() ?: "Matched ${lines.size} games to ${choice.match.name}"
            busy = false
            // The card leaves the tab, so the same index is the next card.
            choiceIndex = 0
            details = false
            reads++
        }
    }

    fun localOnly() {
        val c = card ?: return
        if (busy) return
        busy = true
        scope.launch {
            c.items.forEach { MatchQueue.setLocalOnly(context, it.gameId, true) }
            status = "${c.searched}: local only, never asked about again"
            busy = false
            choiceIndex = 0
            reads++
        }
    }

    BackHandler { onDismiss() }
    Dialog(onDismissRequest = onDismiss) {
        GatePadInThisDialog()
        MenuPanel(
            modifier = Modifier.width(LocalShellWindow.current.panelWidth(760.dp)),
            focusLabel = PcScraper.REVIEW_MATCHES,
            hints = REVIEW_HINTS,
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> {
                        choiceIndex = menuMove(choiceIndex, choices.size, press)
                        details = false
                    }
                    GamepadAction.LEFT -> moveCard(-1)
                    GamepadAction.RIGHT -> moveCard(1)
                    GamepadAction.L -> moveTab(-1)
                    GamepadAction.R -> moveTab(1)
                    GamepadAction.A -> accept()
                    GamepadAction.X -> localOnly()
                    GamepadAction.Y -> details = !details
                    GamepadAction.L2 -> card?.let { c -> searching = gamesOf(c).firstOrNull() }
                    GamepadAction.B, GamepadAction.SELECT -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                PcScraper.REVIEW_MATCHES,
                style = MaterialTheme.typography.titleMedium,
                color = MenuTokens.OnSurface,
                fontWeight = FontWeight.SemiBold,
            )
            ReviewTabs(tab, all)
            when {
                cards == null -> MenuRow(title = "Reading the queue...")
                card == null -> MenuRow(title = "Nothing waits in ${TAB_NAMES.getValue(tab)}")
                else -> {
                    Text(
                        "${cardIndex.coerceAtMost(inTab.size - 1) + 1} of ${inTab.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MenuTokens.OnSurfaceMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LocalSide(card, entries, Modifier.weight(0.4f))
                        Column(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (choices.isEmpty()) {
                                MenuRow(title = "No answer kept", subtitle = "A searches the source again", selected = true)
                            }
                            choices.forEachIndexed { i, choice ->
                                ChoiceRow(choice, selected = i == choiceIndex, onClick = {
                                    choiceIndex = i
                                    accept()
                                })
                            }
                            choices.getOrNull(choiceIndex)?.takeIf { details }?.let { choice ->
                                Text(
                                    choice.match.description ?: "${choice.match.sourceLabel} gave no description",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MenuTokens.OnSurfaceMuted,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            status?.let { NoticeCard(Notice(NoticeTone.Info, it), NoticePlacement.Group) }
        }
    }
}

/** The three tabs with their counts; the open one in the accent. */
@Composable
private fun ReviewTabs(open: MatchQueue.Tab, cards: List<MatchQueue.Card>) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        MatchQueue.Tab.entries.forEach { t ->
            val count = cards.filter { it.tab == t }.sumOf { it.items.size }
            Text(
                "${TAB_NAMES.getValue(t)} ($count)",
                style = MaterialTheme.typography.labelLarge,
                color = if (t == open) MenuTokens.Accent else MenuTokens.OnSurfaceMuted,
                fontWeight = if (t == open) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** The game as it is on disk: its folder or file name, the title and version read from it, and how many share the card. */
@Composable
private fun LocalSide(card: MatchQueue.Card, entries: Map<String, LibraryEntry>, modifier: Modifier) {
    val first = entries[card.items.first().gameId]
    val name = first?.let { File(it.id).name } ?: card.searched
    val parsed = GameTitleParser.parseName(name)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.titleSmall, color = MenuTokens.OnSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Read as ${parsed.title}", style = MaterialTheme.typography.bodySmall, color = MenuTokens.OnSurfaceMuted)
        parsed.version.takeIf { it.isNotBlank() }?.let {
            Text("Version $it", style = MaterialTheme.typography.bodySmall, color = MenuTokens.OnSurfaceMuted)
        }
        card.items.first().source.takeIf { it.isNotBlank() }?.let {
            Text("Searched $it for \"${card.searched}\"", style = MaterialTheme.typography.bodySmall, color = MenuTokens.OnSurfaceMuted)
        }
        if (card.items.size > 1) {
            Text("x${card.items.size}: a pick applies to all", style = MaterialTheme.typography.labelMedium, color = MenuTokens.Accent)
        }
    }
}

/** One answer: its cover, title, year and source, and the score as pips. */
@Composable
private fun ChoiceRow(choice: MatchQueue.Choice, selected: Boolean, onClick: () -> Unit) {
    val match = choice.match
    Row(
        Modifier
            .background(if (selected) MenuTokens.SurfaceSelected else MenuTokens.Surface)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(width = 36.dp, height = 48.dp).background(MenuTokens.Placeholder)) {
            match.coverUrl?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 36.dp, height = 48.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(match.name, style = MaterialTheme.typography.bodyMedium, color = MenuTokens.OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(match.year?.toString(), match.sourceLabel.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MenuTokens.OnSurfaceMuted,
            )
        }
        Pips(MatchQueue.pips(choice.score))
        Spacer(Modifier.width(6.dp))
    }
}

/** The score as filled pips out of [MatchQueue.PIPS], never as a number (#318 §5.1). */
@Composable
private fun Pips(filled: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(MatchQueue.PIPS) { i ->
            Box(Modifier.size(7.dp).background(if (i < filled) MenuTokens.Accent else MenuTokens.Placeholder, CircleShape))
        }
    }
}

