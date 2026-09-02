package dev.droidtop.library.theme

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Real ES-DE's `text` scrolling CONTAINER -- the `container*` property
 * family -- as pure timing/geometry maths, with no Compose or Android
 * dependency so it is unit-testable without a screen. Same split, for the
 * same reason, as `EsDeCarouselLayout.kt`, `EsDeTextListLayout.kt`,
 * `EsDeGridLayout.kt` and `EsDeVideoLayout.kt`.
 *
 * Real ES-DE has TWO different implementations behind this one property
 * family and they share almost nothing:
 *
 *  - `containerType="vertical"` (the default) wraps the text element in a
 *    separate `ScrollableContainer` component, which scrolls the text
 *    upward ONE WHOLE PIXEL at a time on an accumulator, stops at the
 *    bottom, waits, then fades the text back in at the top
 *    (`ScrollableContainer.cpp`).
 *  - `containerType="horizontal"` is not a container at all: the
 *    `TextComponent` itself becomes a marquee, laying the text out on a
 *    single line and looping a second copy in behind the first
 *    (`TextComponent::update`, TextComponent.cpp:708-747).
 *
 * Both are ported here. Everything is in PIXELS, deliberately: real
 * ES-DE's vertical speed is resolution-dependent by design (see
 * [esDeVerticalScrollIntervalMs]) and the horizontal one steps in
 * whole pixels, so doing this in density-independent units would quietly
 * change the behaviour.
 */

enum class EsDeTextContainerType { VERTICAL, HORIZONTAL }

/** Real ES-DE defaults, ScrollableContainer.h:14-18 and TextComponent.cpp:44-50. */
private const val AUTO_SCROLL_DELAY_MS = 4500f
private const val AUTO_SCROLL_RESET_DELAY_MS = 7000f
private const val AUTO_SCROLL_SPEED = 4.0f
private const val HORIZONTAL_SCROLL_DELAY_MS = 1500f
private const val HORIZONTAL_SCROLL_GAP = 1.5f

/**
 * The 300 ms fade-in that plays as a vertical container resets to the
 * top, ScrollableContainer.cpp:246 (`new LambdaAnimation(func, 300)`).
 * It matters for the cycle length as well as for the look: the lambda
 * re-arms the start delay on every frame it runs, so the start delay only
 * begins counting once the fade has finished
 * (ScrollableContainer.cpp:236-245).
 */
const val ES_DE_CONTAINER_RESET_FADE_MS = 300f

/** The resolved, clamped `container*` configuration for one `text` element. */
data class EsDeTextContainerSpec(
    val type: EsDeTextContainerType,
    /** `containerVerticalSnap`, vertical only. */
    val verticalSnap: Boolean,
    /** `containerScrollSpeed`, clamped to 0.1..10 (ScrollableContainer.cpp:118, TextComponent.cpp:527). */
    val scrollSpeed: Float,
    /** `containerStartDelay` in ms, clamped to 0..10000 (ScrollableContainer.cpp:123, TextComponent.cpp:531). */
    val startDelayMs: Float,
    /** `containerResetDelay` in ms, clamped to 0..20000, vertical only (ScrollableContainer.cpp:128). */
    val resetDelayMs: Float,
    /** `containerScrollGap`, clamped to 0.1..5, horizontal only (TextComponent.cpp:534). */
    val scrollGap: Float,
)

/**
 * Resolves the `container*` family for a `text` element, returning null
 * when the element is not a scrolling container at all.
 *
 * The enabling rules are TextComponent.cpp:515-544 plus THEMES.md's own
 * statement of the defaults (THEMES.md:3089-3119):
 *
 *  - `container` defaults to TRUE when `metadata` is `description`, and
 *    false otherwise. This is why eight of the ten measured themes get a
 *    scrolling description panel while only some of them say `container`
 *    out loud.
 *  - A container needs a horizontal `size`; real ES-DE logs an error and
 *    ignores the property otherwise (TextComponent.cpp:516-521).
 *  - `containerType` is only honoured when `container` was set
 *    EXPLICITLY (TextComponent.cpp:522), and an unrecognised value is an
 *    error that leaves the vertical default in place
 *    (TextComponent.cpp:539-543).
 */
fun esDeTextContainerSpec(
    container: Boolean?,
    metadata: String?,
    hasWidth: Boolean,
    containerType: String?,
    verticalSnap: Boolean?,
    scrollSpeed: Float?,
    startDelay: Float?,
    resetDelay: Float?,
    scrollGap: Float?,
): EsDeTextContainerSpec? {
    val enabled = container ?: (metadata == "description")
    if (!enabled || !hasWidth) return null
    // TextComponent.cpp:522 -- `containerType` is read from inside the
    // `elem->has("container")` branch, so it only applies when the theme
    // wrote `container` itself.
    val type = if (container == true && containerType == "horizontal") {
        EsDeTextContainerType.HORIZONTAL
    } else {
        EsDeTextContainerType.VERTICAL
    }
    val horizontal = type == EsDeTextContainerType.HORIZONTAL
    return EsDeTextContainerSpec(
        type = type,
        verticalSnap = verticalSnap ?: true,
        scrollSpeed = (scrollSpeed ?: 1f).coerceIn(0.1f, 10f),
        startDelayMs = startDelay?.coerceIn(0f, 10f)?.times(1000f)
            ?: if (horizontal) HORIZONTAL_SCROLL_DELAY_MS else AUTO_SCROLL_DELAY_MS,
        resetDelayMs = resetDelay?.coerceIn(0f, 20f)?.times(1000f) ?: AUTO_SCROLL_RESET_DELAY_MS,
        scrollGap = (scrollGap ?: HORIZONTAL_SCROLL_GAP).coerceIn(0.1f, 5f),
    )
}

// ---------------------------------------------------------------------
// Vertical container -- ScrollableContainer.cpp
// ---------------------------------------------------------------------

/**
 * The height the container actually clips to.
 * ScrollableContainer.cpp:161-172: with `containerVerticalSnap` on (the
 * default) the clipped height is rounded DOWN to a whole number of text
 * rows, so a half row is never left showing at the top or bottom edge;
 * with it off the declared size is used as-is. The element's declared
 * size is deliberately not changed either way (THEMES.md:3099).
 *
 * [combinedHeightPx] is ES-DE's `maxGlyphHeight * lineSpacing`
 * (ScrollableContainer.cpp:148-149), i.e. the pitch from one baseline to
 * the next.
 */
fun esDeVerticalContainerHeight(
    sizeHeightPx: Float,
    combinedHeightPx: Float,
    verticalSnap: Boolean,
): Float {
    if (!verticalSnap) return sizeHeightPx
    if (combinedHeightPx <= 0f) return sizeHeightPx
    // ScrollableContainer.cpp:163-167 -- floor(), with a floor of one row.
    val numLines = maxOf(1f, floor(sizeHeightPx / combinedHeightPx))
    return ceil(numLines * combinedHeightPx)
}

/**
 * The top inset real ES-DE applies when clipping a generously spaced
 * container, ScrollableContainer.cpp:154-158 and :265-266. Above a line
 * spacing of 1.2 it discards half of the extra leading so the first row
 * is not preceded by a visible band of empty space. Note that ES-DE adds
 * this to the clip ORIGIN and subtracts it from the clip HEIGHT, so the
 * bottom edge does not move -- only the top is trimmed.
 */
fun esDeVerticalContainerClipInset(combinedHeightPx: Float, lineSpacing: Float): Float {
    if (lineSpacing <= 1.2f || combinedHeightPx <= 0f) return 0f
    val maxGlyphHeight = combinedHeightPx / lineSpacing
    return kotlin.math.round((maxGlyphHeight * lineSpacing - maxGlyphHeight * 1.2f) / 2f)
}

/**
 * Milliseconds per ONE PIXEL of vertical scroll -- real ES-DE moves the
 * container by exactly one pixel per elapsed interval
 * (ScrollableContainer.cpp:203-212, `mScrollPos += mScrollDir` with
 * `mScrollDir = {0, 1}`), so the whole speed system is expressed as this
 * interval and NOT as a velocity.
 *
 * ScrollableContainer.cpp:183-198, verbatim:
 *  - a base modifier of `contentWidth / (fontSize * 1.3)`, i.e. roughly
 *    how many characters wide a row is, clamped to 10..40, so wide
 *    columns (which hold more words per row) scroll more slowly;
 *  - multiplied by `4.0 / containerScrollSpeed`, which is why the theme
 *    property is a MULTIPLIER on the auto-calculated base and why a
 *    larger value is faster (THEMES.md:3102-3105);
 *  - divided by the resolution modifier, which is
 *    `min(screenWidth, screenHeight) / 1080` (Renderer.cpp:188-191,
 *    :307-310). This is the resolution dependence to be careful about:
 *    the interval is ms per PIXEL, so without this division a 1080p-tall
 *    handheld would scroll at half the apparent speed of a 2160p one.
 *    Passing droidtop's real viewport pixels in keeps a given theme
 *    scrolling at the same fraction of the screen per second as it does
 *    on a desktop;
 *  - and finally, ScrollableContainer.cpp:195-198, containers under
 *    eight rows tall are sped up proportionally, on the reasoning that a
 *    short box has less to read per pixel travelled.
 *
 * The truncation to int is real ES-DE's (`static_cast<int>`), and a
 * result of zero really does disable scrolling there
 * (ScrollableContainer.cpp:203 tests `!= 0`), so that is preserved. The
 * ONE deviation is that an interval of zero from the row modifier is
 * raised to 1: real ES-DE's `while (accumulator >= 0)` loop at :205 would
 * spin forever on that value, which is a bug and not behaviour to port.
 */
fun esDeVerticalScrollIntervalMs(
    contentWidthPx: Float,
    fontSizePx: Float,
    scrollSpeed: Float,
    resolutionModifier: Float,
    adjustedHeightPx: Float,
    combinedHeightPx: Float,
): Int {
    if (fontSizePx <= 0f || resolutionModifier <= 0f || combinedHeightPx <= 0f) return 0
    val width = contentWidthPx / (fontSizePx * 1.3f)
    var speedModifier = width.coerceIn(10f, 40f)
    speedModifier *= AUTO_SCROLL_SPEED / scrollSpeed.coerceIn(0.1f, 10f)
    speedModifier /= resolutionModifier
    val adjustedSpeed = speedModifier.toInt()
    if (adjustedSpeed == 0) return 0

    // ScrollableContainer.cpp:195-198.
    val lines = adjustedHeightPx / combinedHeightPx
    val rowModifier = if (lines < 8f) lines / 8f else 1f
    return maxOf(1, (rowModifier * adjustedSpeed).toInt())
}

/**
 * The furthest the container ever scrolls, in whole pixels.
 * ScrollableContainer.cpp:225-228: the position keeps advancing while
 * `scrollPos + adjustedHeight <= contentHeight`, and the frame that first
 * breaks that is where it stops, so the last position is one pixel past
 * the exact fit. Content that is no taller than the clipped container
 * never advances at all (the `contentSize.y > mAdjustedHeight` guard at
 * :207), which is the "short text must not scroll" case.
 */
fun esDeVerticalMaxScrollPx(contentHeightPx: Float, adjustedHeightPx: Float): Int {
    if (contentHeightPx <= adjustedHeightPx) return 0
    return floor(contentHeightPx - adjustedHeightPx).toInt() + 1
}

/** Where a vertical container is at [elapsedMs], and how opaque its text is. */
data class EsDeVerticalScrollState(
    /** Pixels the text is shifted UP by. Always a whole number, as in real ES-DE. */
    val scrollPx: Float,
    /** 0..1, only below 1 during the reset fade-in. Multiply the element's own opacity by this. */
    val opacity: Float,
)

/**
 * The full vertical cycle, as a function of time rather than an
 * accumulator. Real ES-DE advances an accumulator by each frame's delta
 * (ScrollableContainer.cpp:204-211); reading it as a closed form of
 * elapsed time gives the identical result for any frame pacing and is
 * testable without a clock.
 *
 * The cycle, from ScrollableContainer.cpp:63-67 (`resetComponent`),
 * :200-212 (the step), :225-248 (the end and the reset):
 *
 *   1. wait `startDelayMs`. The accumulator is seeded to `-mAutoScrollDelay`
 *      at :67, so the delay is measured from the moment the container was
 *      reset -- which is when the selected game changed
 *      (GamelistView.cpp:914-915 resets every container on cursor change),
 *      not from when the view appeared;
 *   2. move up one pixel every interval until the bottom is reached;
 *   3. hold at the bottom for `resetDelayMs`;
 *   4. jump to the top and fade the text in over 300 ms, during which the
 *      start delay is re-armed each frame (:243) so step 1 effectively
 *      begins again only once the fade completes;
 *   5. repeat.
 */
fun esDeVerticalScrollState(
    elapsedMs: Float,
    startDelayMs: Float,
    resetDelayMs: Float,
    intervalMs: Int,
    maxScrollPx: Int,
): EsDeVerticalScrollState {
    if (maxScrollPx <= 0 || intervalMs <= 0) return EsDeVerticalScrollState(0f, 1f)

    val scrollDurationMs = maxScrollPx.toFloat() * intervalMs
    val cycleMs = startDelayMs + scrollDurationMs + resetDelayMs + ES_DE_CONTAINER_RESET_FADE_MS
    val t = if (elapsedMs <= 0f) 0f else elapsedMs % cycleMs

    val fadeStart = startDelayMs + scrollDurationMs + resetDelayMs
    if (t >= fadeStart) {
        // Step 4: back at the top, fading in (ScrollableContainer.cpp:236-241).
        return EsDeVerticalScrollState(0f, ((t - fadeStart) / ES_DE_CONTAINER_RESET_FADE_MS).coerceIn(0f, 1f))
    }
    if (t < startDelayMs) return EsDeVerticalScrollState(0f, 1f)
    val steps = floor((t - startDelayMs) / intervalMs).toInt()
    return EsDeVerticalScrollState(steps.coerceAtMost(maxScrollPx).toFloat(), 1f)
}

// ---------------------------------------------------------------------
// Horizontal container -- TextComponent.cpp
// ---------------------------------------------------------------------

/**
 * The horizontal marquee's speed in PIXELS PER SECOND,
 * TextComponent.cpp:700: `mFont->getSizeReference() * 0.247f *
 * mScrollSpeedMultiplier`.
 *
 * [sizeReferencePx] is real ES-DE's `Font::getSizeReference()`
 * (Font.cpp:517-547): the summed horizontal advance of the 26 Latin
 * capitals at the element's own font size. It is therefore
 * resolution-dependent through the font size alone -- unlike the vertical
 * container, no explicit resolution modifier is involved, so a theme's
 * horizontal marquee is already resolution-relative and needs no
 * adjustment for a handheld beyond measuring the real font.
 */
fun esDeHorizontalScrollSpeedPxPerSec(sizeReferencePx: Float, scrollSpeed: Float): Float =
    sizeReferencePx * 0.247f * scrollSpeed.coerceIn(0.1f, 10f)

/**
 * The gap between the tail of the first copy of the text and the head of
 * the looped second copy, TextComponent.cpp:727: `mScrollSpeed *
 * mScrollGap / mScrollSpeedMultiplier`. The multiplier cancels out of
 * `mScrollSpeed`, so the gap is a fixed DISTANCE that `containerScrollSpeed`
 * does not change -- only the time taken to cross it changes.
 */
fun esDeHorizontalReturnLengthPx(sizeReferencePx: Float, scrollGap: Float): Float =
    sizeReferencePx * 0.247f * scrollGap.coerceIn(0.1f, 5f)

/**
 * The two draw offsets for a horizontal marquee. Both are distances the
 * text is shifted LEFT by, so a negative [secondOffsetPx] means the
 * second copy is drawn to the RIGHT of the element's left edge, coming in
 * behind the first (TextComponent.cpp:376-384).
 */
data class EsDeHorizontalScrollState(
    val firstOffsetPx: Float,
    /** <= 0. Zero means the second copy is not on screen and must not be drawn. */
    val secondOffsetPx: Float,
)

/**
 * TextComponent.cpp:720-744 plus `Utils::Math::loop` (MathUtil.cpp:42-59).
 *
 * Text that fits inside the element does not scroll at all -- real ES-DE
 * guards the whole block with `mTextCache->metrics.size.x > mSize.x`
 * (TextComponent.cpp:724) and leaves both offsets at zero, which is also
 * what makes the ordinary `horizontalAlignment` apply to short text
 * (TextComponent.cpp:241-247).
 *
 * The cycle is `startDelay + scrollTime + returnTime`, where the delay is
 * measured from the last reset -- and `TextComponent::resetComponent`
 * (TextComponent.h:124-129) zeroes `mScrollTime`, called on every cursor
 * change (GamelistView.cpp:918-919) and on focus loss (TextComponent.h:77).
 * Unlike the vertical container this scrolls CONTINUOUSLY: there is no
 * pause at the end, the text simply runs off the left edge while its own
 * second copy arrives from the right.
 */
fun esDeHorizontalScrollState(
    elapsedMs: Float,
    textWidthPx: Float,
    boxWidthPx: Float,
    speedPxPerSec: Float,
    returnLengthPx: Float,
    startDelayMs: Float,
): EsDeHorizontalScrollState {
    if (textWidthPx <= boxWidthPx || speedPxPerSec <= 0f) return EsDeHorizontalScrollState(0f, 0f)

    val scrollLength = textWidthPx
    val scrollTimeMs = scrollLength * 1000f / speedPxPerSec
    val returnTimeMs = returnLengthPx * 1000f / speedPxPerSec
    val maxTimeMs = startDelayMs + scrollTimeMs + returnTimeMs
    if (maxTimeMs <= 0f) return EsDeHorizontalScrollState(0f, 0f)

    val t = if (elapsedMs <= 0f) 0f else elapsedMs % maxTimeMs

    // Utils::Math::loop, MathUtil.cpp:42-59.
    val first = if (t < startDelayMs) {
        0f
    } else {
        val fraction = (t - startDelayMs) / (scrollTimeMs + returnTimeMs)
        fraction * (scrollLength + returnLengthPx)
    }

    // TextComponent.cpp:740-743 -- the second copy appears once the tail
    // of the first is close enough to the right edge to leave a hole.
    val second = if (first > scrollLength - (boxWidthPx - returnLengthPx)) {
        first - (scrollLength + returnLengthPx)
    } else {
        0f
    }
    return EsDeHorizontalScrollState(first, second)
}
