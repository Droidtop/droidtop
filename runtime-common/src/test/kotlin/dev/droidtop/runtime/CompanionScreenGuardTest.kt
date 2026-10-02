package dev.droidtop.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionScreenGuardTest {
    private val builtIn = 0
    private val addOn = 13

    @Test
    fun anAppLaunchedOntoTheCompanionsScreenOwnsIt() {
        assertTrue(CompanionScreenGuard.appInFrontOfCompanion(addOn, builtIn, parkedDisplayId = builtIn, companionCoveredDisplayId = null))
    }

    @Test
    fun anAppTheUserOpenedOverTheCompanionOwnsIt() {
        assertTrue(CompanionScreenGuard.appInFrontOfCompanion(addOn, builtIn, parkedDisplayId = null, companionCoveredDisplayId = builtIn))
    }

    @Test
    fun anIdleCompanionScreenIsNotOwned() {
        assertFalse(CompanionScreenGuard.appInFrontOfCompanion(addOn, builtIn, parkedDisplayId = null, companionCoveredDisplayId = null))
    }

    @Test
    fun anAppOnTheShellsOwnScreenDoesNotOwnTheCompanionsScreen() {
        assertFalse(CompanionScreenGuard.appInFrontOfCompanion(addOn, builtIn, parkedDisplayId = addOn, companionCoveredDisplayId = null))
    }

    @Test
    fun theShellsOwnScreenIsNeverTheCompanionsScreen() {
        assertFalse(CompanionScreenGuard.appInFrontOfCompanion(builtIn, builtIn, parkedDisplayId = builtIn, companionCoveredDisplayId = builtIn))
    }
}
