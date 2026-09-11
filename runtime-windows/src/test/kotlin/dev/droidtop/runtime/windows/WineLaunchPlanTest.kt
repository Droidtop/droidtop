package dev.droidtop.runtime.windows

import com.winlator.winhandler.WinHandler.PreferredInputApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The launch decisions that are pure functions of the prefix and the
 * target. They are the half of the Wine seam that can be checked without
 * a device, and each case here is one that produced a real failure:
 * a quoted path Wine could not resolve, a guest with no virtual desktop
 * to draw into, and an audio server whose socket and whose environment
 * variable disagreed.
 */
class WineLaunchPlanTest {

    @Test
    fun `guest command opens a virtual desktop at the X screen size`() {
        assertEquals(
            "wine explorer /desktop=shell,1280x720 /storage/games/Game.exe",
            WineLaunchPlan.guestExecutable("1280x720", "/storage/games/Game.exe"),
        )
    }

    @Test
    fun `spaces in a target are escaped, not quoted`() {
        // ProcessHelper.splitCommand keeps quote characters inside the
        // argument, which would hand Wine a path that does not exist.
        assertEquals(
            "wine explorer /desktop=shell,1280x800 /storage/My\\ Games/A\\ Game.exe",
            WineLaunchPlan.guestExecutable("1280x800", "/storage/My Games/A Game.exe"),
        )
    }

    @Test
    fun `the prefix's own arguments follow the target`() {
        assertEquals(
            "wine explorer /desktop=shell,1280x720 /storage/games/Game.exe -windowed -nosound",
            WineLaunchPlan.guestExecutable("1280x720", "/storage/games/Game.exe", " -windowed -nosound "),
        )
        // No arguments must not leave a trailing space: the whole string
        // is split on spaces into argv.
        assertEquals(
            "wine explorer /desktop=shell,1280x720 /storage/games/Game.exe",
            WineLaunchPlan.guestExecutable("1280x720", "/storage/games/Game.exe", "   "),
        )
    }

    @Test
    fun `audio driver names map to the components that serve them`() {
        assertEquals(WineLaunchPlan.AudioDriver.PULSEAUDIO, WineLaunchPlan.audioDriverOf("pulseaudio"))
        assertEquals(WineLaunchPlan.AudioDriver.ALSA, WineLaunchPlan.audioDriverOf("alsa"))
        // A prefix carrying neither gets no audio server rather than a
        // crash -- and null is a real case for a container written before
        // the field existed.
        assertEquals(WineLaunchPlan.AudioDriver.NONE, WineLaunchPlan.audioDriverOf(null))
        assertEquals(WineLaunchPlan.AudioDriver.NONE, WineLaunchPlan.audioDriverOf(""))
    }

    @Test
    fun `each audio driver points the guest at the socket its own component binds`() {
        assertEquals(
            mapOf("PULSE_SERVER" to "/data/rootfs/tmp/.sound/PS0"),
            WineLaunchPlan.audioEnvVars("pulseaudio", "/data/rootfs"),
        )
        assertEquals(
            mapOf(
                "ANDROID_ALSA_SERVER" to "/data/rootfs/tmp/.sound/AS0",
                "ANDROID_ASERVER_USE_SHM" to "true",
            ),
            WineLaunchPlan.audioEnvVars("alsa", "/data/rootfs"),
        )
        assertEquals(emptyMap<String, String>(), WineLaunchPlan.audioEnvVars(null, "/data/rootfs"))
    }

    @Test
    fun `a prefix that does not claim the SDL controller API is told nothing`() {
        assertEquals(
            emptyMap<String, String>(),
            WineLaunchPlan.controllerEnvVars(false, PreferredInputApi.BOTH.ordinal),
        )
    }

    @Test
    fun `SDL is told which joystick backend the prefix's input API means`() {
        val xinput = WineLaunchPlan.controllerEnvVars(true, PreferredInputApi.XINPUT.ordinal)
        assertEquals("1", xinput["SDL_XINPUT_ENABLED"])
        assertEquals("0", xinput["SDL_DIRECTINPUT_ENABLED"])
        assertEquals("1", xinput["SDL_JOYSTICK_HIDAPI"])

        val dinput = WineLaunchPlan.controllerEnvVars(true, PreferredInputApi.DINPUT.ordinal)
        assertEquals("0", dinput["SDL_XINPUT_ENABLED"])
        assertEquals("1", dinput["SDL_DIRECTINPUT_ENABLED"])
        assertEquals("0", dinput["SDL_JOYSTICK_HIDAPI"])

        val both = WineLaunchPlan.controllerEnvVars(true, PreferredInputApi.BOTH.ordinal)
        assertEquals("1", both["SDL_XINPUT_ENABLED"])
        assertEquals("1", both["SDL_DIRECTINPUT_ENABLED"])

        // AUTO is xinput-first, the same reading upstream gives it.
        val auto = WineLaunchPlan.controllerEnvVars(true, PreferredInputApi.AUTO.ordinal)
        assertEquals("1", auto["SDL_XINPUT_ENABLED"])
        assertEquals("0", auto["SDL_DIRECTINPUT_ENABLED"])

        // Every prefix that claims the API gets the fixed half too, so a
        // background window keeps receiving pad events.
        assertEquals("1", both["SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS"])
        assertEquals("0", both["SDL_JOYSTICK_WGI"])
    }

    @Test
    fun `the turnip workaround is added for gen8 drivers and for nothing else`() {
        assertEquals("nolrz", WineLaunchPlan.turnipDebug("gen8-25.1.0", ""))
        assertEquals("noconform,nolrz", WineLaunchPlan.turnipDebug("GEN8-25.1.0", "noconform"))
        // Already set: nothing to write.
        assertNull(WineLaunchPlan.turnipDebug("gen8-25.1.0", "nolrz"))
        assertNull(WineLaunchPlan.turnipDebug("turnip-25.0.0", ""))
        assertNull(WineLaunchPlan.turnipDebug("", "noconform"))
    }

    @Test
    fun `a prefix's present mode becomes its own Vulkan mode, and fifo covers the rest`() {
        assertEquals(0, WinePresentation.vkPresentMode("immediate"))
        assertEquals(1, WinePresentation.vkPresentMode("MAILBOX"))
        assertEquals(3, WinePresentation.vkPresentMode("relaxed"))
        assertEquals(2, WinePresentation.vkPresentMode("fifo"))
        // An empty or unknown value is fifo, the mode Vulkan guarantees.
        assertEquals(2, WinePresentation.vkPresentMode(""))
        assertEquals(2, WinePresentation.vkPresentMode(null))
    }

    @Test
    fun `the fullscreen WM class is the executable name, whichever separator the target used`() {
        assertEquals("Game.exe", WinePresentation.fullscreenWMClass("/storage/games/Game.exe"))
        // A .desktop shortcut stores a Windows path.
        assertEquals("Game.exe", WinePresentation.fullscreenWMClass("C:\\Program Files\\Game\\Game.exe"))
        assertNull(WinePresentation.fullscreenWMClass("/storage/games/"))
    }
}
