package dev.droidtop.app.onboarding

import dev.droidtop.app.desktopCheckMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Onboarding's Desktop check, said plainly (Droidtop/tracker#555). */
class DesktopCheckMessageTest {
    @Test
    fun `a kernel that refuses ptrace names root through Shizuku as the way`() {
        val message = desktopCheckMessage(false, rooted = false, error = "proot error: ptrace(TRACEME): Operation not permitted")
        assertTrue(message, message.contains("Shizuku started as root"))
    }

    @Test
    fun `other outcomes keep their sentences`() {
        assertEquals("Desktop can't run on this device.", desktopCheckMessage(false, rooted = false, error = "could not start proot"))
        assertEquals("Desktop can run here (not rooted, a little slower).", desktopCheckMessage(true, rooted = false, error = ""))
        assertEquals("Desktop can run here (rooted, full speed).", desktopCheckMessage(true, rooted = true, error = ""))
        assertEquals("Desktop can't start yet. Try again later in Settings.", desktopCheckMessage(false, rooted = true, error = "ptrace"))
    }
}
