package dev.droidtop.runtime.windows

import org.junit.Assert.assertEquals
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
}
