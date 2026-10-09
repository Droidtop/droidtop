package dev.droidtop.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LaunchReplace] (docs/SPEC.md "One launch replaces the running game"). */
class LaunchReplaceTest {
    private val emerald = LaunchReplace.Running(gameId = "gba/emerald", packageName = "com.retroarch.aarch64")

    @Test
    fun `another game in the same emulator replaces the running one`() {
        assertTrue(LaunchReplace.replaces(emerald, "com.retroarch.aarch64", "gbc/crystal"))
    }

    @Test
    fun `resuming the same game, another emulator, or nothing running is not a replacement`() {
        assertFalse(LaunchReplace.replaces(emerald, "com.retroarch.aarch64", "gba/emerald"))
        assertFalse(LaunchReplace.replaces(emerald, "xyz.aethersx2.tturnip", "ps2/game"))
        assertFalse(LaunchReplace.replaces(null, "com.retroarch.aarch64", "gbc/crystal"))
        assertFalse(LaunchReplace.replaces(emerald, "com.retroarch.aarch64", null))
    }

    @Test
    fun `only a RetroArch launch that could not end the old game is delivered twice`() {
        assertTrue(LaunchReplace.redeliver("com.retroarch.aarch64", ended = false))
        assertFalse(LaunchReplace.redeliver("com.retroarch.aarch64", ended = true))
        assertFalse(LaunchReplace.redeliver("xyz.aethersx2.tturnip", ended = false))
    }
}
