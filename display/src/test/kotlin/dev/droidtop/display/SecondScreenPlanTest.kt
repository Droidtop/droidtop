package dev.droidtop.display

import dev.droidtop.display.SecondScreenPlan.Relocation
import dev.droidtop.runtime.DualScreenOrchestration.ShellMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondScreenPlanTest {
    private fun relocation(
        move: ShellMove,
        current: Int = 0,
        second: Int? = 2,
        onSecond: Boolean = current == second,
        want: Boolean = true,
        companion: Boolean = false,
        appOnCompanion: Boolean = false,
        cooled: Boolean = true,
    ) = SecondScreenPlan.relocation(move, current, second, onSecond, want, companion, appOnCompanion, cooled)

    @Test
    fun `a game opens where the setting says, the shell's own screen by default`() {
        assertNull(SecondScreenPlan.gameLaunchDisplay(null, shellOnSecond = true, secondId = 2))
        assertNull(SecondScreenPlan.gameLaunchDisplay(DisplayRolePrefs.GameLaunchTarget.ASK, true, 2))
        assertEquals(0, SecondScreenPlan.gameLaunchDisplay(DisplayRolePrefs.GameLaunchTarget.BUILT_IN, true, 2))
        assertEquals(2, SecondScreenPlan.gameLaunchDisplay(DisplayRolePrefs.GameLaunchTarget.FOLLOW_SHELL, true, 2))
        assertNull(SecondScreenPlan.gameLaunchDisplay(DisplayRolePrefs.GameLaunchTarget.FOLLOW_SHELL, false, 2))
        assertEquals(2, SecondScreenPlan.gameLaunchDisplay(DisplayRolePrefs.GameLaunchTarget.SECOND, false, 2))
    }

    @Test
    fun `the shell moves to the second screen with the companion behind it, unless an app holds that screen`() {
        assertEquals(Relocation.TO_SECOND_WITH_COMPANION, relocation(ShellMove.TO_SECOND))
        assertEquals(Relocation.TO_SECOND, relocation(ShellMove.TO_SECOND, appOnCompanion = true))
        assertEquals(Relocation.TO_BUILT_IN, relocation(ShellMove.TO_BUILT_IN, current = 2))
    }

    @Test
    fun `on the second screen already, only a missing companion is put back`() {
        assertEquals(Relocation.COMPANION_ON_BUILT_IN, relocation(ShellMove.NONE, current = 2))
        assertEquals(Relocation.NONE, relocation(ShellMove.NONE, current = 2, companion = true))
        assertEquals(Relocation.NONE, relocation(ShellMove.NONE, current = 2, appOnCompanion = true))
        assertEquals(Relocation.NONE, relocation(ShellMove.NONE, current = 2, want = false))
    }

    @Test
    fun `nothing happens inside the cooldown, with no second screen, or with nothing to do`() {
        assertEquals(Relocation.NONE, relocation(ShellMove.TO_SECOND, cooled = false))
        assertEquals(Relocation.NONE, relocation(ShellMove.TO_BUILT_IN, current = 2, cooled = false))
        assertEquals(Relocation.NONE, relocation(ShellMove.TO_SECOND, second = null))
        assertEquals(Relocation.NONE, relocation(ShellMove.NONE))
    }
}
