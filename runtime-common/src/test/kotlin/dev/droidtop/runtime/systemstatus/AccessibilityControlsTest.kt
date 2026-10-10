package dev.droidtop.runtime.systemstatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Accessibility card's writes (AccessibilityControls.kt, Droidtop/tracker#414 slice C22). */
class AccessibilityControlsTest {
    @Test fun `wm density's answer reads as physical and override`() {
        assertEquals(420 to null, AccessibilityControls.parseDensity("Physical density: 420\n"))
        assertEquals(420 to 504, AccessibilityControls.parseDensity("Physical density: 420\nOverride density: 504\n"))
        assertNull(AccessibilityControls.parseDensity("nothing").first)
    }

    @Test fun `display size is a factor of the panel's own density`() {
        assertEquals(504, AccessibilityControls.densityFor(420, 1.2f))
        assertEquals(1.2f, AccessibilityControls.factorOf(420, 504), 0.0001f)
        assertEquals(1.0f, AccessibilityControls.factorOf(420, 421), 0.0001f)
    }

    @Test fun `TalkBack is added or taken out, other services kept`() {
        val other = "dev.droidtop.app/dev.droidtop.app.TypingAccessibilityService"
        val on = AccessibilityControls.withTalkBack(other, on = true)
        assertTrue(AccessibilityControls.talkBackOn(on))
        assertTrue(on.startsWith(other))
        assertEquals(other, AccessibilityControls.withTalkBack(on, on = false))
        assertEquals("", AccessibilityControls.withTalkBack(AccessibilityControls.TALKBACK, on = false))
        assertFalse(AccessibilityControls.talkBackOn(null))
    }
}
