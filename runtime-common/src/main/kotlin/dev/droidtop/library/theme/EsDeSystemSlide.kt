package dev.droidtop.library.theme

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The system view's element layer SLIDING between systems
 * (`SystemView::renderElements`, SystemView.cpp:1565-1745).
 *
 * ES-DE does not animate this layer on a timer of its own. The primary
 * component (the carousel, grid or textlist) owns a continuous camera
 * offset -- `mCamOffset`, the fractional cursor position it is already
 * scrolling -- and every system's element set is drawn translated by
 * `(i - mCamOffset) * mSize` along the primary's own axis
 * (SystemView.cpp:1618-1637), clipped to its own slot. So the neighbouring
 * systems' elements slide in from the sides in lockstep with the carousel,
 * which keeps scrolling untouched (:204).
 *
 * All of that is arithmetic, so it lives here rather than in the renderer.
 */
object EsDeSystemSlide {

    /**
     * Which axis the layer slides along, SystemView.cpp:1618-1637: a
     * `horizontal` or `horizontalWheel` CAROUSEL slides along x by the
     * view's width; a vertical carousel, a grid and a textlist all slide
     * along y by its height.
     *
     * [carouselType] is the `carousel` element's own `type` property; an
     * absent or unrecognised value is `horizontal`, which is ES-DE's own
     * default (CarouselComponent.h's constructor, and the unmatched branch
     * of its `applyTheme`).
     */
    fun slidesHorizontally(primaryType: String?, carouselType: String?): Boolean =
        primaryType == "carousel" && carouselType != "vertical" && carouselType != "verticalWheel"

    /**
     * Which systems' element sets are drawn, SystemView.cpp:1572-1580 and
     * the `isAnimationPlaying(0) || index == mPrimary->getCursor()` guard
     * at :1620: the system under the camera always, and its two
     * neighbours as well while the carousel is actually moving.
     *
     * The returned indices are RAW -- they can fall outside the system
     * list, exactly as ES-DE's loop counter does; [wrap] maps them onto a
     * real system the way ES-DE's own two while loops do.
     */
    fun renderedIndices(camOffset: Float, systemCount: Int, animating: Boolean): List<Int> {
        if (systemCount <= 0) return emptyList()
        // static_cast<int> truncates, and mCamOffset is never negative.
        val center = camOffset.toInt()
        if (!animating || systemCount == 1) return listOf(center)
        return listOf(center - 1, center, center + 1)
    }

    /** SystemView.cpp:1609-1613, the two while loops that fold an index back into range. */
    fun wrap(index: Int, systemCount: Int): Int {
        if (systemCount <= 0) return 0
        var wrapped = index
        while (wrapped < 0) wrapped += systemCount
        while (wrapped >= systemCount) wrapped -= systemCount
        return wrapped
    }

    /**
     * How far system [index]'s element set is pushed from the view's own
     * rest position, as a fraction of the view along the sliding axis:
     * ES-DE's `(i - mCamOffset)` before it is multiplied by `mSize`
     * (SystemView.cpp:1624-1637).
     */
    fun displacement(index: Int, camOffset: Float): Float = index - camOffset

    /**
     * Whether the carousel is mid-move, which is ES-DE's
     * `isAnimationPlaying(0)`: its camera offset is not sitting on a whole
     * entry. The tolerance is there because the offset is a float being
     * animated toward one.
     */
    fun animating(camOffset: Float): Boolean = abs(camOffset - camOffset.roundToInt()) > 0.0005f
}
