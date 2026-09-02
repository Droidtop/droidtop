package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks for the real ES-DE `text` `container*` port. Every expected
 * value below is worked out by hand from the real formulas in
 * `ScrollableContainer.cpp` (the vertical container),
 * `TextComponent.cpp` (the horizontal marquee) and `MathUtil.cpp`
 * (`Utils::Math::loop`), cited per test -- none of them is a recording of
 * this code's own output.
 */
class EsDeTextContainerTest {

    // -----------------------------------------------------------------
    // Which elements are containers at all -- TextComponent.cpp:515-544
    // -----------------------------------------------------------------

    /** THEMES.md:3091 -- `container` defaults to true for a description. */
    @Test
    fun `a description element is a vertical container with no container property at all`() {
        val spec = spec(container = null, metadata = "description")
        assertEquals(EsDeTextContainerType.VERTICAL, spec!!.type)
        // ScrollableContainer.h:14-18 defaults.
        assertEquals(4500f, spec.startDelayMs, 0.001f)
        assertEquals(7000f, spec.resetDelayMs, 0.001f)
        assertEquals(1f, spec.scrollSpeed, 0.001f)
        assertTrue(spec.verticalSnap)
    }

    /** THEMES.md:3091 -- anything else defaults to no container. */
    @Test
    fun `a plain label is not a container`() {
        assertNull(spec(container = null, metadata = "developer"))
    }

    /** TextComponent.cpp:516-521 -- a container needs a horizontal size. */
    @Test
    fun `container is ignored when the element has no width`() {
        assertNull(spec(container = true, metadata = "description", hasWidth = false))
    }

    /** TextComponent.cpp:524-538 -- the horizontal marquee, and its own 1.5 s default delay. */
    @Test
    fun `explicit horizontal container type selects the marquee`() {
        val spec = spec(container = true, metadata = null, containerType = "horizontal")
        assertEquals(EsDeTextContainerType.HORIZONTAL, spec!!.type)
        assertEquals(1500f, spec.startDelayMs, 0.001f)  // TextComponent.cpp:46
        assertEquals(1.5f, spec.scrollGap, 0.001f)      // TextComponent.cpp:47
    }

    /**
     * TextComponent.cpp:522 -- `containerType` is read only from inside
     * the `elem->has("container")` branch, so a description panel that
     * never wrote `container` itself stays vertical even if it wrote a
     * type.
     */
    @Test
    fun `container type is ignored when container itself was not written`() {
        val spec = spec(container = null, metadata = "description", containerType = "horizontal")
        assertEquals(EsDeTextContainerType.VERTICAL, spec!!.type)
    }

    /** TextComponent.cpp:539-543 -- an unknown type is an error, not a new mode. */
    @Test
    fun `an unrecognised container type falls back to vertical`() {
        val spec = spec(container = true, metadata = null, containerType = "diagonal")
        assertEquals(EsDeTextContainerType.VERTICAL, spec!!.type)
    }

    /** ScrollableContainer.cpp:118-129 and TextComponent.cpp:527-534. */
    @Test
    fun `out of range property values are clamped to the documented limits`() {
        val spec = spec(
            container = true, metadata = null, containerType = "horizontal",
            scrollSpeed = 25f, startDelay = 30f, resetDelay = 50f, scrollGap = 9f,
        )!!
        assertEquals(10f, spec.scrollSpeed, 0.001f)
        assertEquals(10_000f, spec.startDelayMs, 0.001f)
        assertEquals(20_000f, spec.resetDelayMs, 0.001f)
        assertEquals(5f, spec.scrollGap, 0.001f)
    }

    // -----------------------------------------------------------------
    // Vertical snap -- ScrollableContainer.cpp:161-172
    // -----------------------------------------------------------------

    /** floor(100/30) = 3 whole rows, ceil(3 * 30) = 90. */
    @Test
    fun `vertical snap trims the container to whole rows`() {
        assertEquals(90f, esDeVerticalContainerHeight(100f, 30f, verticalSnap = true), 0.001f)
    }

    /** floor(100/33.3) = 3, ceil(3 * 33.3) = ceil(99.9) = 100 -- the ceil, not the floor. */
    @Test
    fun `vertical snap rounds the row total up`() {
        assertEquals(100f, esDeVerticalContainerHeight(100f, 33.3f, verticalSnap = true), 0.001f)
    }

    /** ScrollableContainer.cpp:168-170 -- snap off keeps the declared size. */
    @Test
    fun `vertical snap off uses the declared height unchanged`() {
        assertEquals(100f, esDeVerticalContainerHeight(100f, 30f, verticalSnap = false), 0.001f)
    }

    /** ScrollableContainer.cpp:164-165 -- never fewer than one row, even if it overflows. */
    @Test
    fun `a box shorter than one row still keeps one row`() {
        assertEquals(150f, esDeVerticalContainerHeight(100f, 150f, verticalSnap = true), 0.001f)
    }

    /**
     * ScrollableContainer.cpp:154-158. Line spacing 1.5 over a combined
     * height of 30 means a glyph height of 20, so the inset is
     * (20*1.5 - 20*1.2) / 2 = (30 - 24) / 2 = 3.
     */
    @Test
    fun `generous line spacing trims half the extra leading off the top`() {
        assertEquals(3f, esDeVerticalContainerClipInset(30f, 1.5f), 0.001f)
    }

    /** ScrollableContainer.cpp:154 -- at or below 1.2 there is nothing to trim. */
    @Test
    fun `tight line spacing gets no clip inset`() {
        assertEquals(0f, esDeVerticalContainerClipInset(30f, 1.2f), 0.001f)
    }

    // -----------------------------------------------------------------
    // Vertical speed -- ScrollableContainer.cpp:183-198
    // -----------------------------------------------------------------

    /**
     * width = 600 / (20 * 1.3) = 23.0769, inside the 10..40 clamp.
     * 23.0769 * (4.0 / 1.0) = 92.3077, / 1.0 resolution = 92 after the
     * int cast. adjustedHeight 300 / combined 30 = 10 rows, which is at
     * or above 8, so the row modifier is 1: 92 ms per pixel.
     */
    @Test
    fun `vertical interval is derived from row width, speed and resolution`() {
        assertEquals(92, interval())
    }

    /**
     * ScrollableContainer.cpp:191 -- the resolution modifier is
     * min(screenWidth, screenHeight) / 1080 (Renderer.cpp:188-191,
     * :307-310). At twice 1080p the same theme must cover twice as many
     * pixels in the same wall-clock time, so the interval halves:
     * 92.3077 / 2 = 46.15 -> 46.
     */
    @Test
    fun `a higher resolution halves the per-pixel interval`() {
        assertEquals(46, interval(resolutionModifier = 2f))
    }

    /**
     * ScrollableContainer.cpp:117-118 -- `containerScrollSpeed` divides
     * into the 4.0 constant, so 2 is twice as fast:
     * 23.0769 * (4.0 / 2.0) = 46.15 -> 46.
     */
    @Test
    fun `containerScrollSpeed of two halves the interval`() {
        assertEquals(46, interval(scrollSpeed = 2f))
    }

    /**
     * ScrollableContainer.cpp:195-198 -- a 120 px box of 30 px rows is
     * 4 rows, under 8, so the interval is scaled by 4/8: 92 * 0.5 = 46.
     */
    @Test
    fun `a container under eight rows tall scrolls proportionally faster`() {
        assertEquals(46, interval(adjustedHeightPx = 120f))
    }

    /**
     * ScrollableContainer.cpp:186-188 -- the row width is clamped to
     * 10..40 so extreme columns stay sane. 100 / 26 = 3.85 clamps up to
     * 10, giving 10 * 4 = 40; 2000 / 26 = 76.9 clamps down to 40, giving
     * 40 * 4 = 160.
     */
    @Test
    fun `row width is clamped at both ends`() {
        assertEquals(40, interval(contentWidthPx = 100f))
        assertEquals(160, interval(contentWidthPx = 2000f))
    }

    // -----------------------------------------------------------------
    // Vertical travel and cycle -- ScrollableContainer.cpp:200-248
    // -----------------------------------------------------------------

    /**
     * ScrollableContainer.cpp:225-228 -- the stop position is the first
     * whole pixel where scrollPos + adjustedHeight EXCEEDS the content,
     * i.e. one past the exact fit: floor(500 - 300) + 1 = 201.
     */
    @Test
    fun `maximum scroll is one pixel past the exact fit`() {
        assertEquals(201, esDeVerticalMaxScrollPx(500f, 300f))
        assertEquals(1, esDeVerticalMaxScrollPx(300.5f, 300f))
    }

    /** ScrollableContainer.cpp:207 -- text that fits must not move at all. */
    @Test
    fun `text that fits the container never scrolls`() {
        assertEquals(0, esDeVerticalMaxScrollPx(300f, 300f))
        assertEquals(0, esDeVerticalMaxScrollPx(200f, 300f))
        val state = esDeVerticalScrollState(60_000f, 4500f, 7000f, 92, maxScrollPx = 0)
        assertEquals(0f, state.scrollPx, 0.001f)
        assertEquals(1f, state.opacity, 0.001f)
    }

    /**
     * The whole cycle for the worked example above: start delay 4500,
     * 201 pixels at 92 ms each = 18492 ms of travel, reset delay 7000,
     * then the 300 ms fade (ScrollableContainer.cpp:246), so the period
     * is 4500 + 18492 + 7000 + 300 = 30292 ms.
     */
    @Test
    fun `the vertical cycle waits, scrolls, holds, then fades in at the top`() {
        // Waiting: ScrollableContainer.cpp:67 seeds the accumulator to -delay.
        assertEquals(0f, verticalAt(0f).scrollPx, 0.001f)
        assertEquals(0f, verticalAt(4499f).scrollPx, 0.001f)
        // First pixel one interval after the delay expires.
        assertEquals(0f, verticalAt(4500f).scrollPx, 0.001f)
        assertEquals(1f, verticalAt(4592f).scrollPx, 0.001f)
        // 100 intervals in: 4500 + 92 * 100 = 13700.
        assertEquals(100f, verticalAt(13_700f).scrollPx, 0.001f)
        // End of travel at 4500 + 18492 = 22992, and it holds there.
        assertEquals(201f, verticalAt(22_992f).scrollPx, 0.001f)
        assertEquals(201f, verticalAt(25_000f).scrollPx, 0.001f)
        assertEquals(1f, verticalAt(25_000f).opacity, 0.001f)
        // Reset at 22992 + 7000 = 29992: back to the top, fully transparent.
        assertEquals(0f, verticalAt(29_992f).scrollPx, 0.001f)
        assertEquals(0f, verticalAt(29_992f).opacity, 0.001f)
        // Halfway through the 300 ms fade.
        assertEquals(0.5f, verticalAt(30_142f).opacity, 0.001f)
        // And the next cycle starts clean at 30292, delay included.
        assertEquals(0f, verticalAt(30_292f).scrollPx, 0.001f)
        assertEquals(1f, verticalAt(30_292f).opacity, 0.001f)
        assertEquals(1f, verticalAt(30_292f + 4592f).scrollPx, 0.001f)
    }

    // -----------------------------------------------------------------
    // Horizontal marquee -- TextComponent.cpp:695-745
    // -----------------------------------------------------------------

    /** TextComponent.cpp:700 -- 520 * 0.247 = 128.44 px/s at speed 1, doubled at 2. */
    @Test
    fun `horizontal speed is the size reference times the ES-DE constant`() {
        assertEquals(128.44f, esDeHorizontalScrollSpeedPxPerSec(520f, 1f), 0.001f)
        assertEquals(256.88f, esDeHorizontalScrollSpeedPxPerSec(520f, 2f), 0.001f)
    }

    /**
     * TextComponent.cpp:727 -- the multiplier cancels out of
     * `mScrollSpeed * mScrollGap / mScrollSpeedMultiplier`, so the gap is
     * a fixed distance: 520 * 0.247 * 1.5 = 192.66, and a gap of 9 is
     * clamped to 5 giving 642.2.
     */
    @Test
    fun `the loop gap is a fixed distance independent of speed`() {
        assertEquals(192.66f, esDeHorizontalReturnLengthPx(520f, 1.5f), 0.001f)
        assertEquals(642.2f, esDeHorizontalReturnLengthPx(520f, 9f), 0.001f)
    }

    /** TextComponent.cpp:724 -- text that fits is aligned, not scrolled. */
    @Test
    fun `horizontal text that fits the element never scrolls`() {
        val state = esDeHorizontalScrollState(9999f, 300f, 400f, 100f, 150f, 1500f)
        assertEquals(0f, state.firstOffsetPx, 0.001f)
        assertEquals(0f, state.secondOffsetPx, 0.001f)
    }

    /**
     * A 1000 px text in a 400 px box at 100 px/s with a 150 px gap and
     * the default 1.5 s delay: scrollTime = 1000 * 1000 / 100 = 10000 ms,
     * returnTime = 150 * 1000 / 100 = 1500 ms, so the period is
     * 1500 + 10000 + 1500 = 13000 ms (TextComponent.cpp:726-730).
     */
    @Test
    fun `the horizontal marquee waits then runs continuously`() {
        // Utils::Math::loop, MathUtil.cpp:47-50 -- flat zero during the delay.
        assertEquals(0f, horizontalAt(0f).firstOffsetPx, 0.001f)
        assertEquals(0f, horizontalAt(1499f).firstOffsetPx, 0.001f)
        assertEquals(0f, horizontalAt(1500f).firstOffsetPx, 0.001f)
        // MathUtil.cpp:51-55 -- linear over scrollTime + returnTime = 11500 ms
        // across scrollLength + returnLength = 1150 px. A tenth of the way
        // (t = 1500 + 1150) is 115 px.
        assertEquals(115f, horizontalAt(2650f).firstOffsetPx, 0.001f)
        // And it wraps with no pause at the end (TextComponent.cpp:734-735).
        assertEquals(115f, horizontalAt(15_650f).firstOffsetPx, 0.001f)
    }

    /**
     * TextComponent.cpp:740-741 -- the second copy is drawn once the
     * first has passed 1000 - (400 - 150) = 750 px, and it sits at
     * offset - (1000 + 150). At 800 px that is 800 - 1150 = -350, which
     * leaves exactly the 150 px gap between the two copies: the first
     * copy's tail lands at 1000 - 800 = 200 in the box, the second copy's
     * head at 350.
     */
    @Test
    fun `the second copy comes in behind the first once the gap opens`() {
        // 115 px in, nothing behind it yet.
        assertEquals(0f, horizontalAt(2650f).secondOffsetPx, 0.001f)
        // 800 px in: fraction 800/1150, so t = 1500 + 8000 = 9500.
        val state = horizontalAt(9500f)
        assertEquals(800f, state.firstOffsetPx, 0.001f)
        assertEquals(-350f, state.secondOffsetPx, 0.001f)
    }

    // -----------------------------------------------------------------

    private fun spec(
        container: Boolean?,
        metadata: String?,
        hasWidth: Boolean = true,
        containerType: String? = null,
        scrollSpeed: Float? = null,
        startDelay: Float? = null,
        resetDelay: Float? = null,
        scrollGap: Float? = null,
    ) = esDeTextContainerSpec(
        container = container,
        metadata = metadata,
        hasWidth = hasWidth,
        containerType = containerType,
        verticalSnap = null,
        scrollSpeed = scrollSpeed,
        startDelay = startDelay,
        resetDelay = resetDelay,
        scrollGap = scrollGap,
    )

    private fun interval(
        contentWidthPx: Float = 600f,
        fontSizePx: Float = 20f,
        scrollSpeed: Float = 1f,
        resolutionModifier: Float = 1f,
        adjustedHeightPx: Float = 300f,
        combinedHeightPx: Float = 30f,
    ) = esDeVerticalScrollIntervalMs(
        contentWidthPx, fontSizePx, scrollSpeed, resolutionModifier, adjustedHeightPx, combinedHeightPx,
    )

    private fun verticalAt(elapsedMs: Float) =
        esDeVerticalScrollState(elapsedMs, startDelayMs = 4500f, resetDelayMs = 7000f, intervalMs = 92, maxScrollPx = 201)

    private fun horizontalAt(elapsedMs: Float) = esDeHorizontalScrollState(
        elapsedMs = elapsedMs, textWidthPx = 1000f, boxWidthPx = 400f,
        speedPxPerSec = 100f, returnLengthPx = 150f, startDelayMs = 1500f,
    )
}
