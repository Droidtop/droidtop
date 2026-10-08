package dev.droidtop.shell.gamepad

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The motion rules as numbers (docs/SPEC.md "Gaming motion and focus"):
 * colour is instant, taking focus is quicker than leaving it, a centred
 * scroll is the shortest animation, the focus look runs between rest and
 * focused without overshooting, and turning motion off makes every role a
 * snap.
 */
class MotionTokensTest {
    @After
    fun motionBackOn() = Motion.update(appSwitchOn = true, animatorDurationScale = 1f)

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
    fun motionIsOnOnlyWhenTheSwitchIsOnAndAndroidAnimates() {
        assertTrue(Motion.isOn(appSwitchOn = true, animatorDurationScale = 1f))
        assertTrue(Motion.isOn(appSwitchOn = true, animatorDurationScale = 0.5f))
        assertFalse(Motion.isOn(appSwitchOn = false, animatorDurationScale = 1f))
        assertFalse(Motion.isOn(appSwitchOn = true, animatorDurationScale = 0f))
    }

    @Test
    fun everyRoleSnapsWhenMotionIsOff() {
        Motion.update(appSwitchOn = false, animatorDurationScale = 1f)
        assertTrue(Motion.lift<Float>() is SnapSpec<*>)
        assertTrue(Motion.release<Float>() is SnapSpec<*>)
        assertTrue(Motion.panelIn<Float>() is SnapSpec<*>)
        assertTrue(Motion.scroll(chained = false) is SnapSpec<*>)
        assertTrue(Motion.rise<Float>(3) is SnapSpec<*>)
        assertTrue(Motion.sp<Float>(0.7f, 500f) is SnapSpec<*>)
        assertEquals(0L, Motion.ms(450))
    }

    @Test
    fun rolesAreTimedWhenMotionIsOn() {
        Motion.update(appSwitchOn = true, animatorDurationScale = 1f)
        assertEquals(Motion.LiftMs, (Motion.lift<Float>() as TweenSpec<*>).durationMillis)
        assertEquals(450L, Motion.ms(450))
    }

    @Test
    fun androidsAnimatorScaleStretchesWallClockWaits() {
        Motion.update(appSwitchOn = true, animatorDurationScale = 2f)
        assertEquals(900L, Motion.ms(450))
    }

    @Test
    fun riseStaggersAndCaps() {
        assertEquals(0, Motion.riseDelay(0))
        assertEquals(Motion.RiseStaggerMs, Motion.riseDelay(1))
        assertEquals(Motion.RiseMaxSteps * Motion.RiseStaggerMs, Motion.riseDelay(40))
    }

    @Test
    fun focusLookEndsAreRestAndFocused() {
        assertEquals(1f, FocusLook.scale(0f), 0f)
        assertEquals(FocusLook.LiftScale, FocusLook.scale(1f), 0f)
        assertEquals(FocusLook.LiftScaleWide, FocusLook.scale(1f, wide = true), 0f)
        assertEquals(FocusLook.RestShadowDp, FocusLook.shadowDp(0f), 0f)
        assertEquals(FocusLook.FocusShadowDp, FocusLook.shadowDp(1f), 0f)
        assertEquals(FocusLook.RestDim, FocusLook.dimAlpha(0f), 0f)
        assertEquals(0f, FocusLook.dimAlpha(1f), 0f)
        assertEquals(-FocusLook.RiseDp, FocusLook.riseDp(1f), 0f)
    }

    @Test
    fun aWideCardGrowsLessThanAPortraitOne() {
        assertTrue(FocusLook.LiftScaleWide < FocusLook.LiftScale)
    }

    @Test
    fun focusLookClampsOutOfRangeProgress() {
        assertEquals(FocusLook.LiftScale, FocusLook.scale(1.4f), 0f)
        assertEquals(1f, FocusLook.scale(-0.3f), 0f)
        assertEquals(0f, FocusLook.dimAlpha(2f), 0f)
    }

    @Test
    fun ringLandsFromOutsideAndBreathesToItsFloor() {
        assertEquals(Motion.RingLandFromDp, FocusLook.ringLandOutsetDp(0f), 0.0001f)
        assertEquals(0f, FocusLook.ringLandOutsetDp(1f), 0.0001f)
        assertEquals(FocusLook.RingAlpha, FocusLook.ringAlpha(0f), 0.0001f)
        assertEquals(FocusLook.RingBreathAlpha, FocusLook.ringAlpha(1f), 0.0001f)
    }

    @Test
    fun contentGutterIsAProportionOfTheWindow() {
        assertEquals(21.504f, contentGutterDp(768), 0.001f)
        assertEquals(35.84f, contentGutterDp(1280), 0.001f)
        assertEquals(64f, contentGutterDp(1600), 0.001f)
        assertEquals(16f, contentGutterDp(360), 0f)
        assertEquals(12f + 768 * 0.014f, barGutterDp(768), 0.001f)
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
