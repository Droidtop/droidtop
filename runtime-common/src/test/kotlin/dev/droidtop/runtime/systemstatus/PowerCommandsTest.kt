package dev.droidtop.runtime.systemstatus

import dev.droidtop.runtime.tasks.ForceStopResult
import dev.droidtop.runtime.tasks.PrivilegedShell
import dev.droidtop.runtime.tasks.ShellOutput
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerCommandsTest {
    private class Recording(private val reply: ShellOutput?) : PrivilegedShell {
        var last: List<String>? = null
        override fun forceStop(packageName: String) = ForceStopResult.NoProvider
        override fun exec(argv: List<String>): ShellOutput? {
            last = argv
            return reply
        }
    }

    @Test
    fun `each action sends its own command and a zero exit is success`() {
        val shell = Recording(ShellOutput(0, "", ""))
        assertEquals("", PowerAction.SLEEP.run(shell))
        assertEquals(listOf("input", "keyevent", "KEYCODE_SLEEP"), shell.last)
        assertEquals("", PowerAction.POWER_OFF.run(shell))
        assertEquals(listOf("svc", "power", "shutdown"), shell.last)
        assertEquals("", PowerAction.RESTART.run(shell))
        assertEquals(listOf("svc", "power", "reboot"), shell.last)
    }

    @Test
    fun `a failed or unanswered command is reported`() {
        assertEquals("Failed: the helper could not do it", PowerAction.SLEEP.run(Recording(ShellOutput(1, "", "no"))))
        assertEquals("Failed: the helper could not do it", PowerAction.SLEEP.run(Recording(null)))
    }
}
