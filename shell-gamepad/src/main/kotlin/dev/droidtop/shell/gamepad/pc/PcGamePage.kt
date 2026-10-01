package dev.droidtop.shell.gamepad.pc

import dev.droidtop.shell.gamepad.groundBackground
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.droidtop.library.GameNaming
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.FieldSources
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.ShellChip
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import dev.droidtop.shell.gamepad.selectionFrame
import dev.droidtop.shell.gamepad.input.GatePadInThisDialog
import dev.droidtop.shell.gamepad.input.ownPadButtons

/**
 * One PC game's own page -- what Steam Big Picture and the Steam Deck
 * library show for a game, drawn over the gamelist as a full-bleed
 * Dialog (docs/SPEC.md 7i, "The PC library is a controller-first
 * storefront view"): the game's art on the left; on the right its name,
 * ONE big primary button (Play, or the setup step that makes it Play,
 * [PcPlayState]), two secondary buttons (Favourite, Options), and under
 * them everything known about the game in a column the D-pad scrolls.
 *
 * Opened by Y (or a long-press) on the library's grid; A on the grid
 * still plays at once (7i), so this page is for looking and for the
 * game's own actions, never a step in front of Play.
 *
 * Moving needs no key handling of its own: every button and every block of
 * the information column is a real focus target, so focus search moves
 * between them (Left/Right along the buttons, Up/Down between the buttons
 * and the blocks) and a block taking focus scrolls itself into view. The
 * presses go through the shell's one input pipeline (docs/SPEC.md 6e): the
 * page is its own window, so it gets the pipeline's front, A is the
 * confirm [ShellChip] answers, and B closes the page from the window's
 * outermost node ([ownPadButtons]), as the system back key does through
 * the Dialog. B used to be left to Android's fallback of BUTTON_B to BACK,
 * which a swapped face-button layout breaks: there the bottom button means
 * B, its fallback is DPAD_CENTER, and it pressed the focused button instead
 * of closing the page. Nothing on the page is reachable by touch only.
 *
 * No disk work while drawing: everything shown is already on the
 * [LibraryEntry]; the one lookup, the resolved runner, runs for this one
 * game off the main thread ([rememberPcPlayState]).
 */
@Composable
internal fun PcGamePage(
    entry: LibraryEntry,
    plateColor: Color?,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenOptions: () -> Unit,
    onClose: () -> Unit,
) {
    val (play, runner) = rememberPcPlayState(entry)
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(playFocus, "PC game page") }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        GatePadInThisDialog()
        val window = LocalShellWindow.current
        Row(
            modifier = Modifier
                .fillMaxSize()
                .ownPadButtons(onBack = onClose)
                .groundBackground()
                .padding(start = window.edgePadding, end = window.edgePadding, top = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(modifier = Modifier.weight(0.38f).fillMaxHeight()) {
                val art = entry.heroUri ?: entry.artworkUri
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(if (entry.heroUri != null) 16f / 9f else 3f / 4f)
                        .background(plateColor ?: MenuTokens.Card, RoundedCornerShape(12.dp)),
                ) {
                    if (art != null) {
                        AsyncImage(
                            model = art,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Text(
                            GameNaming.displayName(entry.title),
                            color = MenuTokens.OnSurface,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                PcPageHeader(
                    entry = entry,
                    play = play,
                    playFocus = playFocus,
                    onPlay = {
                        if (play.pressable) {
                            onClose()
                            onPlay()
                        }
                    },
                    onToggleFavorite = onToggleFavorite,
                    onOpenOptions = onOpenOptions,
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PcPageAbout(entry, play, runner?.let { "${it.label} - ${it.reason}" })
                }
            }
        }
    }
}

/** Title (or scraped logo), the identity line, the three buttons, and what the primary one will do. */
@Composable
private fun PcPageHeader(
    entry: LibraryEntry,
    play: PcPlayState,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    val title = GameNaming.displayName(entry.title)
    var logoFailed by remember(entry.logoUri) { mutableStateOf(false) }
    val logo = entry.logoUri
    if (logo != null && !logoFailed) {
        AsyncImage(
            model = logo,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { logoFailed = true },
            modifier = Modifier.fillMaxWidth(0.7f).height(56.dp),
        )
    } else {
        Text(
            title,
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        listOfNotNull(entry.sourceLabel(), entry.engineLabel()).joinToString(" - "),
        color = MenuTokens.OnSurfaceMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(
        modifier = Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellChip(
            play.verb,
            modifier = Modifier.focusRequester(playFocus),
            primary = true,
            large = true,
            onClick = onPlay,
        )
        ShellChip("Favourite", on = entry.favorite, onClick = onToggleFavorite)
        ShellChip("Options", onClick = onOpenOptions)
    }
    if (play.detail.isNotBlank()) {
        Text(
            play.detail,
            color = if (play.pressable) MenuTokens.Value else MenuTokens.OnSurfaceDisabled,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * "About this game" (docs/SPEC.md 7h): the scraped flavour, the labelled
 * facts, the line saying where each came from, then this copy's own
 * facts (runner, source, play time, update). All in-memory
 * [LibraryEntry] data.
 */
@Composable
private fun PcPageAbout(entry: LibraryEntry, play: PcPlayState, runnerLine: String?) {
    if (!entry.hideMetadata) {
        entry.description?.takeIf { it.isNotBlank() }?.let { desc ->
            InfoBlock { Text(desc, color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium) }
        }
        aboutFacts(entry).forEach { (label, value) -> PageFact(label, value) }
        sourcesLine(entry)?.let { line ->
            InfoBlock { Text(line, color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.labelSmall) }
        }
    }
    PageFact("Runs with", runnerLine ?: if (play.pressable) "" else play.detail)
    PageFact("Source", entry.sourceLabel())
    if (entry.playtimeSeconds > 0) PageFact("Played", "${entry.playtimeSeconds / 60} min")
    entry.availableUpdate?.let { PageFact("Update", "$it available") }
}

@Composable
private fun PageFact(label: String, value: String) {
    if (value.isBlank()) return
    InfoBlock {
        Row {
            Text(label, color = MenuTokens.SectionLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
            Text(value, color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * One stop of the information column: a real focus target, so the pad
 * reaches it, the column scrolls it into view the moment it is focused
 * (a focusable asks its scrollable ancestor to), and it wears the shell's
 * selection frame while it holds focus.
 */
@Composable
private fun InfoBlock(content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .selectionFrame(focused, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { content() }
}

/**
 * The labelled "About this game" facts, in the order a store page lists
 * them; only the ones that exist. Restored from the PcGameAbout.kt
 * 444271f2 deleted, for [PcGamePage] to draw (docs/SPEC.md 7h).
 */
internal fun aboutFacts(entry: LibraryEntry): List<Pair<String, String>> = listOfNotNull(
    entry.developer?.let { "Developer" to it },
    entry.publisher?.let { "Publisher" to it },
    entry.releaseDate?.let { formatReleaseDate(it) }?.let { "Released" to it },
    entry.genre?.let { "Genre" to it },
    entry.series?.let { "Series" to it },
    // ES-DE's 0-1 rating, shown on the five-star scale ES-DE draws it on.
    entry.rating?.let { "Rating" to String.format(java.util.Locale.US, "%.1f / 5", it * 5) },
)

/**
 * ES-DE's `YYYYMMDDT000000` as a date a person reads, in their locale;
 * null for anything that is not a full date (nothing here widens a year
 * into a day).
 */
internal fun formatReleaseDate(raw: String): String? {
    val parsed = runCatching {
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
            isLenient = false
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(raw.take(8))
    }.getOrNull() ?: return null
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }.format(parsed)
}

/**
 * "Description from IGDB. Cover and hero art from SteamGridDB." -- each
 * source once, with the fields it gave, in the order the fields are
 * listed ([FieldSources.LABELS]); "you" for what the metadata editor
 * changed. Null when nothing recorded a source. The one line docs/
 * SPEC.md 7h promises, read straight out of
 * [LibraryEntry.fieldSources] with no state of its own: it is how a
 * person sees where each field came from, which is also how they can
 * see what a rescrape is not allowed to take from them.
 */
internal fun sourcesLine(entry: LibraryEntry): String? {
    if (entry.fieldSources.isEmpty()) return null
    val order = FieldSources.LABELS.keys.toList()
    val bySource = entry.fieldSources.entries
        .sortedBy { order.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        .groupBy({ it.value }, { FieldSources.LABELS[it.key] ?: it.key })
    return bySource.entries.joinToString(" ") { (source, fields) ->
        val list = fields.mapIndexed { i, field -> if (i == 0) field else field.lowercase() }
        val joined = if (list.size == 1) list.single() else list.dropLast(1).joinToString(", ") + " and " + list.last()
        if (source == FieldSources.EDITED) "$joined edited by you." else "$joined from $source."
    }
}
