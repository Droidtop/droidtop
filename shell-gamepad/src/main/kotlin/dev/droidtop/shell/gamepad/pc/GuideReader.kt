package dev.droidtop.shell.gamepad.pc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
import dev.droidtop.library.GameResources
import dev.droidtop.library.Links
import dev.droidtop.library.guides.GameGuides
import dev.droidtop.library.guides.Guide
import dev.droidtop.library.guides.GuideBookmark
import dev.droidtop.library.guides.GuideFormat
import dev.droidtop.library.guides.GuidePlace
import dev.droidtop.library.guides.GuidePlaces
import dev.droidtop.library.guides.GuidePosition
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.HintBinding
import dev.droidtop.shell.gamepad.input.HintRow
import dev.droidtop.shell.gamepad.MenuRow
import dev.droidtop.shell.gamepad.R
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.groundBackground
import dev.droidtop.shell.gamepad.input.onPad
import dev.droidtop.shell.gamepad.requestFocusWhenAttached
import java.io.File
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
            val local = folder?.let { GameGuides.inFolder(File(it)) }.orEmpty()
            local + GameResources.forGame(gameName).map { Guide("web:${it.label}", it.label, it.detail, GuideFormat.WEB, url = it.url) }
        }
    }
    var reading by remember(gameId) { mutableStateOf<Guide?>(null) }
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
                    GamepadAction.DOWN -> cursor = (cursor + 1).coerceAtMost((list.size - 1).coerceAtLeast(0))
                    GamepadAction.A -> list.getOrNull(cursor)?.let(::choose)
                    GamepadAction.B -> onClose()
                    else -> return@onPad false
                }
                true
            }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.guides_title, gameName), color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleLarge)
        when {
            guides == null -> Text(stringResource(R.string.guides_looking), color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            else -> list.forEachIndexed { index, guide ->
                MenuRow(
                    title = guide.title,
                    subtitle = guide.source,
                    value = when {
                        place?.last == guide.id -> "Last read"
                        guide.format == GuideFormat.WEB && guide.path == null -> "Opens the browser"
                        else -> null
                    },
                    selected = index == cursor,
                    onClick = {
                        cursor = index
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
 * the place, X goes to the next mark, B closes and keeps the place. A text guide over [MAX_TEXT_BYTES] shows its start.
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
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(checkNotNull(guide.path))
                // At most MAX_TEXT_BYTES, read in a loop: InputStream.readNBytes is Android 13 and later.
                val bytes = ByteArray(minOf(file.length(), MAX_TEXT_BYTES.toLong()).toInt())
                var read = 0
                file.inputStream().use { input ->
                    while (read < bytes.size) {
                        val n = input.read(bytes, read, bytes.size - read)
                        if (n < 0) break
                        read += n
                    }
                }
                val text = String(bytes, 0, read, Charsets.UTF_8)
                text.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
            }.getOrDefault(listOf(context.getString(R.string.guide_unreadable)))
        }
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
    val document = remember(guide.id) { PdfDocument(guide.path) }
    DisposableEffect(document) { onDispose { document.close() } }
    var page by remember(guide.id) { mutableIntStateOf(start.page) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val width = context.resources.displayMetrics.widthPixels
    val bitmap by produceState<Bitmap?>(null, page) { value = withContext(Dispatchers.IO) { document.render(page, width) } }
    var restored by remember(guide.id) { mutableStateOf(false) }
    LaunchedEffect(bitmap) {
        if (bitmap != null && !restored) {
            restored = true
            scroll.scrollTo((start.fraction * scroll.maxValue).toInt())
        }
    }
    fun position() = GuidePosition(page, if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f)
    fun turn(to: Int) {
        val pages = document.pageCount
        if (pages <= 0) return
        page = to.coerceIn(0, pages - 1)
        scope.launch { scroll.scrollTo(0) }
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
                        restored = false
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

/** The first mark after [here], else null: X goes forward through the marks. Pure. */
internal fun nextMark(marks: List<GuideBookmark>, here: GuidePosition): GuideBookmark? =
    marks.sortedWith(compareBy({ it.position.page }, { it.position.fraction }))
        .firstOrNull { it.position.page > here.page || (it.position.page == here.page && it.position.fraction > here.fraction + 0.001f) }

/** One open PDF, its pages rendered one at a time at the screen's width. [pageCount] is -1 when it cannot be opened. */
private class PdfDocument(path: String?) {
    private val descriptor = runCatching { ParcelFileDescriptor.open(File(checkNotNull(path)), ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    private val renderer = descriptor?.let { runCatching { PdfRenderer(it) }.getOrNull() }
    val pageCount: Int = renderer?.pageCount ?: -1

    @Synchronized
    fun render(index: Int, width: Int): Bitmap? {
        val pdf = renderer ?: return null
        if (index !in 0 until pdf.pageCount) return null
        return runCatching {
            pdf.openPage(index).use { page ->
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }.getOrNull()
    }

    @Synchronized
    fun close() {
        runCatching { renderer?.close() }
        runCatching { descriptor?.close() }
    }
}

/** The most of a text guide read: about two million characters, more than any walkthrough. */
private const val MAX_TEXT_BYTES = 2_000_000

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
