package dev.droidtop.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.droidtop.library.LibraryEntry
import kotlinx.coroutines.delay

/**
 * The companion's idle state: a slow rotation of artwork from the user's
 * own library, rather than a placeholder.
 *
 * Directly from iiSU's `"Show Hero on Idle Bottom Screen"` (docs/SPEC.md
 * §4d). The screen this replaces drew the word "droidtop" on black, which
 * is the one thing a glanceable surface must never do: occupy a whole
 * panel and say nothing.
 *
 * Deliberately calm — a long dwell and a slow crossfade, per the
 * peripheral-display research §4d cites: it should inform without
 * pulling attention off the primary screen. A fast slideshow on a second
 * screen is a distraction, not an ambient display.
 */
@Composable
internal fun CompanionIdle(entries: List<LibraryEntry>) {
    // Only entries that actually have art; a rotation through blank
    // frames would be worse than showing nothing at all.
    val withArt = remember(entries) { entries.filter { !it.artworkUri.isNullOrBlank() } }

    if (withArt.isEmpty()) {
        // Nothing scanned yet, or nothing scraped. Draw the ground and
        // let the status bar above be the content -- never a wordmark.
        Box(modifier = Modifier.fillMaxSize())
        return
    }

    var index by remember(withArt) { mutableStateOf(0) }
    LaunchedEffect(withArt) {
        while (true) {
            delay(DWELL_MS)
            index = (index + 1) % withArt.size
        }
    }

    val entry = withArt[index]
    Crossfade(
        targetState = entry,
        animationSpec = tween(durationMillis = FADE_MS),
        label = "companion-idle-art",
    ) { shown ->
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = shown.artworkUri,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // Dimmed: this sits BEHIND the status bar and
                    // notifications, which have to stay readable over it.
                    .alpha(0.55f),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 24.dp, vertical = 36.dp),
            ) {
                Text(
                    shown.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                val subtitle = shown.systemId
                    ?.let { dev.droidtop.library.consoles.PlatformsDatabase.displayNameOrNull(it) }
                    ?: shown.pcInfo?.source
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

// Long enough to read as ambient rather than as a slideshow demanding
// attention (the §4d "calm technology" constraint), short enough that a
// glance later shows something new.
private const val DWELL_MS = 12_000L
private const val FADE_MS = 1_200
