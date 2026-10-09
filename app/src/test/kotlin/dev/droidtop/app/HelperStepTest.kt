package dev.droidtop.app

import dev.droidtop.runtime.tasks.BackendState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The helper-app page's one button (CompanionPins.kt, HelperStep). */
class HelperStepTest {
    @Test fun theNextStepFollowsWhereShizukuStands() {
        assertEquals(HelperStep.GET, HelperStep.next(shizukuInstalled = false, state = BackendState.ABSENT))
        assertEquals(HelperStep.OPEN, HelperStep.next(shizukuInstalled = true, state = BackendState.ABSENT))
        assertEquals(HelperStep.ALLOW, HelperStep.next(shizukuInstalled = true, state = BackendState.NEEDS_PERMISSION))
        assertEquals(HelperStep.DONE, HelperStep.next(shizukuInstalled = true, state = BackendState.READY))
    }
}
