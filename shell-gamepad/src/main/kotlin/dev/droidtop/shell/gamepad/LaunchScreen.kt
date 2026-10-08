package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
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
 *
 * Started from a game page's Play, the screen grows out of that button in
 * the launch colour and appears as the colour fades ([LaunchFlood], behind
 * the Animations switch); started any other way it simply appears. With
 * motion off the waiting line holds still instead of pulsing.
 */
@Composable
internal fun LaunchScreen(entry: LibraryEntry, via: String? = null) {
    val origin = remember(entry.id) { FloodOrigin.takeLaunch()?.takeIf { Motion.enabled } }
    var covered by remember(entry.id) { mutableStateOf(origin == null) }
    val reveal = remember(entry.id) { Animatable(if (origin == null) 1f else 0f) }
    LaunchedEffect(covered) { if (covered) reveal.animateTo(1f, Motion.tw(LAUNCH_FLOOD_FADE_MS)) }
    val pulse = rememberLaunchPulse()

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }.groundBackground(),
            contentAlignment = Alignment.Center,
        ) {
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
                    color = MenuTokens.OnSurface,
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
                    modifier = Modifier.graphicsLayer { alpha = pulse.value },
                )
            }
            // A quiet progress rail rather than a spinner: a spinner over
            // artwork reads as a loading failure, a rail reads as work.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .graphicsLayer { alpha = pulse.value * 0.7f }
                    .background(MenuTokens.Accent),
            )
        }
        if (origin != null) {
            LaunchFlood(from = origin, fill = MenuTokens.Launch, cornerAtRest = Corners.CrispRadius, onCovered = { covered = true })
        }
    }
}

/** The waiting line's pulse, read in the layer phase; held at full with motion off. */
@Composable
private fun rememberLaunchPulse(): State<Float> {
    if (!Motion.enabled) return remember { mutableStateOf(1f) }
    val transition = rememberInfiniteTransition(label = "launch-pulse")
    return transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        // An ambient role (Motion.AmbientPulseMs); a repeating spec has no snap to fall back to, so motion off
        // does not build it at all (above).
        animationSpec = infiniteRepeatable(tween(Motion.AmbientPulseMs), RepeatMode.Reverse),
        label = "launch-pulse-alpha",
    )
}
