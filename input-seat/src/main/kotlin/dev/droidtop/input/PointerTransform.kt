package dev.droidtop.input

/**
 * How the compositor's output image ends up occupying the Android view that
 * shows it. This is not a preference — it has to match what :host-bridge's
 * present path actually does, or every touch lands somewhere other than
 * where the user aimed.
 *
 * [STRETCH] is what the present path does today: `presentPrimaryOutput`
 * calls `ANativeWindow_setBuffersGeometry(output_width, output_height, ...)`
 * and SurfaceFlinger then scales that buffer to fill the SurfaceView's
 * layout bounds. There is no letterboxing anywhere in that path, and the
 * scale is independently chosen per axis when the aspect ratios differ.
 *
 * [LETTERBOX] is here because the moment the present path grows an
 * aspect-preserving mode (a 16:9 container output on a 20:9 handheld panel
 * is the obvious case), the input transform has to grow the matching mode
 * in the same commit or input silently goes wrong. Wiring the mode through
 * now, tested, is cheaper than discovering the asymmetry later.
 */
enum class SurfaceFit { STRETCH, LETTERBOX }

/** A point in the compositor output's own pixel space, plus the extent it is relative to. */
data class OutputPoint(val x: Float, val y: Float, val extentWidth: Int, val extentHeight: Int)

/**
 * Maps a point in Android view space onto the compositor output's pixel
 * space, which is what `zwlr_virtual_pointer_v1.motion_absolute` wants: an
 * (x, y) together with the (x_extent, y_extent) it should be read against.
 *
 * A note on why [outputWidth]/[outputHeight] being approximate is safe in
 * [SurfaceFit.STRETCH]: the compositor divides x by x_extent, so only the
 * ratio survives. Scaling view coordinates into a claimed output extent and
 * handing the compositor that same extent produces the identical fraction
 * no matter what the true output size is. That matters because the real
 * output size is only known on the native side (it arrives in the
 * screencopy `buffer` event); Kotlin only has the Android display metrics
 * in [dev.droidtop.runtime.DisplayOutput]. Under STRETCH that discrepancy
 * cannot produce an off-by-scale error. Under [SurfaceFit.LETTERBOX] it
 * can, because the aspect ratio is then load-bearing — so if the present
 * path ever letterboxes, the true output size has to be plumbed up from
 * native rather than guessed from the panel.
 */
data class PointerTransform(
    val viewWidth: Int,
    val viewHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val fit: SurfaceFit = SurfaceFit.STRETCH,
) {
    private val valid: Boolean
        get() = viewWidth > 0 && viewHeight > 0 && outputWidth > 0 && outputHeight > 0

    /**
     * Returns null when the transform has no usable geometry yet (the
     * surface has not been laid out), or when [viewX]/[viewY] falls in a
     * letterbox bar, where there is no output pixel under the finger and
     * inventing one would teleport the cursor to an edge.
     */
    fun map(viewX: Float, viewY: Float): OutputPoint? {
        if (!valid) return null
        return when (fit) {
            SurfaceFit.STRETCH -> OutputPoint(
                x = clamp(viewX * outputWidth / viewWidth, outputWidth),
                y = clamp(viewY * outputHeight / viewHeight, outputHeight),
                extentWidth = outputWidth,
                extentHeight = outputHeight,
            )

            SurfaceFit.LETTERBOX -> {
                val scale = minOf(viewWidth.toFloat() / outputWidth, viewHeight.toFloat() / outputHeight)
                val contentWidth = outputWidth * scale
                val contentHeight = outputHeight * scale
                val offsetX = (viewWidth - contentWidth) / 2f
                val offsetY = (viewHeight - contentHeight) / 2f
                if (viewX < offsetX || viewX > offsetX + contentWidth) return null
                if (viewY < offsetY || viewY > offsetY + contentHeight) return null
                OutputPoint(
                    x = clamp((viewX - offsetX) / scale, outputWidth),
                    y = clamp((viewY - offsetY) / scale, outputHeight),
                    extentWidth = outputWidth,
                    extentHeight = outputHeight,
                )
            }
        }
    }

    /**
     * Relative view-space deltas scaled into output space, for pointer
     * sources that report movement rather than position (a captured
     * trackpad, a relative mouse). Uses the same per-axis scale as [map],
     * so a drag of half the view's width moves the cursor half the output's
     * width in both fit modes.
     */
    fun mapDelta(dx: Float, dy: Float): Pair<Float, Float>? {
        if (!valid) return null
        return when (fit) {
            SurfaceFit.STRETCH ->
                dx * outputWidth / viewWidth to dy * outputHeight / viewHeight

            SurfaceFit.LETTERBOX -> {
                val scale = minOf(viewWidth.toFloat() / outputWidth, viewHeight.toFloat() / outputHeight)
                dx / scale to dy / scale
            }
        }
    }

    // The protocol's extent is exclusive, so the last addressable column is
    // extent - 1; clamping to `extent` itself would put the cursor one pixel
    // outside the output on every swipe that runs off the right/bottom edge.
    private fun clamp(value: Float, extent: Int): Float =
        value.coerceIn(0f, (extent - 1).toFloat())
}
