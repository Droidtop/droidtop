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

class SecondScreenPairingTest {
    private fun output(id: Int, kind: dev.droidtop.runtime.DisplayOutputKind = dev.droidtop.runtime.DisplayOutputKind.SECOND_SCREEN) =
        dev.droidtop.runtime.DisplayOutput(id = "d$id", androidDisplayId = id, kind = kind, widthPx = 1920, heightPx = 1080)

    private val builtIn = output(0, dev.droidtop.runtime.DisplayOutputKind.PRIMARY_SCREEN)

    @Test
    fun `the paired screen is the main one when the menus are elsewhere, else the companion's`() {
        val outputs = listOf(builtIn, output(2), output(3))
        assertEquals(2, SecondScreenPlan.pairedSecond(outputs, mainId = 2, companionId = 3)?.androidDisplayId)
        assertEquals(3, SecondScreenPlan.pairedSecond(outputs, mainId = 0, companionId = 3)?.androidDisplayId)
        assertNull(SecondScreenPlan.pairedSecond(outputs, mainId = 0, companionId = null))
        // A screen that is gone, or the built-in one, is never the paired second screen.
        assertNull(SecondScreenPlan.pairedSecond(outputs, mainId = 0, companionId = 7))
        assertNull(SecondScreenPlan.pairedSecond(listOf(builtIn), mainId = 0, companionId = 0))
    }

    @Test
    fun `every unmirrored second screen is covered, the companion's first`() {
        val outputs = listOf(builtIn, output(2), output(3), output(4))
        assertEquals(listOf(3, 2, 4), SecondScreenPlan.coverCandidates(outputs, mirroredIds = emptySet(), companionDisplayId = 3))
        assertEquals(listOf(2), SecondScreenPlan.coverCandidates(outputs, mirroredIds = setOf(3, 4), companionDisplayId = 3))
        assertEquals(emptyList<Int>(), SecondScreenPlan.coverCandidates(listOf(builtIn), emptySet(), null))
    }
}

/** The desktop on a second screen (Droidtop/tracker#908 step D5): nothing of the companion's covers it there. */
class SecondScreenDesktopTest {
    private fun output(id: Int, kind: dev.droidtop.runtime.DisplayOutputKind = dev.droidtop.runtime.DisplayOutputKind.SECOND_SCREEN) =
        dev.droidtop.runtime.DisplayOutput(id = "d$id", androidDisplayId = id, kind = kind, widthPx = 1920, heightPx = 1080)

    @Test
    fun `the companion's Presentation never goes where the desktop is drawn`() {
        assertNull(SecondScreenPlan.companionPresentation(2, desktopOn = setOf(2)))
        assertEquals(2, SecondScreenPlan.companionPresentation(2, desktopOn = setOf(3)))
        assertEquals(2, SecondScreenPlan.companionPresentation(2, desktopOn = emptySet()))
        assertNull(SecondScreenPlan.companionPresentation(null, desktopOn = setOf(2)))
    }

    @Test
    fun `the idle cover never goes where the desktop is drawn`() {
        val outputs = listOf(output(0, dev.droidtop.runtime.DisplayOutputKind.PRIMARY_SCREEN), output(2), output(3))
        assertEquals(listOf(3), SecondScreenPlan.coverCandidates(outputs, emptySet(), companionDisplayId = 2, desktopOn = setOf(2)))
        assertEquals(listOf(2, 3), SecondScreenPlan.coverCandidates(outputs, emptySet(), companionDisplayId = 2, desktopOn = emptySet()))
        assertEquals(false, SecondScreenPlan.mayCover(2, setOf(2)))
        assertEquals(true, SecondScreenPlan.mayCover(2, setOf(3)))
    }

    @Test
    fun `with the desktop on the built-in screen the shell still moves, but the companion is not put there`() {
        val onBuiltIn = setOf(android.view.Display.DEFAULT_DISPLAY)
        assertEquals(Relocation.TO_SECOND, SecondScreenPlan.aroundDesktop(Relocation.TO_SECOND_WITH_COMPANION, onBuiltIn))
        assertEquals(Relocation.NONE, SecondScreenPlan.aroundDesktop(Relocation.COMPANION_ON_BUILT_IN, onBuiltIn))
        assertEquals(Relocation.TO_BUILT_IN, SecondScreenPlan.aroundDesktop(Relocation.TO_BUILT_IN, onBuiltIn))
        // Elsewhere, or nowhere, the step is unchanged.
        Relocation.entries.forEach { assertEquals(it, SecondScreenPlan.aroundDesktop(it, setOf(2))) }
        Relocation.entries.forEach { assertEquals(it, SecondScreenPlan.aroundDesktop(it, emptySet())) }
    }

    @Test
    fun `a second screen the desktop is drawn on is in use, not broken`() {
        assertEquals(false, SecondScreenPlan.reinitOffered(true, secondId = 2, desktopOn = setOf(2)))
        assertEquals(true, SecondScreenPlan.reinitOffered(true, secondId = 2, desktopOn = emptySet()))
        assertEquals(false, SecondScreenPlan.reinitOffered(false, secondId = 2, desktopOn = emptySet()))
    }
}
