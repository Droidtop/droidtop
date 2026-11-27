package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** Enginehost's `state` reply, read as droidtop's session watch reads it (docs/SPEC.md, mytop-project/tracker#967). */
class EnginehostStateAnswerTest {
    @Test
    fun theLibraryOpenAfterAStoppedLaunchIsNoGame() {
        assertEquals(false, EnginehostCapabilities.stateAnswer(game = false, tasks = 1))
    }

    @Test
    fun aGameOrItsLaunchKeepsTheSession() {
        assertEquals(true, EnginehostCapabilities.stateAnswer(game = true, tasks = 1))
    }

    @Test
    fun anOlderEnginehostIsReadByItsTasks() {
        assertEquals(true, EnginehostCapabilities.stateAnswer(game = null, tasks = 2))
        assertEquals(false, EnginehostCapabilities.stateAnswer(game = null, tasks = 0))
    }

    @Test
    fun noAnswerSaysNothing() {
        assertEquals(null, EnginehostCapabilities.stateAnswer(game = null, tasks = null))
    }
}
