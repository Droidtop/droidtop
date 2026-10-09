package dev.droidtop.app

import android.view.KeyEvent
import dev.droidtop.library.settings.TrackpadSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pocketworkstation.pckeyboard.MacroPlayer

/** The companion's Input tab (CompanionInput.kt, Droidtop/tracker#414 slice C12). */
class CompanionInputTest {
    private val companion = InputTarget.Screen(2, "local:2", "Add-on screen")
    private val main = InputTarget.Screen(0, "local:0", "Built-in screen")
    private val monitor = InputTarget.Screen(5, "hdmi:5", "Monitor, 27 in")
    private val screens = listOf(main, companion, monitor)

    @Test fun `keys go to the shell's screen, never the companion's own`() {
        assertEquals(0, InputTarget.displayId(own = 2, shell = 0, screens = screens, pinned = null))
        // The shell is on the companion's screen (or unknown): the first other screen, still never the companion.
        assertEquals(0, InputTarget.displayId(own = 2, shell = 2, screens = screens, pinned = null))
        assertEquals(0, InputTarget.displayId(own = 2, shell = null, screens = screens, pinned = null))
    }

    @Test fun `a pinned screen wins while it is connected`() {
        assertEquals(5, InputTarget.displayId(own = 2, shell = 0, screens = screens, pinned = "hdmi:5"))
        // Unplugged: back to the shell's screen.
        assertEquals(0, InputTarget.displayId(own = 2, shell = 0, screens = listOf(main, companion), pinned = "hdmi:5"))
        // Pinning the companion itself is never honoured.
        assertEquals(0, InputTarget.displayId(own = 2, shell = 0, screens = screens, pinned = "local:2"))
    }

    @Test fun `the header always names the target, and Desktop types to the Linux desktop`() {
        assertEquals("Typing to: Linux desktop", InputTarget.label(desktop = true, target = main, pinned = false))
        assertEquals("Typing to: Monitor, 27 in", InputTarget.label(desktop = false, target = monitor, pinned = false))
        assertEquals("Typing to: Monitor, 27 in (pinned)", InputTarget.label(desktop = false, target = monitor, pinned = true))
        assertEquals("Typing to: no other screen", InputTarget.label(desktop = false, target = null, pinned = false))
    }

    @Test fun `the trackpad settings reach the gesture engine`() {
        val config = trackpadConfig(
            TrackpadSettings(tapToClick = false, tapMs = 250, dragWindowMs = 500, twoFingerRightClick = false, threeFingerMiddleClick = false, dragLock = true, momentum = true, twoFingerScroll = false),
        )
        assertFalse(config.tapToClick)
        assertEquals(250L, config.tapTimeoutMs)
        assertEquals(500L, config.tapDragTimeoutMs)
        assertFalse(config.twoFingerRightClick || config.threeFingerMiddleClick || config.twoFingerScroll)
        assertTrue(config.dragLock && config.momentum)
        // Defaults are libinput's: tap, two- and three-finger taps and scroll on, drag lock and momentum off.
        val defaults = trackpadConfig(TrackpadSettings())
        assertTrue(defaults.tapToClick && defaults.twoFingerScroll && defaults.twoFingerRightClick && defaults.threeFingerMiddleClick)
        assertFalse(defaults.dragLock || defaults.momentum)
        assertEquals(0.5f, trackpadSpeed(TrackpadSettings(speed = 5)), 0.0001f)
        assertEquals(-1f, trackpadSpeed(TrackpadSettings(speed = -30)), 0.0001f)
    }

    @Test fun `a chord plays as a held modifier around the key, on whichever route types`() {
        val keys = mutableListOf<Pair<Int, Boolean>>()
        val player = MacroPlayer({ code, down -> keys += code to down }, {})
        player.play(CompanionChords.ALL.single { it.name == "Alt+Tab" })
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_ALT_LEFT to true,
                KeyEvent.KEYCODE_TAB to true,
                KeyEvent.KEYCODE_TAB to false,
                KeyEvent.KEYCODE_ALT_LEFT to false,
            ),
            keys,
        )
        keys.clear()
        player.play(CompanionChords.ALL.single { it.name == "Super" })
        assertEquals(listOf(KeyEvent.KEYCODE_META_LEFT to true, KeyEvent.KEYCODE_META_LEFT to false), keys)
        // Every chord leaves nothing held.
        for (macro in CompanionChords.ALL) {
            keys.clear()
            player.play(macro)
            assertEquals(macro.name, keys.count { it.second }, keys.count { !it.second })
        }
    }

    @Test fun `controllers show their battery, and Home names the one in hand`() {
        val rp5 = Controllers.Pad("Retroid Pocket Controller", 80)
        val bt = Controllers.Pad("Xbox Wireless Controller", 55)
        val old = Controllers.Pad("Generic Pad", null)
        assertEquals("Retroid Pocket Controller 80%, Generic Pad", Controllers.line(listOf(rp5, old)))
        assertNull(Controllers.line(emptyList()))
        assertEquals("Pad 80%", Controllers.status(listOf(rp5, old)))
        // Two reporting a battery: which one is in hand is not known, so Home says nothing.
        assertNull(Controllers.status(listOf(rp5, bt)))
    }
}
