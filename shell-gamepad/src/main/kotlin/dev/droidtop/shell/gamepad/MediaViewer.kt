package dev.droidtop.shell.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.theme.EsDeNavigationSounds

/**
 * Fullscreen browser for everything scraped for one game (real ES-DE has
 * a media viewer on the same idea). Scraping now pulls screenshots,
 * title screens, marquees, physical media and fan art alongside the box
 * art, and until this existed a theme decided which single one you ever
 * saw -- the rest were files on disk nobody could look at.
 *
 * Left/Right pages, B closes. Each image is shown whole (Fit, not Crop):
 * cropping a marquee or a physical-media disc to fill a 16:9 panel cuts
 * off the thing being looked at.
 */
@Composable
internal fun MediaViewer(title: String, media: List<Pair<String, String>>, onClose: () -> Unit) {
    if (media.isEmpty()) return
    var index by remember(media) { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusWhenAttached(focus, "Media viewer") }
    BackHandler { onClose() }

    val (label, path) = media[index.coerceIn(media.indices)]
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (GamepadKeyMap.actionFor(event.key)) {
                    GamepadAction.LEFT -> {
                        index = (index - 1 + media.size) % media.size
                        EsDeNavigationSounds.play("scroll")
                        true
                    }
                    GamepadAction.RIGHT -> {
                        index = (index + 1) % media.size
                        EsDeNavigationSounds.play("scroll")
                        true
                    }
                    GamepadAction.B, GamepadAction.BACK -> {
                        onClose()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = path,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(top = 56.dp, bottom = 72.dp),
        )
        Column(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("$label  (${index + 1}/${media.size})", color = MenuTokens.Value, style = MaterialTheme.typography.bodyMedium)
            MenuHint("Left/Right browses, B closes")
        }
    }
}
