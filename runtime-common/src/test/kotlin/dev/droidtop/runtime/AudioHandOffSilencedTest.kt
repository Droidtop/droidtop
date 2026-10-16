package dev.droidtop.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing of droidtop's plays beneath a modal layer (docs/SPEC.md "Launch audio hand-off", Droidtop/tracker#160). */
class AudioHandOffSilencedTest {
    @Test
    fun `nothing plays beneath an open layer, and a hand-off supersedes the rule`() {
        assertTrue(AudioHandOff.silenced(layerOpen = true, handedOff = false))
        assertFalse(AudioHandOff.silenced(layerOpen = false, handedOff = false))
        assertFalse(AudioHandOff.silenced(layerOpen = true, handedOff = true))
        assertFalse(AudioHandOff.silenced(layerOpen = false, handedOff = true))
    }
}
