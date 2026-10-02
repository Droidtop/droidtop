package dev.droidtop.shell.gamepad.theme

import dev.droidtop.library.theme.EsDeViewTransition
import org.junit.Assert.assertEquals
import org.junit.Test

/** [esDeTransitionKind]: the system view is the null group, every other view is a gamelist. */
class EsDeTransitionKindTest {
    @Test
    fun `a move between system views is system to system`() {
        assertEquals(EsDeViewTransition.SYSTEM_TO_SYSTEM, esDeTransitionKind(null, null))
    }

    @Test
    fun `leaving the system view for a group is system to gamelist`() {
        assertEquals(EsDeViewTransition.SYSTEM_TO_GAMELIST, esDeTransitionKind(null, "snes"))
    }

    @Test
    fun `going back from a group to the system view is gamelist to system`() {
        assertEquals(EsDeViewTransition.GAMELIST_TO_SYSTEM, esDeTransitionKind("snes", null))
    }

    @Test
    fun `a move between two groups is gamelist to gamelist`() {
        assertEquals(EsDeViewTransition.GAMELIST_TO_GAMELIST, esDeTransitionKind("snes", "nes"))
    }
}
