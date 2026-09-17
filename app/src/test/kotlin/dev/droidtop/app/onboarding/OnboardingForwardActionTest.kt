package dev.droidtop.app.onboarding

import dev.droidtop.app.onboardingForwardLabel
import dev.droidtop.app.onboardingForwardLabelWhenAnswerRequired
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One way forward per step (docs/SPEC.md 7b). The label is the whole
 * affordance: with only one action there, it has to say whether moving
 * on answers the step or skips it.
 */
class OnboardingForwardActionTest {

    @Test
    fun `an unanswered step moves on by skipping, and says so`() {
        assertEquals("Skip this step", onboardingForwardLabel(reEntry = false, answered = false))
    }

    @Test
    fun `an answered step moves on to the next one`() {
        assertEquals("Next", onboardingForwardLabel(reEntry = false, answered = true))
    }

    @Test
    fun `a step opened from Settings has nothing after it`() {
        assertEquals("Done", onboardingForwardLabel(reEntry = true, answered = false))
        assertEquals("Done", onboardingForwardLabel(reEntry = true, answered = true))
    }

    @Test
    fun `a step that cannot be skipped shows no forward action until it is answered`() {
        assertNull(onboardingForwardLabelWhenAnswerRequired(answered = false))
        assertEquals("Next", onboardingForwardLabelWhenAnswerRequired(answered = true))
    }
}
