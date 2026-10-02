package dev.droidtop.shell.gamepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion rules as numbers (docs/SPEC.md "Gaming motion and focus"):
 * colour is instant, taking focus is quicker than leaving it, a centred
 * scroll is the shortest animation, and the focus look runs between rest
 * and focused without overshooting.
 */
class MotionTokensTest {
    @Test
    fun colourIsInstantAndShapeGlides() {
        assertEquals(0, Motion.ColourMs)
        assertTrue(Motion.LiftMs > 0)
    }

    @Test
    fun releaseIsSlowerThanLift() {
        assertTrue(Motion.ReleaseMs > Motion.LiftMs)
        assertTrue(Motion.PanelOutMs < Motion.PanelInMs)
    }

    @Test
    fun centringScrollIsTheQuickest() {
        assertTrue(Motion.CarouselCentreMs < Motion.LiftMs)
        assertTrue(Motion.CarouselCentreMs < Motion.RingLandMs)
    }

    @Test
    fun focusLookEndsAreRestAndFocused() {
        assertEquals(1f, FocusLook.scale(0f), 0f)
        assertEquals(FocusLook.LiftScale, FocusLook.scale(1f), 0f)
        assertEquals(0f, FocusLook.shadowDp(0f), 0f)
        assertEquals(FocusLook.FocusShadowDp, FocusLook.shadowDp(1f), 0f)
        assertEquals(FocusLook.RestDim, FocusLook.dimAlpha(0f), 0f)
        assertEquals(0f, FocusLook.dimAlpha(1f), 0f)
    }

    @Test
    fun focusLookClampsOutOfRangeProgress() {
        assertEquals(FocusLook.LiftScale, FocusLook.scale(1.4f), 0f)
        assertEquals(1f, FocusLook.scale(-0.3f), 0f)
        assertEquals(0f, FocusLook.dimAlpha(2f), 0f)
    }

    @Test
    fun ringLandsThickToThinAndTransparentToOpaque() {
        assertEquals(1f + FocusLook.RingLandExtra, FocusLook.ringWidthFactor(0f), 0f)
        assertEquals(1f, FocusLook.ringWidthFactor(1f), 0f)
        assertEquals(0f, FocusLook.ringAlpha(0f), 0f)
        assertEquals(1f, FocusLook.ringAlpha(1f), 0f)
    }

    @Test
    fun centreDeltaIsZeroWhenAlreadyCentred() {
        assertEquals(0f, centreDelta(itemStart = 450f, itemSize = 100f, spanStart = 0f, spanEnd = 1000f), 0f)
    }

    @Test
    fun centreDeltaScrollsForwardForItemsAfterCentre() {
        assertEquals(300f, centreDelta(itemStart = 750f, itemSize = 100f, spanStart = 0f, spanEnd = 1000f), 0f)
    }

    @Test
    fun centreDeltaScrollsBackwardForItemsBeforeCentre() {
        assertEquals(-450f, centreDelta(itemStart = 0f, itemSize = 100f, spanStart = 0f, spanEnd = 1000f), 0f)
    }

    @Test
    fun centreDeltaUsesTheVisibleSpanNotTheViewport() {
        // 100 of content padding at each end: the span is 100..900, centre 500.
        assertEquals(0f, centreDelta(itemStart = 450f, itemSize = 100f, spanStart = 100f, spanEnd = 900f), 0f)
    }
}
