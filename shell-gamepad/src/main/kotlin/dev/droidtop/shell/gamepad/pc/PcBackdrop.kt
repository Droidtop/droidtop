package dev.droidtop.shell.gamepad.pc

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import dev.droidtop.library.LibraryEntry
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.Motion

/**
 * The art-led backdrop of the PC Games home (docs/SPEC.md 7i, "Home art"):
 * the art of the game under the cursor, darkened toward the page's ground
 * and crossfading as the cursor moves, so the screen takes its colour from
 * the player's own games. Our own treatment, cheap on a handheld on
 * purpose:
 *
 * - **No live blur.** The art is decoded SMALL ([BACKDROP_WIDTH_PX] by
 *   [BACKDROP_HEIGHT_PX]) and scaled up under a heavy scrim, so it is soft
 *   by construction and costs one small bitmap, not a blur pass per frame.
 * - **Neighbours are preloaded** ([PreloadBackdrops]) with the very same
 *   request, so a step along a shelf finds the next backdrop already in the
 *   memory cache and the crossfade never waits on a decode.
 * - **No disk work from here**: the art is a URI the entry already carries
 *   and Coil decodes it off the main thread.
 *
 * With no art the backdrop draws nothing and the page's own ground shows.
 * Colours come from the theme tokens, never a literal.
 */
@Composable
internal fun PcBackdrop(art: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        Crossfade(targetState = art, animationSpec = tween(Motion.AmbientFadeMs), label = "pc backdrop") { shown ->
            if (shown != null) {
                AsyncImage(
                    model = remember(shown) { backdropRequest(context, shown) },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = BACKDROP_ART_ALPHA,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (art != null) {
            // Darker toward the bottom, where the shelves' text sits.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MenuTokens.Ground.copy(alpha = BACKDROP_SCRIM_TOP),
                                MenuTokens.Ground.copy(alpha = BACKDROP_SCRIM_BOTTOM),
                            ),
                        ),
                    ),
            )
        }
    }
}

/** Asks Coil for the backdrops of the games next to the cursor, so stepping to one shows it at once. */
@Composable
internal fun PreloadBackdrops(arts: List<String>) {
    val context = LocalContext.current
    LaunchedEffect(arts) {
        val loader = SingletonImageLoader.get(context)
        arts.forEach { loader.enqueue(backdropRequest(context, it)) }
    }
}

/** The art the backdrop shows for a game: its landscape hero when scraped, else its box art. */
internal fun LibraryEntry.backdropArt(): String? = heroUri ?: artworkUri

/**
 * The games whose backdrops are worth having ready: [reach] either side of
 * the cursor, never the cursor's own. Pure, for the tests.
 */
internal fun neighbourBackdrops(list: List<LibraryEntry>, index: Int, reach: Int = BACKDROP_PRELOAD_REACH): List<String> =
    (-reach..reach).filter { it != 0 }.mapNotNull { list.getOrNull(index + it)?.backdropArt() }.distinct()

/** One request for both the shown and the preloaded backdrop, so they share a memory-cache entry. */
private fun backdropRequest(context: Context, art: String): ImageRequest =
    ImageRequest.Builder(context).data(art).size(Size(BACKDROP_WIDTH_PX, BACKDROP_HEIGHT_PX)).build()

private const val BACKDROP_WIDTH_PX = 640
private const val BACKDROP_HEIGHT_PX = 360

/** How many games either side of the cursor are preloaded. */
internal const val BACKDROP_PRELOAD_REACH = 2

/**
 * How much of the art shows through, and the ground laid over it, top and
 * bottom: lighter than before (the Steam original measures about half
 * brightness at 70% opacity), still heaviest where the shelves' text sits.
 */
private const val BACKDROP_ART_ALPHA = 0.7f
private const val BACKDROP_SCRIM_TOP = 0.35f
private const val BACKDROP_SCRIM_BOTTOM = 0.85f
