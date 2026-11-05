package dev.droidtop.shell.desktop

import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import dev.droidtop.hostbridge.Toplevel
import dev.droidtop.library.TaskbarPin
import dev.droidtop.library.TaskbarPins
import dev.droidtop.runtime.ContainerApp
import dev.droidtop.shell.desktop.DesktopPadRoutes.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Desktop bar's pure decisions (Droidtop/tracker#731): pad routes, the row choice, titles and icons. */
class DesktopBarTest {
    @Test
    fun `four pad buttons are droidtop's, the rest stay with the container`() {
        assertEquals(Route.START_MENU, DesktopPadRoutes.routeFor(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(Route.QUICK_MENU, DesktopPadRoutes.routeFor(KeyEvent.KEYCODE_BUTTON_R2))
        assertEquals(Route.WINDOWS, DesktopPadRoutes.routeFor(KeyEvent.KEYCODE_BUTTON_L1))
        assertEquals(Route.SEARCH, DesktopPadRoutes.routeFor(KeyEvent.KEYCODE_BUTTON_R1))
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_UP)) {
            assertNull(DesktopPadRoutes.routeFor(code))
        }
    }

    @Test
    fun `the windows share the bar only while they keep a window's width`() {
        // The 2026-11-04 rig's AVD: a 639 dp bar whose buttons and tray took about 550 dp.
        assertFalse(windowsFitBeside(639.dp, 550.dp))
        assertTrue(windowsFitBeside(1280.dp, 550.dp))
        assertTrue(windowsFitBeside(750.dp, 550.dp))
        assertFalse(windowsFitBeside(749.dp, 550.dp))
    }

    @Test
    fun `a window is named by its title, else its app id`() {
        fun window(title: String, appId: String) = Toplevel(1, title, appId, false, false, false, false)
        assertEquals("notes.txt - Mousepad", windowTitle(window("notes.txt - Mousepad", "mousepad")))
        assertEquals("foot", windowTitle(window(" ", "foot")))
        assertEquals("(untitled window)", windowTitle(window("", "")))
    }

    private val foot = ContainerApp("foot.desktop", "Foot", listOf("foot"), terminal = false, iconFile = "/icons/foot.png")
    private val noIcon = ContainerApp("pcmanfm.desktop", "File Manager", listOf("pcmanfm"), terminal = false)

    @Test
    fun `a window finds its app's icon by app id, without case`() {
        val icons = windowIcons(listOf(foot, noIcon, foot.copy(id = "org.Gnome.Foo.desktop", iconFile = "/icons/foo.svg")))
        assertEquals("file:///icons/foot.png", icons["foot"])
        assertEquals("file:///icons/foo.svg", icons["org.gnome.foo"])
        assertFalse("pcmanfm" in icons)
    }

    @Test
    fun `a Linux pin without art takes its app's icon, and a pin with art keeps its own`() {
        val bare = TaskbarPin(TaskbarPins.linuxKey("foot.desktop"), "Foot")
        val drawn = TaskbarPin(TaskbarPins.linuxKey("foot.desktop"), "Foot", art = "file:///mine.png")
        val other = TaskbarPin("android:com.example", "Example")
        assertEquals(
            listOf(bare.copy(art = "file:///icons/foot.png"), drawn, other),
            withLinuxIcons(listOf(bare, drawn, other), listOf(foot, noIcon)),
        )
    }
}
