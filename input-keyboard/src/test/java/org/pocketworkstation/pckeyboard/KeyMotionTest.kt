package org.pocketworkstation.pckeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hacker Keyboard Reborn's motion rules (Droidtop/tracker#903 H12a): reduce motion, the pressed key, new suggestions. */
class KeyMotionTest {
    @Test fun anAnimatorScaleOfZeroOrAnimatorsSwitchedOffMeansNoMotion() {
        assertEquals(0f, KeyMotion.durationScale(0f, animatorsEnabled = true), 0f)
        assertEquals(0f, KeyMotion.durationScale(1f, animatorsEnabled = false), 0f)
        assertEquals(0f, KeyMotion.durationScale(-1f, animatorsEnabled = true), 0f)
    }

    @Test fun otherScalesStretchEveryMovementAndAnUnreadableOneCountsAsOne() {
        assertEquals(1f, KeyMotion.durationScale(1f, true), 0f)
        assertEquals(0.5f, KeyMotion.durationScale(0.5f, true), 0f)
        assertEquals(1f, KeyMotion.durationScale(Float.NaN, true), 0f)
        assertEquals(10f, KeyMotion.durationScale(20f, true), 0f)
        assertEquals(60L, KeyMotion.scaled(120L, 0.5f))
        assertEquals(0L, KeyMotion.scaled(120L, 0f))
        // Never shorter than a millisecond while there is motion.
        assertEquals(1L, KeyMotion.scaled(1L, 0.1f))
    }

    @Test fun aPressedKeyIsDrawnPressedAtOnceAndShrinksAfter() {
        // The first frame after touch-down: the full pressed colour, at full size.
        assertEquals(1f, KeyMotion.keyTint(pressed = true, from = 0f, elapsedMs = 0L, motion = 1f), 0f)
        assertEquals(1f, KeyMotion.keyScale(pressed = true, from = 1f, elapsedMs = 0L, motion = 1f), 0f)
        val mid = KeyMotion.keyScale(true, 1f, KeyMotion.PRESS_MS / 2, 1f)
        assertTrue(mid < 1f && mid > KeyMotion.PRESSED_SCALE)
        assertEquals(KeyMotion.PRESSED_SCALE, KeyMotion.keyScale(true, 1f, KeyMotion.PRESS_MS, 1f), 0f)
        assertFalse(KeyMotion.keySettled(true, KeyMotion.PRESS_MS - 1, 1f))
        assertTrue(KeyMotion.keySettled(true, KeyMotion.PRESS_MS, 1f))
    }

    @Test fun aReleasedKeyEasesBackFromWhereItWas() {
        val from = KeyMotion.PRESSED_SCALE
        assertEquals(from, KeyMotion.keyScale(false, from, 0L, 1f), 0f)
        assertEquals(1f, KeyMotion.keyScale(false, from, KeyMotion.RELEASE_MS, 1f), 0f)
        assertEquals(1f, KeyMotion.keyTint(false, 1f, 0L, 1f), 0f)
        val fading = KeyMotion.keyTint(false, 1f, KeyMotion.RELEASE_MS / 2, 1f)
        assertTrue(fading > 0f && fading < 1f)
        assertEquals(0f, KeyMotion.keyTint(false, 1f, KeyMotion.RELEASE_MS, 1f), 0f)
        // A slower animator scale stretches it.
        assertFalse(KeyMotion.keySettled(false, KeyMotion.RELEASE_MS, 2f))
        assertTrue(KeyMotion.keySettled(false, KeyMotion.RELEASE_MS * 2, 2f))
    }

    @Test fun withNoMotionAKeyNeverShrinksAndItsColourFollowsThePressExactly() {
        assertEquals(1f, KeyMotion.keyScale(true, 1f, 0L, 0f), 0f)
        assertEquals(1f, KeyMotion.keyScale(false, KeyMotion.PRESSED_SCALE, 0L, 0f), 0f)
        assertEquals(1f, KeyMotion.keyTint(true, 0f, 0L, 0f), 0f)
        assertEquals(0f, KeyMotion.keyTint(false, 1f, 0L, 0f), 0f)
        assertTrue(KeyMotion.keySettled(false, 0L, 0f))
    }

    @Test fun newSuggestionsFadeAndRiseIn() {
        assertEquals(KeyMotion.CANDIDATE_FROM_ALPHA, KeyMotion.candidateAlpha(0L, 1f), 0.0001f)
        assertEquals(KeyMotion.CANDIDATE_RISE_DP, KeyMotion.candidateRiseDp(0L, 1f), 0.0001f)
        assertEquals(1f, KeyMotion.candidateAlpha(KeyMotion.CANDIDATE_MS, 1f), 0f)
        assertEquals(0f, KeyMotion.candidateRiseDp(KeyMotion.CANDIDATE_MS, 1f), 0f)
        // No motion: there at once.
        assertEquals(1f, KeyMotion.candidateAlpha(0L, 0f), 0f)
        assertEquals(0f, KeyMotion.candidateRiseDp(0L, 0f), 0f)
    }

    @Test fun easingStartsAtZeroAndEndsAtOne() {
        assertEquals(0f, KeyMotion.easeOut(0f), 0f)
        assertEquals(1f, KeyMotion.easeOut(1f), 0f)
        assertEquals(1f, KeyMotion.easeOut(2f), 0f)
        assertTrue(KeyMotion.easeOut(0.5f) > 0.5f)
        assertEquals(0f, KeyMotion.fraction(-5L, 100L), 0f)
        assertEquals(1f, KeyMotion.fraction(5L, 0L), 0f)
    }
}
