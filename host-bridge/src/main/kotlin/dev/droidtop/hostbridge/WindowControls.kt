package dev.droidtop.hostbridge

import kotlin.math.max
import kotlin.math.min

/** A box on an Android surface, in its view pixels: [left] and [top] inside it, [right] and [bottom] just past it. */
data class ViewBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    fun intersects(other: ViewBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/** A title-bar button droidtop draws over a sway window (docs/SPEC.md 2b "Window controls", Droidtop/tracker#910). */
enum class WindowButton { MINIMIZE, MAXIMIZE, CLOSE }

/**
 * One drawn button: the window it is on, what it does, the [visual] droidtop draws (title-bar height) and the
 * [press] area that takes a finger or a mouse, at least a finger's size.
 */
data class ControlButton(val window: WindowFrame, val kind: WindowButton, val visual: ViewBox, val press: ViewBox)

/** What a press on the desktop's surface is, when it is not for the compositor as it stands. */
sealed interface ControlHit {
    data class Button(val button: ControlButton) : ControlHit

    /**
     * A press in the resize band of the window [conId], on its [edges] ([WindowControls.LEFT] and the rest, or-ed).
     * sway's resize starts with the pointer at ([grabX], [grabY]), a point inside the window, since sway resizes
     * the window under the pointer; the finger's motion is then followed from there.
     */
    data class Resize(val conId: Long, val edges: Int, val grabX: Float, val grabY: Float) : ControlHit
}

/**
 * The title-bar buttons and resize bands on one surface showing one sway output (Droidtop/tracker#910), in the
 * surface's view pixels, and what a press at a point is ([hit]). Built by [layout] from the layout tree
 * ([SwayFrames]); pure, so it is tested on the JVM (WindowControlsTest).
 *
 * Who takes a press: a button where it is drawn; then, from the top down, each floating window on its own (the top
 * one first), then the tiled windows together, as tiled windows sit side by side rather than over each other. Within
 * that, a button's press area first, then
 * a window the press is inside (its border is band, the rest is the window's own and goes to the compositor), then
 * the band outside a window. So a window's band reaches over what is below it but never over a window beside or
 * above it, and never into a window's own content past its border.
 */
class WindowControls internal constructor(
    /** The buttons to draw, bottom window first. */
    val buttons: List<ControlButton>,
    private val layers: List<List<Target>>,
) {
    /** What a press at ([x], [y]) of the surface is; null for the compositor, exactly as without this. */
    fun hit(x: Float, y: Float): ControlHit? {
        // A button where it is drawn is that button, whatever band reaches over it: it is drawn only uncovered.
        buttons.lastOrNull { it.visual.contains(x, y) }?.let { return ControlHit.Button(it) }
        for (layer in layers) {
            for (target in layer) target.buttons.firstOrNull { it.press.contains(x, y) }?.let { return ControlHit.Button(it) }
            for (target in layer) {
                if (!target.box.contains(x, y)) continue
                return target.innerBand(x, y)
            }
            for (target in layer) target.outerBand(x, y)?.let { return it }
        }
        return null
    }

    /** The button at ([x], [y]), for a press let go there: the same one it started on, or not. */
    fun buttonAt(x: Float, y: Float): ControlButton? = (hit(x, y) as? ControlHit.Button)?.button

    /** One window on this surface: its whole frame [box], its border's width and the band's reach, and its buttons. */
    internal class Target(
        val window: WindowFrame,
        val box: ViewBox,
        val hasTitleBar: Boolean,
        /** The border, the band's part inside the window. */
        val inward: Float,
        /** The band's part outside the window. */
        val outward: Float,
        /** How far along an edge from a corner a press counts as the corner. */
        val corner: Float,
        /** One logical pixel, in view pixels: how far inside the window the pointer is held. */
        val inset: Float,
        val buttons: List<ControlButton>,
    ) {
        fun innerBand(x: Float, y: Float): ControlHit? {
            var edges = 0
            if (x - box.left < inward) edges = edges or LEFT
            if (box.right - x <= inward) edges = edges or RIGHT
            if (!hasTitleBar && y - box.top < inward) edges = edges or TOP
            if (box.bottom - y <= inward) edges = edges or BOTTOM
            if (edges == 0) return null
            return resize(x, y, withCorner(x, y, edges))
        }

        fun outerBand(x: Float, y: Float): ControlHit? {
            if (x < box.left - outward || x >= box.right + outward || y < box.top - outward || y >= box.bottom + outward) return null
            var edges = 0
            if (x < box.left) edges = edges or LEFT
            if (x >= box.right) edges = edges or RIGHT
            if (y < box.top) edges = edges or TOP
            if (y >= box.bottom) edges = edges or BOTTOM
            if (edges == 0) return null
            return resize(x, y, withCorner(x, y, edges))
        }

        /** A press near a corner along one edge is that corner. */
        private fun withCorner(x: Float, y: Float, edges: Int): Int {
            var all = edges
            if ((edges and (LEFT or RIGHT)) != 0) {
                if (y - box.top < corner) all = all or TOP
                if (box.bottom - y <= corner) all = all or BOTTOM
            }
            if ((edges and (TOP or BOTTOM)) != 0) {
                if (x - box.left < corner) all = all or LEFT
                if (box.right - x <= corner) all = all or RIGHT
            }
            // A window smaller than two corners: the nearer side of each pair.
            if ((all and (LEFT or RIGHT)) == (LEFT or RIGHT)) all = all and (if (x - box.left < box.right - x) RIGHT else LEFT).inv()
            if ((all and (TOP or BOTTOM)) == (TOP or BOTTOM)) all = all and (if (y - box.top < box.bottom - y) BOTTOM else TOP).inv()
            return all
        }

        private fun resize(x: Float, y: Float, edges: Int): ControlHit.Resize {
            val grabX = x.coerceIn(box.left + inset, max(box.left + inset, box.right - inset))
            val grabY = y.coerceIn(box.top + inset, max(box.top + inset, box.bottom - inset))
            return ControlHit.Resize(window.conId, edges, grabX, grabY)
        }
    }

    companion object {
        const val LEFT = 1
        const val RIGHT = 2
        const val TOP = 4
        const val BOTTOM = 8

        val NONE = WindowControls(emptyList(), emptyList())

        /**
         * The controls on a [viewWidth] x [viewHeight] surface showing the output [outputName] ([SwayFrames.output]:
         * "" or null for a primary sway has not named, with one output), where a press target is [targetPx] view
         * pixels (a finger's size, ShellWindow's).
         *
         * Geometry: the output's box in the layout ([SwayFrames.outputs], the tree's own) is the whole surface, so a
         * layout point maps by its offset into that box times the surface's pixels per logical pixel on each axis. That
         * takes in Desktop scale, the render resolution and where a second output sits in the layout alike, since the
         * surface always shows exactly its output's box (DesktopScale sizes the output to the surface).
         *
         * Buttons: minimise, maximise or restore, and close, at the right end of each title bar, close last. Each is
         * drawn as a square of the title bar's height, in a slot as wide as a press target, and pressed anywhere in
         * that slot over a press target's height centred on the title bar (kept on the surface). A title bar with no
         * room for the three and a slot's width of title left to drag the window by gets close alone, and one with no
         * room for that gets none. A button with any part off the surface or under a window above is not drawn.
         *
         * Band: the window's border inside it, and outside it the rest of a press target's width (at least half of
         * one), along every edge and corner. A title bar's top has no band inside it: it moves the window.
         */
        fun layout(frames: SwayFrames, outputName: String?, viewWidth: Int, viewHeight: Int, targetPx: Float): WindowControls {
            val output = frames.output(outputName) ?: return NONE
            val box = frames.outputs[output] ?: return NONE
            if (viewWidth <= 0 || viewHeight <= 0 || box.width <= 0 || box.height <= 0 || !(targetPx > 0f)) return NONE
            val sx = viewWidth.toFloat() / box.width
            val sy = viewHeight.toFloat() / box.height
            fun map(r: SwayRect) = ViewBox((r.x - box.x) * sx, (r.y - box.y) * sy, (r.x - box.x + r.width) * sx, (r.y - box.y + r.height) * sy)
            val surface = ViewBox(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())

            val windows = frames.on(output)
            val boxes = windows.map { map(it.frame) }
            val targets = windows.mapIndexed { index, window ->
                val frame = boxes[index]
                val above = boxes.subList(index + 1, boxes.size)
                val buttons = window.titleBar?.let { bar ->
                    buttons(window, map(bar), targetPx, surface).filter { button -> above.none { it.intersects(button.visual) } }
                }.orEmpty()
                val inward = window.border * min(sx, sy)
                Target(
                    window = window,
                    box = frame,
                    hasTitleBar = window.titleBar != null,
                    inward = inward,
                    outward = max(targetPx - inward, targetPx / 2),
                    corner = targetPx,
                    inset = max(1f, max(sx, sy)),
                    buttons = buttons,
                )
            }
            val floating = targets.filter { it.window.floating }.reversed().map { listOf(it) }
            val tiled = targets.filterNot { it.window.floating }.reversed()
            val layers = if (tiled.isEmpty()) floating else floating + listOf(tiled)
            return WindowControls(targets.flatMap { it.buttons }, layers)
        }

        /** The buttons on a title bar at [bar], or none when it has no room for them or any is off the [surface]. */
        internal fun buttons(window: WindowFrame, bar: ViewBox, targetPx: Float, surface: ViewBox): List<ControlButton> {
            val size = bar.height
            if (!(size > 0f)) return emptyList()
            val slot = max(size, targetPx)
            val kinds = when {
                bar.width >= slot * 4 -> listOf(WindowButton.MINIMIZE, WindowButton.MAXIMIZE, WindowButton.CLOSE)
                bar.width >= slot * 2 -> listOf(WindowButton.CLOSE)
                else -> return emptyList()
            }
            val pressHeight = max(size, targetPx)
            var pressTop = bar.top + (size - pressHeight) / 2
            if (pressTop + pressHeight > surface.bottom) pressTop = surface.bottom - pressHeight
            if (pressTop < surface.top) pressTop = surface.top
            val buttons = kinds.mapIndexed { index, kind ->
                val slotLeft = bar.right - slot * (kinds.size - index)
                val visualLeft = slotLeft + (slot - size) / 2
                ControlButton(
                    window = window,
                    kind = kind,
                    visual = ViewBox(visualLeft, bar.top, visualLeft + size, bar.top + size),
                    press = ViewBox(slotLeft, pressTop, slotLeft + slot, pressTop + pressHeight),
                )
            }
            val inside = buttons.all {
                it.visual.left >= surface.left && it.visual.top >= surface.top &&
                    it.visual.right <= surface.right && it.visual.bottom <= surface.bottom
            }
            return if (inside) buttons else emptyList()
        }
    }
}
