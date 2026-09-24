package dev.droidtop.shell.gamepad.theme

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import dev.droidtop.library.theme.EsDeAnimationDirection
import dev.droidtop.library.theme.esDeAnimationFrame
import dev.droidtop.library.theme.esDeLottiePacingMs
import dev.droidtop.library.theme.esDeLottieSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/**
 * A Lottie (`.json`) `animation` element: ES-DE's `LottieAnimComponent`,
 * which plays through rlottie, played here through the Lottie library
 * the launcher shell already ships.
 *
 * What is ES-DE's and where it comes from:
 *
 *  * Size is the animation's own, not the image element's: see
 *    [esDeLottieSize] for `size`/`maxSize`/`scaleFactor`, which need the
 *    file's viewport, so nothing is placed until the file has loaded.
 *  * Frames advance on ES-DE's own clock, not Lottie's: the pace is the
 *    file's frame rate divided by `speed` ([esDeLottiePacingMs]) and the
 *    frame index comes from [esDeAnimationFrame], the same bookkeeping
 *    ES-DE's GIF and Lottie components share, so `direction` and
 *    `iterationCount` mean exactly what they do for a GIF.
 *  * Each frame is rasterised into a bitmap at the (possibly
 *    `scaleFactor`-reduced) raster size, as rlottie renders into its
 *    surface (LottieAnimComponent.cpp:190-192), and that bitmap is drawn
 *    at the element's size through [filterQuality] -- which is how
 *    `interpolation` and `scaleFactor` reach the screen at all -- with
 *    the shared colour pipeline as [colorFilter].
 *  * A file that will not parse draws nothing, and says so once, as
 *    ES-DE's "Couldn't parse Lottie animation file" does (:123-126).
 *
 * Not carried over: ES-DE's frame cache (:525-540) exists because rlottie
 * rasterises on the CPU per frame; Lottie does the same work on the draw
 * call, and memory on a handheld is better spent elsewhere. And, like the
 * GIF path, the animation keeps playing while a menu is open, where
 * ES-DE holds its frame (:474-479).
 */
@Composable
internal fun EsDeLottieAnimation(
    path: String,
    size: kotlin.Pair<Float, Float>?,
    maxSize: kotlin.Pair<Float, Float>?,
    scaleFactor: Float?,
    direction: EsDeAnimationDirection,
    speed: Float?,
    iterationCount: Int,
    filterQuality: FilterQuality,
    colorFilter: ColorFilter?,
    viewWidth: Dp,
    viewHeight: Dp,
    place: (width: Dp, height: Dp) -> Modifier,
) {
    val composition by produceState<LottieComposition?>(null, path) {
        value = withContext(Dispatchers.IO) { loadLottie(path) }
    }
    val loaded = composition ?: return
    val density = LocalDensity.current
    val layout = remember(loaded, size, maxSize, scaleFactor, viewWidth, viewHeight, density) {
        esDeLottieSize(
            size = size,
            maxSize = maxSize,
            scaleFactor = scaleFactor,
            viewportWidth = loaded.bounds.width(),
            viewportHeight = loaded.bounds.height(),
            screenWidth = with(density) { viewWidth.toPx() },
            screenHeight = with(density) { viewHeight.toPx() },
        )
    }
    if (layout.rasterWidth <= 0 || layout.rasterHeight <= 0) return
    val drawable = remember(loaded) { LottieDrawable().apply { setComposition(loaded) } }
    val bitmap = remember(layout.rasterWidth, layout.rasterHeight) {
        Bitmap.createBitmap(layout.rasterWidth, layout.rasterHeight, Bitmap.Config.ARGB_8888)
    }
    val rasterCanvas = remember(bitmap) { android.graphics.Canvas(bitmap) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val totalFrames = loaded.durationFrames.toInt()
    val pacingMs = esDeLottiePacingMs(loaded.frameRate, speed)

    var elapsedMs by remember(loaded) { mutableLongStateOf(0L) }
    LaunchedEffect(loaded) {
        val start = withFrameNanos { it }
        while (true) {
            elapsedMs = withFrameNanos { (it - start) / 1_000_000L }
        }
    }
    val width = with(density) { layout.width.toDp() }
    val height = with(density) { layout.height.toDp() }
    Canvas(modifier = place(width, height)) {
        // Read in the draw phase, so a new frame redraws without recomposing.
        val frame = esDeAnimationFrame(elapsedMs, totalFrames, pacingMs, direction, iterationCount)
        drawable.setBounds(0, 0, layout.rasterWidth, layout.rasterHeight)
        drawable.frame = (loaded.startFrame + frame).toInt()
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        drawable.draw(rasterCanvas)
        drawImage(
            image = image,
            dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
            filterQuality = filterQuality,
            colorFilter = colorFilter,
        )
    }
}

private fun loadLottie(path: String): LottieComposition? {
    // No cache key: a re-downloaded theme must not be served the old file.
    val result = runCatching {
        FileInputStream(path).use { LottieCompositionFactory.fromJsonInputStreamSync(it, null) }
    }.getOrNull()
    val composition = result?.value
    if (composition == null) {
        android.util.Log.w("droidtop.EsDeTheme", "Couldn't parse Lottie animation file $path", result?.exception)
    }
    return composition
}
