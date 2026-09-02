package dev.droidtop.shell.gamepad

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.LibraryEntry
import kotlinx.coroutines.delay
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Idle screensaver: a slow slideshow of the library's own artwork (real
 * ES-DE has one, and a handheld left on a bright static menu is both a
 * burn-in risk on OLED panels and a battery cost).
 *
 * Deliberately NOT a screen blanker: Android's own display timeout
 * already does that and does it better (it actually powers the panel
 * down). This is the thing that happens BEFORE that -- the shell stops
 * showing a menu and starts showing the library.
 */
enum class ScreensaverMode(val label: String, val idleSeconds: Int) {
    OFF("Off", 0),
    AFTER_2("After 2 minutes", 120),
    AFTER_5("After 5 minutes", 300),
    AFTER_10("After 10 minutes", 600),
}

object ScreensaverPrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_MODE = "droidtop_screensaver_mode"

    // OFF by default (directed): a slideshow that appears on its own
    // is an interruption unless somebody asked for it.
    fun mode(context: Context): ScreensaverMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null) ?: return ScreensaverMode.OFF
        return runCatching { ScreensaverMode.valueOf(raw) }.getOrDefault(ScreensaverMode.OFF)
    }

    fun setMode(context: Context, mode: ScreensaverMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }
}

/**
 * One artwork at a time, crossfaded, changing every [SLIDE_SECONDS].
 * Any input dismisses it -- the caller owns that, because the shell
 * already sees every key before its own handlers do.
 */
private const val SLIDE_SECONDS = 12L

@Composable
internal fun Screensaver(entries: List<LibraryEntry>, onDismiss: () -> Unit) {
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

    val withArt = remember(entries) { entries.filter { it.artworkUri != null } }
    if (withArt.isEmpty()) {
        // Nothing scraped yet: still dim the shell rather than leaving a
        // menu burning in, but say why it is empty instead of showing a
        // black rectangle that looks like a crash.
        Box(
            Modifier.fillMaxSize().background(Color.Black).then(dismissModifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Nothing scraped yet",
                color = MenuTokens.Placeholder,
                style = MaterialTheme.typography.bodyMedium,
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
    val entry = withArt[index.coerceIn(withArt.indices)]

    Box(Modifier.fillMaxSize().background(Color.Black).then(dismissModifier)) {
        Crossfade(targetState = entry, animationSpec = tween(1200), label = "screensaver-slide") { shown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = shown.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(32.dp).alpha(0.9f),
                )
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                entry.title,
                color = MenuTokens.Value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        }
    }
}
