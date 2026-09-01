package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.LibraryEntry

/**
 * The screen shown while a game is starting (real ES-DE has one too --
 * GuiLaunchScreen). Without it, launching freezes the shell mid-frame
 * for however long the emulator takes to appear, which reads as a hang
 * rather than as work happening: on this hardware a cold PS2 or Switch
 * emulator start is comfortably several seconds.
 *
 * The game's own art carries it, dimmed so the text stays legible over
 * anything, with the launch target named because "which emulator is
 * this even using" is the first question when a launch goes wrong.
 */
@Composable
internal fun LaunchScreen(entry: LibraryEntry, via: String? = null) {
    val transition = rememberInfiniteTransition(label = "launch-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "launch-pulse-alpha",
    )

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        entry.artworkUri?.let { art ->
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Dim rather than blur: a blur costs a render pass on
                // every frame of a screen whose whole job is to appear
                // instantly on a device that is already busy starting an
                // emulator.
                modifier = Modifier.fillMaxSize().alpha(0.35f),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 64.dp),
        ) {
            Text(
                entry.title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (via != null) "Starting with $via" else "Starting",
                color = MenuTokens.Value,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(pulse),
            )
        }
        // A quiet progress rail rather than a spinner: a spinner over
        // artwork reads as a loading failure, a rail reads as work.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(3.dp)
                .background(MenuTokens.Accent.copy(alpha = pulse * 0.7f)),
        )
    }
}
