package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What each screen is for (docs/SPEC.md 4c, Droidtop/tracker#776): no screen is assumed better. */
class ScreenRolesTest {
    private fun screen(id: Int, builtIn: Boolean, name: String = "Screen $id") =
        ScreenFacts(id, "u$id", name, if (builtIn) ScreenClass.BUILT_IN else ScreenClass.EXTERNAL, 1080, 1920)

    private val builtIn = screen(0, builtIn = true)
    private val addOn = screen(9, builtIn = false)
    private val avd2 = screen(2, builtIn = false)
    private val avd3 = screen(3, builtIn = false)

    @Test fun `with nothing answered the menus stay where droidtop started, and nothing is the companion's`() {
        val a = ScreenRoles.arrange(listOf(builtIn, avd2, avd3), emptyMap())
        assertEquals(0, a.mainId)
        assertNull(a.companionId)
        assertEquals(listOf(2, 3), a.unanswered)
    }

    @Test fun `one screen alone is the main one and nothing is asked`() {
        val a = ScreenRoles.arrange(listOf(builtIn), emptyMap())
        assertEquals(0, a.mainId)
        assertNull(a.companionId)
        assertEquals(emptyList<Int>(), a.unanswered)
    }

    @Test fun `an add-on answered Main screen takes the menus, and the built-in screen is the companion's`() {
        val a = ScreenRoles.arrange(listOf(builtIn, addOn), mapOf("u9" to ScreenRole.MAIN))
        assertEquals(9, a.mainId)
        assertEquals(0, a.companionId)
        assertEquals(emptyList<Int>(), a.unanswered)
    }

    @Test fun `an add-on answered Companion takes the companion, and the menus stay on the built-in screen`() {
        val a = ScreenRoles.arrange(listOf(builtIn, avd2, avd3), mapOf("u2" to ScreenRole.COMPANION, "u3" to ScreenRole.MIRROR))
        assertEquals(0, a.mainId)
        assertEquals(2, a.companionId)
        assertEquals(setOf(3), a.mirrored)
        assertEquals(emptyList<Int>(), a.unanswered)
    }

    @Test fun `an answer follows the screen, not the display id it came up with`() {
        val replugged = addOn.copy(displayId = 14)
        assertEquals(14, ScreenRoles.arrange(listOf(builtIn, replugged), mapOf("u9" to ScreenRole.MAIN)).mainId)
    }

    @Test fun `Nothing and Mirror leave the menus home with no companion`() {
        val a = ScreenRoles.arrange(listOf(builtIn, addOn), mapOf("u9" to ScreenRole.NOTHING))
        assertEquals(0, a.mainId)
        assertNull(a.companionId)
    }

    @Test fun `a markedly larger screen suggests Main screen, a similar or smaller one Companion`() {
        // A 6-inch 1080x1920 handheld panel.
        val handheld = ScreenRoles.Size(1080, 1920, xdpi = 367f, ydpi = 367f, builtIn = true)
        // A 27-inch 4K monitor and a 3.9-inch add-on.
        val monitor = ScreenRoles.Size(3840, 2160, xdpi = 163f, ydpi = 163f, builtIn = false)
        val smallAddOn = ScreenRoles.Size(1080, 1240, xdpi = 400f, ydpi = 400f, builtIn = false)
        assertEquals(ScreenRole.MAIN, ScreenRoles.suggest(monitor, handheld))
        assertEquals(ScreenRole.COMPANION, ScreenRoles.suggest(smallAddOn, handheld))
        assertEquals(ScreenRole.COMPANION, ScreenRoles.suggest(handheld.copy(builtIn = false), handheld))
    }

    @Test fun `without a believable density only an external screen with twice the pixels counts as larger`() {
        val handheld = ScreenRoles.Size(1080, 1920, xdpi = 0f, ydpi = 0f, builtIn = true)
        assertEquals(ScreenRole.MAIN, ScreenRoles.suggest(ScreenRoles.Size(3840, 2160, 0f, 0f, builtIn = false), handheld))
        assertEquals(ScreenRole.COMPANION, ScreenRoles.suggest(ScreenRoles.Size(1920, 1080, 0f, 0f, builtIn = false), handheld))
        // A second built-in panel is never taken for a monitor by its pixels.
        assertEquals(ScreenRole.COMPANION, ScreenRoles.suggest(ScreenRoles.Size(3840, 2160, 0f, 0f, builtIn = true), handheld))
    }
}

/** Desktop's own answer per screen (Droidtop/tracker#908 step D5): asked once, remembered, never assumed. */
class DesktopScreenChoiceTest {
    private fun screen(id: Int, builtIn: Boolean) =
        ScreenFacts(id, "u$id", "Screen $id", if (builtIn) ScreenClass.BUILT_IN else ScreenClass.EXTERNAL, 1080, 1920)

    private val builtIn = screen(0, builtIn = true)
    private val addOn = screen(9, builtIn = false)
    private val avd2 = screen(2, builtIn = false)
    private val avd3 = screen(3, builtIn = false)

    @Test fun `with nothing answered the desktop extends nowhere`() {
        assertEquals(emptySet<Int>(), ScreenRoles.desktopExtends(listOf(builtIn, addOn), desktopId = 0, choices = emptyMap()))
    }

    @Test fun `the desktop extends to the screens answered Extend, never to its own`() {
        val choices = mapOf("u2" to DesktopScreenChoice.EXTEND, "u3" to DesktopScreenChoice.COMPANION, "u0" to DesktopScreenChoice.EXTEND)
        assertEquals(setOf(2), ScreenRoles.desktopExtends(listOf(builtIn, avd2, avd3), desktopId = 0, choices = choices))
        // The desktop on the add-on (it is the main screen): the built-in screen can be extended to.
        assertEquals(setOf(0, 2), ScreenRoles.desktopExtends(listOf(builtIn, avd2, avd3), desktopId = 3, choices = choices))
    }

    @Test fun `the answer follows the screen, whatever display id it comes back with`() {
        val replugged = addOn.copy(displayId = 14)
        assertEquals(setOf(14), ScreenRoles.desktopExtends(listOf(builtIn, replugged), 0, mapOf("u9" to DesktopScreenChoice.EXTEND)))
        assertNull(ScreenRoles.desktopToAsk(listOf(builtIn, replugged), 0, mapOf("u9" to DesktopScreenChoice.COMPANION), emptySet(), emptySet()))
    }

    @Test fun `each unanswered screen is asked about once, lowest id first, after its role`() {
        val screens = listOf(builtIn, avd3, avd2)
        assertEquals(2, ScreenRoles.desktopToAsk(screens, 0, emptyMap(), emptySet(), emptySet())?.displayId)
        assertEquals(3, ScreenRoles.desktopToAsk(screens, 0, mapOf("u2" to DesktopScreenChoice.EXTEND), emptySet(), emptySet())?.displayId)
        // Asked already while droidtop runs (answered or closed): not again.
        assertEquals(3, ScreenRoles.desktopToAsk(screens, 0, emptyMap(), emptySet(), setOf("u2"))?.displayId)
        assertNull(ScreenRoles.desktopToAsk(screens, 0, emptyMap(), emptySet(), setOf("u2", "u3")))
        // A screen still waiting for its role is asked that first.
        assertEquals(3, ScreenRoles.desktopToAsk(screens, 0, emptyMap(), roleUnanswered = setOf(2), asked = emptySet())?.displayId)
        // The desktop's own screen is never asked about; one screen alone asks nothing.
        assertNull(ScreenRoles.desktopToAsk(listOf(builtIn), 0, emptyMap(), emptySet(), emptySet()))
        assertEquals(0, ScreenRoles.desktopToAsk(listOf(builtIn, avd2), 2, emptyMap(), emptySet(), emptySet())?.displayId)
    }

    @Test fun `the question starts where the screens' sizes point, never a fixed answer`() {
        val handheld = ScreenRoles.Size(1080, 1920, xdpi = 367f, ydpi = 367f, builtIn = true)
        val monitor = ScreenRoles.Size(3840, 2160, xdpi = 163f, ydpi = 163f, builtIn = false)
        val smallAddOn = ScreenRoles.Size(1080, 1240, xdpi = 400f, ydpi = 400f, builtIn = false)
        assertEquals(DesktopScreenChoice.EXTEND, ScreenRoles.suggestDesktop(monitor, handheld))
        assertEquals(DesktopScreenChoice.COMPANION, ScreenRoles.suggestDesktop(smallAddOn, handheld))
        // The desktop on the monitor: the handheld's own screen is the smaller one.
        assertEquals(DesktopScreenChoice.COMPANION, ScreenRoles.suggestDesktop(handheld, monitor))
    }
}
