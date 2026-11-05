package dev.droidtop.hostbridge

import androidx.annotation.Keep

/** A box in the compositor's layout, in logical pixels. */
data class LayoutBox(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * One compositor output's place in the layout, as wlr-output-management last reported it (the native client's
 * OutputPlacement, read at the protocol's `done`). [x]/[y] are its position in logical pixels; [modeWidth] x
 * [modeHeight] its current mode in pixels (0 while unknown); [transform] is wl_output.transform; [scale] how many
 * pixels one logical pixel is.
 *
 * Constructed from JNI (hostbridge_jni.cpp's nativeOutputPlacements) by constructor signature, so [Keep] for the
 * same reason as [Toplevel].
 */
@Keep
data class OutputPlacement(
    val name: String,
    val enabled: Boolean,
    val x: Int,
    val y: Int,
    val modeWidth: Int,
    val modeHeight: Int,
    val transform: Int,
    val scale: Double,
) {
    /**
     * The output's box in the layout, worked out as wlroots does (wlr_output_layout.c's output box, from
     * wlr_output_effective_resolution): the mode, its sides swapped for a 90 or 270 degree transform (the odd
     * wl_output.transform values), each side divided by the scale in single precision and truncated. Null for an
     * output that is not in the layout (disabled) or whose size is not known yet.
     *
     * The compositor's own scale is a float and the protocol sends it as a 24.8 fixed-point number, so for a scale
     * that is not a multiple of 1/256 this box can be a logical pixel off the compositor's own.
     */
    fun box(): LayoutBox? {
        if (!enabled || modeWidth <= 0 || modeHeight <= 0 || !(scale > 0.0)) return null
        val sideways = transform % 2 != 0
        val width = (if (sideways) modeHeight else modeWidth).toFloat() / scale.toFloat()
        val height = (if (sideways) modeWidth else modeHeight).toFloat() / scale.toFloat()
        if (width.toInt() <= 0 || height.toInt() <= 0) return null
        return LayoutBox(x, y, width.toInt(), height.toInt())
    }
}

/**
 * The four numbers of one zwlr_virtual_pointer_v1.motion_absolute: the pointer goes to [x] / [extentWidth] and
 * [y] / [extentHeight] of the way across the compositor's layout. Each is an unsigned 32-bit value on the wire.
 */
data class AbsoluteMotion(val x: Long, val y: Long, val extentWidth: Long, val extentHeight: Long)

/**
 * Where a point on an Android surface showing one compositor output lands in the compositor's layout
 * (Droidtop/tracker#908, step D1b). Pure, so it is tested on the JVM (OutputLayoutTest).
 *
 * host-bridge's virtual pointer is not tied to an output, so wlroots maps its absolute motion onto the box around
 * every output in the layout (wlr_cursor.c's wlr_cursor_absolute_to_layout_coords with no mapping). With one output
 * that box is the output; with more, a point has to be placed inside the right output's part of it, which is what
 * [absoluteMotion] does.
 */
object OutputLayout {
    /** The largest value motion_absolute carries (a uint32). */
    const val MAX_PROTOCOL_VALUE = 0xFFFF_FFFFL

    /** The box around every output in the layout, as wlr_output_layout_get_box(layout, NULL) gives it; null when none is. */
    fun layoutBox(placements: List<OutputPlacement>): LayoutBox? {
        val boxes = placements.mapNotNull { it.box() }
        if (boxes.isEmpty()) return null
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        val right = boxes.maxOf { it.x.toLong() + it.width }
        val bottom = boxes.maxOf { it.y.toLong() + it.height }
        return LayoutBox(left, top, (right - left).toInt(), (bottom - top).toInt())
    }

    /**
     * The motion that puts the pointer at ([x], [y]) of a [extentWidth] x [extentHeight] surface showing the output
     * named [outputName]: the same fraction of the way across that output, wherever it sits in the layout. A point
     * outside the surface is held on the output's edge, so it never reaches a neighbouring output. Null when no
     * output of that name is in the layout, or the extent is empty.
     *
     * In whole numbers, so the compositor's division gives the point back exactly: the layout is divided into
     * [extentWidth] steps per logical pixel (fewer only when that would pass the uint32 limit), and the output's
     * part of it starts at its offset in the layout times that.
     */
    fun absoluteMotion(
        placements: List<OutputPlacement>,
        outputName: String,
        x: Double,
        y: Double,
        extentWidth: Int,
        extentHeight: Int,
    ): AbsoluteMotion? {
        if (extentWidth <= 0 || extentHeight <= 0) return null
        val output = placements.firstOrNull { it.name == outputName }?.box() ?: return null
        val layout = layoutBox(placements) ?: return null
        val (motionX, motionWidth) = axis(x, extentWidth, output.x - layout.x, output.width, layout.width)
        val (motionY, motionHeight) = axis(y, extentHeight, output.y - layout.y, output.height, layout.height)
        return AbsoluteMotion(motionX, motionY, motionWidth, motionHeight)
    }

    /**
     * One axis: [point] of [extent] on the surface, onto an output [outputSize] long that starts [offset] into a
     * layout [layoutSize] long. Returns the protocol value and the protocol extent.
     */
    private fun axis(point: Double, extent: Int, offset: Int, outputSize: Int, layoutSize: Int): Pair<Long, Long> {
        val steps = minOf(extent.toLong(), MAX_PROTOCOL_VALUE / layoutSize)
        val start = offset.toLong() * steps
        val span = outputSize.toLong() * steps
        val fraction = if (point.isNaN()) 0.0 else point.coerceIn(0.0, extent.toDouble()) / extent
        val value = start + kotlin.math.floor(fraction * span).toLong()
        return value.coerceIn(start, start + span - 1) to layoutSize.toLong() * steps
    }
}
