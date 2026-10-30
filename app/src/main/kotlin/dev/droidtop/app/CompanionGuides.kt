package dev.droidtop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.droidtop.display.secondScreenScroll
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.Links
import dev.droidtop.library.guides.Guide
import dev.droidtop.library.guides.GuideBookmark
import dev.droidtop.library.guides.GuideContent
import dev.droidtop.library.guides.GuideFormat
import dev.droidtop.library.guides.GuidePdf
import dev.droidtop.library.guides.GuidePlace
import dev.droidtop.library.guides.GuidePlaces
import dev.droidtop.library.guides.GuidePosition
import dev.droidtop.library.guides.nextMark
import dev.droidtop.runtime.util.AppScopes
import dev.droidtop.shell.gamepad.R as GamepadR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The running game's guides on the companion, beside the game (docs/SPEC.md 7i "Guides", Droidtop/tracker#817): the
 * same list and the same kept place as the game menu's Guides ([GuideContent], [GuidePlaces]), drawn for touch. A finger
 * scrolls and turns pages while the pad stays on the game (the companion never takes the pad: [CompanionActivity]).
 * The notes are shown here and written in the game menu, where the shell's text entry is. A web search opens the
 * browser on this screen. While a guide is open the companion stays on. Its words are the game menu's own (`GamepadR`),
 * one copy for both readers.
 */
@Composable
internal fun CompanionGuides(entry: LibraryEntry, onClose: () -> Unit) {
    val context = LocalContext.current
    var place by remember(entry.id) { mutableStateOf<GuidePlace?>(null) }
    val guides by produceState<List<Guide>?>(null, entry.id) {
        value = withContext(Dispatchers.IO) {
            place = GuidePlaces.load(context, entry.id)
            GuideContent.forGame(GuideContent.folderOf(entry), entry.title)
        }
    }
    var reading by remember(entry.id) { mutableStateOf<Guide?>(null) }
    fun keep(next: GuidePlace) {
        place = next
        val app = context.applicationContext
        AppScopes.io.launch { GuidePlaces.save(app, entry.id, next) }
    }
    val open = reading
    if (open != null) {
        val current = place ?: GuidePlace()
        CompanionGuideReader(
            guide = open,
            start = current.at(open.id),
            bookmarks = current.bookmarks[open.id].orEmpty(),
            onBookmark = { mark -> keep((place ?: GuidePlace()).marked(open.id, mark)) },
            onLeave = { left -> keep((place ?: GuidePlace()).leftAt(open.id, left)) },
            onClose = { reading = null },
        )
        return
    }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().secondScreenScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompanionPill("Back", onClick = onClose)
            Text(stringResource(GamepadR.string.guides_title, entry.title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        place?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            CompanionCard("Your notes") {
                Text(notes, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
        }
        val list = guides
        if (list == null) {
            CompanionNote(stringResource(GamepadR.string.guides_looking))
            return@Column
        }
        list.forEach { guide ->
            val web = guide.format == GuideFormat.WEB && guide.path == null
            GuideRow(
                title = guide.title,
                detail = when {
                    place?.last == guide.id -> "Last read · ${guide.from}"
                    web -> "${guide.from} · opens the browser here"
                    else -> guide.from
                },
            ) {
                // From the companion's own context, so the browser opens on this screen and the game keeps the other.
                if (web) guide.url?.let { Links.open(context, it) } else reading = guide
            }
        }
        if (list.none { it.path != null }) CompanionNote(stringResource(GamepadR.string.guides_none_in_folder))
    }
}

/**
 * One guide read by touch: drag to scroll, the page buttons for a PDF, Mark and Next mark as in the game menu's
 * reader. The place is kept when it is left by Back, by another tab or by the game ending ([onLeave]).
 */
@Composable
private fun CompanionGuideReader(
    guide: Guide,
    start: GuidePosition,
    bookmarks: List<GuideBookmark>,
    onBookmark: (GuideBookmark) -> Unit,
    onLeave: (GuidePosition) -> Unit,
    onClose: () -> Unit,
) {
    KeepScreenOn(true)
    var said by remember(guide.id) { mutableStateOf<String?>(null) }
    // Where the reader is, asked when it is marked or left; the format's reader below answers it.
    val where = remember(guide.id) { ReaderPlace(start) }
    fun position() = where.read()
    val leave by rememberUpdatedState(onLeave)
    DisposableEffect(guide.id) { onDispose { leave(where.read()) } }
    // A mark to go to, handed to the format's reader.
    var goTo by remember(guide.id) { mutableStateOf<GuidePosition?>(null) }
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompanionPill("Back", onClick = onClose)
            Text(guide.title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            CompanionPill("Mark") {
                val here = position()
                val label = if (guide.format == GuideFormat.PDF) "Page ${here.page + 1}" else "${(here.fraction * 100).toInt()}% through"
                onBookmark(GuideBookmark(here, label))
                said = "Marked $label"
            }
            CompanionPill("Next mark") {
                val mark = nextMark(bookmarks, position())
                if (mark != null) goTo = mark.position
                said = mark?.label ?: "No marks further on"
            }
        }
        said?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (guide.format) {
                GuideFormat.PDF -> CompanionPdfGuide(guide, start, goTo, where, onWent = { goTo = null })
                else -> CompanionTextGuide(guide, start, goTo, where, onWent = { goTo = null })
            }
        }
    }
}

@Composable
private fun CompanionTextGuide(
    guide: Guide,
    start: GuidePosition,
    goTo: GuidePosition?,
    where: ReaderPlace,
    onWent: () -> Unit,
) {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(null, guide.id) {
        value = withContext(Dispatchers.IO) { GuideContent.paragraphs(guide) ?: listOf(context.getString(GamepadR.string.guide_unreadable)) }
    }
    val state = rememberLazyListState()
    val list = paragraphs.orEmpty()
    where.read = { GuidePosition(0, if (list.isEmpty()) 0f else state.firstVisibleItemIndex.toFloat() / list.size) }
    suspend fun scrollTo(at: GuidePosition) {
        if (list.isNotEmpty()) state.scrollToItem((at.fraction * list.size).toInt().coerceIn(0, list.size - 1))
    }
    LaunchedEffect(paragraphs) { if (paragraphs != null) scrollTo(start) }
    LaunchedEffect(goTo) {
        val target = goTo ?: return@LaunchedEffect
        scrollTo(target)
        onWent()
    }
    if (paragraphs == null) {
        CompanionNote(stringResource(GamepadR.string.guide_opening_page))
        return
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list) { paragraph ->
            Text(paragraph, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun CompanionPdfGuide(
    guide: Guide,
    start: GuidePosition,
    goTo: GuidePosition?,
    where: ReaderPlace,
    onWent: () -> Unit,
) {
    val document = remember(guide.id) { GuidePdf(guide.path) }
    DisposableEffect(document) { onDispose { document.close() } }
    var page by remember(guide.id) { mutableIntStateOf(start.page) }
    // Where on the page to land once it is drawn: the kept place, then a mark gone to.
    var landAt by remember(guide.id) { mutableStateOf<Float?>(start.fraction) }
    val scroll = rememberScrollState()
    where.read = { GuidePosition(page, if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f) }
    LaunchedEffect(goTo) {
        val target = goTo ?: return@LaunchedEffect
        page = target.page
        landAt = target.fraction
        onWent()
    }
    fun turn(to: Int) {
        if (document.pageCount <= 0) return
        val next = to.coerceIn(0, document.pageCount - 1)
        if (next == page) return
        page = next
        landAt = 0f
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompanionPill("Previous page") { turn(page - 1) }
            Text(
                "Page ${page + 1} of ${document.pageCount.coerceAtLeast(1)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            CompanionPill("Next page") { turn(page + 1) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = with(LocalDensity.current) { maxWidth.roundToPx() }
            // The page drawn, with its number: the place is landed on only once the page asked for is laid out.
            val rendered by produceState<Pair<Int, android.graphics.Bitmap?>?>(null, page, width) {
                val asked = page
                value = asked to withContext(Dispatchers.IO) { document.render(asked, width) }
            }
            val bitmap = rendered?.takeIf { it.first == page }?.second
            LaunchedEffect(bitmap, landAt) {
                val fraction = landAt ?: return@LaunchedEffect
                if (bitmap == null) return@LaunchedEffect
                withFrameNanos { }
                scroll.scrollTo((fraction * scroll.maxValue).toInt())
                landAt = null
            }
            Box(Modifier.fillMaxSize().secondScreenScroll(scroll), contentAlignment = Alignment.TopCenter) {
                val shown = bitmap
                if (shown != null) {
                    Image(shown.asImageBitmap(), contentDescription = "Page ${page + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                } else {
                    CompanionNote(stringResource(if (document.pageCount < 0) GamepadR.string.guide_unreadable else GamepadR.string.guide_opening_page))
                }
            }
        }
    }
}

/** A guide in the list: 56dp, its name and where it is from. */
@Composable
private fun GuideRow(title: String, detail: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    CompanionTile(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Where an open guide is, as its reader answers it: asked when the person marks the place and when they leave. */
private class ReaderPlace(start: GuidePosition) {
    var read: () -> GuidePosition = { start }
}
