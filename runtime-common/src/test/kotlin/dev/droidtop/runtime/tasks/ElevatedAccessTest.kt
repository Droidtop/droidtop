package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevatedAccessTest {
    private val absent = BackendState.ABSENT
    private val needs = BackendState.NEEDS_PERMISSION
    private val ready = BackendState.READY

    private class FakeBackend(
        var state: BackendState,
        private val caps: TaskPrivileges,
        private val name: String,
    ) : ElevatedBackend {
        val stopped = mutableListOf<String>()

        override fun state() = state

        override fun capabilities() = if (state == BackendState.READY) caps else TaskPrivileges.NONE

        override fun forceStop(packageName: String): ForceStopResult {
            stopped += packageName
            return ForceStopResult.Stopped
        }

        override fun exec(argv: List<String>) = ShellOutput(0, name, "")
    }

    private val full = TaskPrivileges(forceStop = true, shell = true, grantPermission = true)

    @Test
    fun `auto takes the Shizuku app when both are ready`() {
        assertEquals(ElevatedBackendId.SHIZUKU_APP, ElevatedAccess.resolve(ElevatedChoice.AUTO, ready, ready))
    }

    @Test
    fun `auto takes whichever one is ready`() {
        assertEquals(ElevatedBackendId.SHIZUKU_PLUGIN, ElevatedAccess.resolve(ElevatedChoice.AUTO, absent, ready))
        assertEquals(ElevatedBackendId.SHIZUKU_PLUGIN, ElevatedAccess.resolve(ElevatedChoice.AUTO, needs, ready))
        assertEquals(ElevatedBackendId.SHIZUKU_APP, ElevatedAccess.resolve(ElevatedChoice.AUTO, ready, absent))
    }

    @Test
    fun `auto picks nothing while neither is ready`() {
        assertNull(ElevatedAccess.resolve(ElevatedChoice.AUTO, absent, absent))
        assertNull(ElevatedAccess.resolve(ElevatedChoice.AUTO, needs, needs))
    }

    @Test
    fun `a named backend is never swapped for the other one`() {
        assertEquals(ElevatedBackendId.SHIZUKU_APP, ElevatedAccess.resolve(ElevatedChoice.SHIZUKU_APP, ready, ready))
        assertEquals(ElevatedBackendId.SHIZUKU_PLUGIN, ElevatedAccess.resolve(ElevatedChoice.SHIZUKU_PLUGIN, ready, ready))
        assertNull(ElevatedAccess.resolve(ElevatedChoice.SHIZUKU_APP, absent, ready))
        assertNull(ElevatedAccess.resolve(ElevatedChoice.SHIZUKU_PLUGIN, ready, absent))
    }

    @Test
    fun `a named backend that still needs permission stays selected`() {
        assertEquals(ElevatedBackendId.SHIZUKU_APP, ElevatedAccess.resolve(ElevatedChoice.SHIZUKU_APP, needs, ready))
    }

    @Test
    fun `off is always none`() {
        assertNull(ElevatedAccess.resolve(ElevatedChoice.OFF, ready, ready))
    }

    @Test
    fun `the row offers only what is there`() {
        assertTrue(ElevatedAccess.options(absent, absent).isEmpty())
        assertEquals(
            listOf(ElevatedChoice.AUTO, ElevatedChoice.SHIZUKU_APP, ElevatedChoice.OFF),
            ElevatedAccess.options(needs, absent),
        )
        assertEquals(
            listOf(ElevatedChoice.AUTO, ElevatedChoice.SHIZUKU_PLUGIN, ElevatedChoice.OFF),
            ElevatedAccess.options(absent, ready),
        )
        assertEquals(
            listOf(ElevatedChoice.AUTO, ElevatedChoice.SHIZUKU_APP, ElevatedChoice.SHIZUKU_PLUGIN, ElevatedChoice.OFF),
            ElevatedAccess.options(ready, ready),
        )
    }

    @Test
    fun `an unknown stored value reads as auto`() {
        assertEquals(ElevatedChoice.AUTO, ElevatedChoice.fromId(null))
        assertEquals(ElevatedChoice.AUTO, ElevatedChoice.fromId("root"))
        assertEquals(ElevatedChoice.OFF, ElevatedChoice.fromId("off"))
    }

    @Test
    fun `the shell forwards to the chosen backend and to nothing for off`() {
        val app = FakeBackend(ready, full, "app")
        val plugin = FakeBackend(ready, full, "plugin")
        var choice = ElevatedChoice.AUTO
        val shell = ElevatedShell(app, plugin, { choice })

        assertEquals("app", shell.exec(listOf("true"))?.stdout)
        choice = ElevatedChoice.SHIZUKU_PLUGIN
        assertEquals("plugin", shell.exec(listOf("true"))?.stdout)
        assertEquals(ForceStopResult.Stopped, shell.forceStop("com.example.game"))
        assertEquals(listOf("com.example.game"), plugin.stopped)
        assertTrue(app.stopped.isEmpty())

        choice = ElevatedChoice.OFF
        assertEquals(TaskPrivileges.NONE, shell.capabilities())
        assertNull(shell.exec(listOf("true")))
        assertEquals(ForceStopResult.NoProvider, shell.forceStop("com.example.game"))
    }

    @Test
    fun `privileged controls hide when no backend is usable`() {
        val app = FakeBackend(absent, full, "app")
        val plugin = FakeBackend(absent, full, "plugin")
        val shell = ElevatedShell(app, plugin, { ElevatedChoice.AUTO })

        assertEquals(TaskPrivileges.NONE, shell.capabilities())
        assertTrue(shell.options().isEmpty())

        plugin.state = ready
        assertEquals(full, shell.capabilities())
        assertEquals(ElevatedBackendId.SHIZUKU_PLUGIN, shell.active())
    }
}
