package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** [esDeTransitionAnimation]: slide and fade are named; every other value, blank or null is INSTANT. */
class EsDeTransitionAnimationParseTest {
    @Test
    fun `slide and fade parse, ignoring surrounding spaces`() {
        assertEquals(EsDeTransitionAnimation.SLIDE, esDeTransitionAnimation("slide"))
        assertEquals(EsDeTransitionAnimation.FADE, esDeTransitionAnimation("fade"))
        assertEquals(EsDeTransitionAnimation.SLIDE, esDeTransitionAnimation(" slide "))
    }

    @Test
    fun `instant, unknown, blank and null are all instant`() {
        assertEquals(EsDeTransitionAnimation.INSTANT, esDeTransitionAnimation("instant"))
        assertEquals(EsDeTransitionAnimation.INSTANT, esDeTransitionAnimation("Slide"))
        assertEquals(EsDeTransitionAnimation.INSTANT, esDeTransitionAnimation(""))
        assertEquals(EsDeTransitionAnimation.INSTANT, esDeTransitionAnimation(null))
    }
}
