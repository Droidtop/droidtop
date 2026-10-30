package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which feature steps wait at a place (docs/SPEC.md 7b, "Update onboarding"). */
class FeatureStepsTest {
    private fun step(id: String, place: String?) = FeatureStep(id = id, arrived = null, place = place, title = id, body = id)

    private val steps = listOf(step("chords", null), step("sites", "accounts"), step("streams", null))

    @Test
    fun `app-wide steps not yet seen wait, in registration order`() {
        assertEquals(listOf("chords", "streams"), FeatureSteps.unseenAt(steps, null, emptySet()).map { it.id })
    }

    @Test
    fun `a seen step never shows again`() {
        assertEquals(listOf("streams"), FeatureSteps.unseenAt(steps, null, setOf("chords")).map { it.id })
    }

    @Test
    fun `a step shows only at its own place`() {
        assertEquals(listOf("sites"), FeatureSteps.unseenAt(steps, "accounts", emptySet()).map { it.id })
        assertEquals(emptyList<String>(), FeatureSteps.unseenAt(steps, "controls", emptySet()).map { it.id })
    }
}
