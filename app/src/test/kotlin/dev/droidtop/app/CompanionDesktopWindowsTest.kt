package dev.droidtop.app

import dev.droidtop.hostbridge.Toplevel
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionDesktopWindowsTest {
    private fun window(title: String, appId: String, activated: Boolean = false) =
        Toplevel(1, title, appId, activated, minimized = false, maximized = false, fullscreen = false)

    @Test
    fun `a window says where it is and, when its title does not, which app it is`() {
        assertEquals("In front · org.gnome.TextEditor", windowState(window("notes.txt", "org.gnome.TextEditor", activated = true), false))
        assertEquals("Minimized · firefox", windowState(window("Mozilla Firefox", "firefox"), true))
        assertEquals("Open", windowState(window("foot", "foot"), false))
    }
}
