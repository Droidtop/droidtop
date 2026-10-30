package dev.droidtop.shell.gamepad.pc

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.droidtop.library.Links
import dev.droidtop.library.guides.GuideContent
import dev.droidtop.library.guides.Guide
import dev.droidtop.library.guides.GuideBookmark
import dev.droidtop.library.guides.GuideFormat
import dev.droidtop.library.guides.GuidePlace
import dev.droidtop.library.guides.GuidePdf
import dev.droidtop.library.guides.GuidePlaces
import dev.droidtop.library.guides.GuidePosition
import dev.droidtop.library.guides.nextMark
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.R
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A game's guides and droidtop's reader for them (docs/SPEC.md 7i "Guides", Droidtop/tracker#817): the walkthrough,
 * guide and readme files in the game's own folder, read in place, then the web searches of the resources rows.
 * Text and PDF open in the reader, a web page in the browser. The game's place is kept per guide ([GuidePlaces]): the
 * guide last opened, where each was left, and the person's bookmarks. Every file is read off the main thread.
 */
@Composable
internal fun GuidesScreen(gameId: String, gameName: String, folder: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember(gameId) { mutableStateOf<GuidePlace?>(null) }
    val guides by produceState<List<Guide>?>(null, gameId, folder) {
        value = withContext(Dispatchers.IO) {
            place = GuidePlaces.load(context, gameId)
            GuideContent.forGame(folder, gameName)
        }
    }
    var reading by remember(gameId) { mutableStateOf<Guide?>(null) }
    var editingNotes by remember(gameId) { mutableStateOf(false) }
    if (editingNotes) {
        // The game's notes: one text, the shell's own text entry through a catalog row (Droidtop/tracker#817).
        val notesScreen = remember(gameId) {
            dev.droidtop.library.settings.CatalogScreen(
                id = "guide_notes",
                title = context.getString(R.string.guide_notes_title, gameName),
                groups = { _ ->
                    listOf(
                        dev.droidtop.library.settings.CatalogGroup(
                            id = "guide_notes_group",
                            title = null,
                            items = listOf(
                                dev.droidtop.library.settings.TextInputItem(
                                    id = "guide_notes_text",
                                    title = context.getString(R.string.guide_notes),
                                    value = place?.notes.orEmpty(),
                                    multiline = true,
                                    onChange = { ctx, text ->
                                        val next = (place ?: GuidePlace()).copy(notes = text)
                                        place = next
                                        withContext(Dispatchers.IO) { GuidePlaces.save(ctx, gameId, next) }
                                    },
                                ),
                            ),
                        ),
                    )
                },
            )
        }
        dev.droidtop.shell.gamepad.CatalogNavigator(root = notesScreen, onExit = { editingNotes = false })
        return
    }
    val open = reading
    if (open != null) {
        val current = place ?: GuidePlace()
        GuideReaderScreen(
            guide = open,
            start = current.at(open.id),
            bookmarks = current.bookmarks[open.id].orEmpty(),
            onBookmark = { mark ->
                val next = (place ?: GuidePlace()).marked(open.id, mark)
                place = next
                scope.launch { withContext(Dispatchers.IO) { GuidePlaces.save(context, gameId, next) } }
            },
            onClose = { left ->
                val next = (place ?: GuidePlace()).leftAt(open.id, left)
                place = next
                reading = null
                scope.launch { withContext(Dispatchers.IO) { GuidePlaces.save(context, gameId, next) } }
            },
        )
        return
    }
    var cursor by remember(gameId) { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Guides") }
    BackHandler { onClose() }
    val list = guides.orEmpty()
    // The rows: the game's notes first, then its guides.
    val rows = list.size + 1
    fun choose(guide: Guide) {
        if (guide.format == GuideFormat.WEB && guide.path == null) {
            guide.url?.let { Links.open(context, it) }
        } else {
            reading = guide
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .groundBackground()
            .focusRequester(focus)
            .focusable()
            .onPad { press ->
                when (press.action) {
                    GamepadAction.UP -> cursor = (cursor - 1).coerceAtLeast(0)
                    GamepadAction.DOWN -> cursor = (cursor + 1).coerceAtMost(rows - 1)
                    GamepadAction.A -> if (cursor == 0) editingNotes = true else list.getOrNull(cursor - 1)?.let(::choose)
                    GamepadAction.B -> onClose()
                    else -> return@onPad false
                }
                true
            }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.guides_title, gameName), color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleLarge)
        MenuRow(
            title = stringResource(R.string.guide_notes),
            value = place?.notes?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: stringResource(R.string.guide_notes_none),
            selected = cursor == 0,
            onClick = {
                cursor = 0
                editingNotes = true
            },
        )
        when {
            guides == null -> Text(stringResource(R.string.guides_looking), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            else -> list.forEachIndexed { index, guide ->
                MenuRow(
                    title = guide.title,
                    subtitle = guide.from,
                    value = when {
                        place?.last == guide.id -> "Last read"
                        guide.format == GuideFormat.WEB && guide.path == null -> "Opens the browser"
                        else -> null
                    },
                    selected = index + 1 == cursor,
                    onClick = {
                        cursor = index + 1
                        choose(guide)
                    },
                )
            }
        }
        if (guides != null && list.none { it.path != null }) {
            Text(
                stringResource(R.string.guides_none_in_folder),
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HintRow(
            bindings = listOf(HintBinding(GamepadAction.A, "Open"), HintBinding(GamepadAction.B, "Close")),
            background = Color.Transparent,
        )
    }
}

/**
 * One guide, read by pad: Up and Down scroll, L1 and R1 page through text, Left and Right turn a PDF's pages, Y marks
 * the place, X goes to the next mark, B closes and keeps the place. A text guide over [GuideContent.MAX_TEXT_BYTES] shows its start.
 */
@Composable
private fun GuideReaderScreen(
    guide: Guide,
    start: GuidePosition,
    bookmarks: List<GuideBookmark>,
    onBookmark: (GuideBookmark) -> Unit,
    onClose: (GuidePosition) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Guide") }
    var said by remember(guide.id) { mutableStateOf<String?>(null) }
    when (guide.format) {
        GuideFormat.PDF -> PdfGuide(guide, start, bookmarks, focus, onBookmark, onClose) { said = it }
        else -> TextGuide(guide, start, bookmarks, focus, onBookmark, onClose) { said = it }
    }
    said?.let {
        Box(Modifier.fillMaxSize().padding(bottom = 64.dp), contentAlignment = Alignment.BottomCenter) {
            Text(it, color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TextGuide(
    guide: Guide,
    start: GuidePosition,
    bookmarks: List<GuideBookmark>,
    focus: FocusRequester,
    onBookmark: (GuideBookmark) -> Unit,
    onClose: (GuidePosition) -> Unit,
    say: (String) -> Unit,
) {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(null, guide.id) {
        value = withContext(Dispatchers.IO) { GuideContent.paragraphs(guide) ?: listOf(context.getString(R.string.guide_unreadable)) }
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val list = paragraphs.orEmpty()
    fun position() = GuidePosition(0, if (list.isEmpty()) 0f else state.firstVisibleItemIndex.toFloat() / list.size)
    fun goTo(position: GuidePosition) {
        if (list.isNotEmpty()) scope.launch { state.scrollToItem((position.fraction * list.size).toInt().coerceIn(0, list.size - 1)) }
    }
    LaunchedEffect(paragraphs) { if (paragraphs != null) goTo(start) }
    BackHandler { onClose(position()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .groundBackground()
            .focusRequester(focus)
            .focusable()
            .onPad { press ->
                val page = state.layoutInfo.viewportSize.height.toFloat()
                when (press.action) {
                    GamepadAction.UP -> scope.launch { state.animateScrollBy(-page / 4) }
                    GamepadAction.DOWN -> scope.launch { state.animateScrollBy(page / 4) }
                    GamepadAction.L -> scope.launch { state.animateScrollBy(-page * 0.9f) }
                    GamepadAction.R -> scope.launch { state.animateScrollBy(page * 0.9f) }
                    GamepadAction.Y -> {
                        val here = position()
                        onBookmark(GuideBookmark(here, "${(here.fraction * 100).toInt()}% through"))
                        say("Marked")
                    }
                    GamepadAction.X -> nextMark(bookmarks, position())?.let {
                        goTo(it.position)
                        say(it.label)
                    } ?: say("No marks further on")
                    GamepadAction.B -> onClose(position())
                    else -> return@onPad false
                }
                true
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(guide.title, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
        LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(list) { _, paragraph ->
                Text(paragraph, color = MenuTokens.OnSurface, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 760.dp))
            }
        }
        HintRow(bindings = READER_HINTS, background = Color.Transparent)
    }
}

@Composable
private fun PdfGuide(
    guide: Guide,
    start: GuidePosition,
    bookmarks: List<GuideBookmark>,
    focus: FocusRequester,
    onBookmark: (GuideBookmark) -> Unit,
    onClose: (GuidePosition) -> Unit,
    say: (String) -> Unit,
) {
    val context = LocalContext.current
    val document = remember(guide.id) { GuidePdf(guide.path) }
    DisposableEffect(document) { onDispose { document.close() } }
    var page by remember(guide.id) { mutableIntStateOf(start.page) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val width = context.resources.displayMetrics.widthPixels
    // The page drawn, with its number: a place is landed on only once the page asked for is laid out.
    val rendered by produceState<Pair<Int, Bitmap?>?>(null, page) {
        val asked = page
        value = asked to withContext(Dispatchers.IO) { document.render(asked, width) }
    }
    val bitmap = rendered?.takeIf { it.first == page }?.second
    // Where on the page to land once it is drawn: the kept place, then a mark gone to.
    var landAt by remember(guide.id) { mutableStateOf<Float?>(start.fraction) }
    LaunchedEffect(bitmap, landAt) {
        val fraction = landAt ?: return@LaunchedEffect
        if (bitmap == null) return@LaunchedEffect
        withFrameNanos { }
        scroll.scrollTo((fraction * scroll.maxValue).toInt())
        landAt = null
    }
    fun position() = GuidePosition(page, if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f)
    fun turn(to: Int) {
        val pages = document.pageCount
        if (pages <= 0) return
        val next = to.coerceIn(0, pages - 1)
        if (next == page) return
        page = next
        landAt = 0f
    }
    BackHandler { onClose(position()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .groundBackground()
            .focusRequester(focus)
            .focusable()
            .onPad { press ->
                when (press.action) {
                    GamepadAction.UP -> scope.launch { scroll.animateScrollBy(-PDF_STEP_PX) }
                    GamepadAction.DOWN -> scope.launch { scroll.animateScrollBy(PDF_STEP_PX) }
                    GamepadAction.LEFT, GamepadAction.L -> turn(page - 1)
                    GamepadAction.RIGHT, GamepadAction.R -> turn(page + 1)
                    GamepadAction.Y -> {
                        onBookmark(GuideBookmark(position(), "Page ${page + 1}"))
                        say("Marked page ${page + 1}")
                    }
                    GamepadAction.X -> nextMark(bookmarks, position())?.let {
                        page = it.position.page
                        landAt = it.position.fraction
                        say(it.label)
                    } ?: say("No marks further on")
                    GamepadAction.B -> onClose(position())
                    else -> return@onPad false
                }
                true
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            stringResource(R.string.guide_pdf_heading, guide.title, page + 1, document.pageCount.coerceAtLeast(1)),
            color = MenuTokens.OnSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
            val shown = bitmap
            if (shown != null) {
                Image(shown.asImageBitmap(), contentDescription = "Page ${page + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            } else {
                Text(stringResource(if (document.pageCount < 0) R.string.guide_unreadable else R.string.guide_opening_page), color = MenuTokens.OnSurfaceMuted)
            }
        }
        HintRow(bindings = PDF_HINTS, background = Color.Transparent)
    }
}

private val READER_HINTS = listOf(
    HintBinding(GamepadAction.R, "Page"),
    HintBinding(GamepadAction.Y, "Mark"),
    HintBinding(GamepadAction.X, "Next mark"),
    HintBinding(GamepadAction.B, "Close"),
)

private val PDF_HINTS = listOf(
    HintBinding(GamepadAction.RIGHT, "Page"),
    HintBinding(GamepadAction.Y, "Mark"),
    HintBinding(GamepadAction.X, "Next mark"),
    HintBinding(GamepadAction.B, "Close"),
)

/** How far Up or Down moves down a PDF page, in pixels. */
private const val PDF_STEP_PX = 320f
