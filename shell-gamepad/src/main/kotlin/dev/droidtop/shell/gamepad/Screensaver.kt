package dev.droidtop.shell.gamepad

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.LibraryEntry
import dev.droidtop.ui.shared.Motion
import kotlinx.coroutines.delay

/**
 * Idle screensaver: a slow slideshow of the library's own artwork (real
 * ES-DE has one, and a handheld left on a bright static menu is both a
 * burn-in risk on OLED panels and a battery cost).
 *
 * Deliberately NOT a screen blanker: Android's own display timeout
 * already does that and does it better (it actually powers the panel
 * down). This is the thing that happens BEFORE that -- the shell stops
 * showing a menu and starts showing the library. When it shows is
 * `ScreensaverPrefs` (runtime-common), the one definition the settings
 * row writes and the shell's timer observes.
 */
/**
 * One artwork at a time, crossfaded, changing every [SLIDE_SECONDS].
 * Any input dismisses it -- the caller owns that, because the shell
 * already sees every key before its own handlers do.
 */
private const val SLIDE_SECONDS = 12L

@Composable
internal fun Screensaver(entries: List<LibraryEntry>, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // What it shows, dimmed or not, and the name (Settings > Screensaver, Droidtop/tracker#726).
    val look by remember { dev.droidtop.library.settings.ScreensaverPrefs.lookChanges(context) }
        .collectAsState(initial = dev.droidtop.library.settings.ScreensaverLook())
    // Owns focus and input: any key or tap dismisses it. Without this
    // the shell's own key handler never runs, because entering this
    // branch leaves nothing in the tree focused and an unfocused
    // Compose tree receives no key events (confirmed on device: the
    // screensaver appeared and could not be dismissed).
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Screensaver") }
    val dismissModifier = Modifier
        .focusRequester(focus)
        .focusable()
        .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                onDismiss()
                true
            } else {
                // Swallow the matching key-up too, or it reaches
                // whatever the screensaver was covering.
                true
            }
        }
        .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    // Dim: the whole screensaver drawn darker over black (a dark room, and less burn-in on an OLED panel).
    val shade = if (look.dim) DIM_ALPHA else 1f

    when (look.content) {
        dev.droidtop.library.settings.ScreensaverContent.BLACK -> {
            Box(Modifier.fillMaxSize().background(Color.Black).then(dismissModifier))
            return
        }
        dev.droidtop.library.settings.ScreensaverContent.CLOCK -> {
            Box(Modifier.fillMaxSize().background(Color.Black).then(dismissModifier)) {
                ScreensaverClock(Modifier.alpha(shade))
            }
            return
        }
        else -> Unit
    }

    // Covers are the entries' own artwork; screenshots are their scraped screenshots, looked up on IO (a media folder
    // listing each), never in composition.
    val screenshots = look.content == dev.droidtop.library.settings.ScreensaverContent.SCREENSHOTS
    val slides by androidx.compose.runtime.produceState<List<Pair<LibraryEntry, String>>?>(null, entries, screenshots) {
        value = if (!screenshots) {
            entries.mapNotNull { entry -> entry.artworkUri?.let { entry to it.toString() } }
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                entries.asSequence()
                    .mapNotNull { entry -> entry.mediaForImageTypes(listOf("screenshot"))?.let { entry to it } }
                    .take(MAX_SCREENSHOTS)
                    .toList()
            }
        }
    }
    val withArt = slides ?: run {
        // Still being looked up: a black screen, so the menu is gone at once.
        Box(Modifier.fillMaxSize().background(Color.Black).then(dismissModifier))
        return
    }
    if (withArt.isEmpty()) {
        // Nothing scraped yet: still dim the shell rather than leaving a
        // menu burning in, but say why it is empty instead of showing a
        // black rectangle that looks like a crash.
        Box(
            Modifier.fillMaxSize().groundBackground().then(dismissModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (screenshots) "No screenshots scraped yet" else "Nothing scraped yet",
                color = MenuTokens.Placeholder,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.alpha(shade),
            )
        }
        return
    }
    var index by remember(withArt) { mutableStateOf(withArt.indices.random()) }
    LaunchedEffect(withArt) {
        while (true) {
            delay(SLIDE_SECONDS * 1000)
            // Random rather than sequential: a library browsed in order
            // would show the same opening run every single idle period.
            index = withArt.indices.random()
        }
    }
    val slide = withArt[index.coerceIn(withArt.indices)]

    Box(Modifier.fillMaxSize().background(Color.Black).then(dismissModifier)) {
        Box(Modifier.fillMaxSize().alpha(shade).groundBackground()) {
            Crossfade(targetState = slide, animationSpec = Motion.ambientFade(), label = "screensaver-slide") { shown ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = shown.second,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(32.dp).alpha(0.9f),
                    )
                }
            }
            if (look.showName) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        slide.first.title,
                        color = MenuTokens.Value,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = LocalShellWindow.current.edgePadding),
                    )
                }
            }
        }
    }
}

/**
 * The clock screensaver: the time, large, moved to a new spot each minute so no pixel holds the same light for long.
 */
@Composable
private fun ScreensaverClock(modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var spot by remember { mutableStateOf(Alignment.Center) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_STEP_MS - System.currentTimeMillis() % CLOCK_STEP_MS)
            now = System.currentTimeMillis()
            spot = CLOCK_SPOTS.random()
        }
    }
    val time = remember(now) { android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(now)) }
    Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = spot) {
        Text(time, color = MenuTokens.Value, style = MaterialTheme.typography.displayLarge, modifier = modifier)
    }
}

/** How dark Dim draws the screensaver. */
private const val DIM_ALPHA = 0.35f

/** How many screenshots a slideshow looks up at most: enough for variety, few enough to find quickly. */
private const val MAX_SCREENSHOTS = 200

private const val CLOCK_STEP_MS = 60_000L
private val CLOCK_SPOTS = listOf(
    Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd,
    Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd,
    Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd,
)
