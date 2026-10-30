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

    private class SpawnBackend(private val process: Process?) : ElevatedBackend {
        var asked = 0

        override fun state() = BackendState.READY

        override fun forceStop(packageName: String): ForceStopResult = ForceStopResult.NoProvider

        override fun exec(argv: List<String>): ShellOutput? = null

        override fun spawn(argv: List<String>): Process? {
            asked++
            return process
        }
    }

    @Test
    fun `the rooted stack's process comes from a root provider plugin first, then the Shizuku app`() {
        val fromPlugin = ProcessBuilder("true").start()
        val fromApp = ProcessBuilder("true").start()

        val pluginRoot = SpawnBackend(fromPlugin)
        val app = SpawnBackend(fromApp)
        assertTrue(ElevatedShell(app, pluginRoot, { ElevatedChoice.AUTO }).spawn(listOf("id")) === fromPlugin)
        assertEquals(0, app.asked)

        // No provider at root level: Auto falls back to the app's binder.
        assertTrue(ElevatedShell(app, SpawnBackend(null), { ElevatedChoice.AUTO }).spawn(listOf("id")) === fromApp)

        // A named backend is used alone, and Off is none.
        assertNull(ElevatedShell(app, SpawnBackend(null), { ElevatedChoice.SHIZUKU_PLUGIN }).spawn(listOf("id")))
        assertTrue(ElevatedShell(app, pluginRoot, { ElevatedChoice.SHIZUKU_APP }).spawn(listOf("id")) === fromApp)
        assertNull(ElevatedShell(app, pluginRoot, { ElevatedChoice.OFF }).spawn(listOf("id")))
    }

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
    fun `no elevated access says why and where to change it`() {
        assertNull(ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, ready, absent))
        assertNull(ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, needs, ready))
        // Shizuku running over ADB that has not allowed droidtop: the console case behind "Not confirmed".
        val notAllowed = ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, needs, absent)!!
        assertTrue(notAllowed.contains("not allowed in Shizuku"))
        assertTrue(notAllowed.contains("Allow droidtop in Shizuku"))
        assertEquals(notAllowed, ElevatedAccess.unavailableReason(ElevatedChoice.SHIZUKU_APP, needs, ready))
        // Nothing installed: say what would let droidtop close apps.
        val nothing = ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, absent, absent)!!
        assertTrue(nothing.contains("Install Shizuku"))
        assertTrue(nothing.contains("add the Shizuku plugin"))
        // The Shizuku app is on the device but not running: start it, do not ask for it to be added.
        assertTrue(ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, absent, absent, appInstalled = true)!!.startsWith("Shizuku is installed but not running"))
        // The plugin is installed and waiting for approval (console, build 1773): approve it, never "add the plugin".
        val waiting = ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, absent, needs, appInstalled = true)!!
        assertTrue(waiting.contains("plugin is installed but not approved"))
        assertTrue(!waiting.contains("add the Shizuku plugin"))
        assertEquals(waiting, ElevatedAccess.unavailableReason(ElevatedChoice.SHIZUKU_PLUGIN, ready, needs))
        // The app being usable-after-allowing wins the wording when both need the person.
        assertTrue(ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, needs, needs)!!.contains("not allowed in Shizuku"))
        assertTrue(ElevatedAccess.unavailableReason(ElevatedChoice.SHIZUKU_PLUGIN, ready, absent)!!.contains("Shizuku plugin"))
        assertTrue(ElevatedAccess.unavailableReason(ElevatedChoice.OFF, ready, ready)!!.startsWith("Elevated access is Off"))
    }

    @Test
    fun `the shell waits for the Shizuku app's binder only where the app can serve`() {
        var connected = 0
        val app = object : ElevatedBackend {
            override fun state() = BackendState.ABSENT
            override fun connect(timeoutMs: Long) {
                connected++
            }
            override fun forceStop(packageName: String) = ForceStopResult.NoProvider
            override fun exec(argv: List<String>): ShellOutput? = null
        }
        val plugin = FakeBackend(absent, full, "plugin")
        var choice = ElevatedChoice.AUTO
        val shell = ElevatedShell(app, plugin, { choice })
        shell.connect(1)
        choice = ElevatedChoice.SHIZUKU_APP
        shell.connect(1)
        choice = ElevatedChoice.SHIZUKU_PLUGIN
        shell.connect(1)
        choice = ElevatedChoice.OFF
        shell.connect(1)
        assertEquals(2, connected)
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

    @Test
    fun `wireless debugging comes last in Auto, and a named pick keeps it while paired`() {
        assertEquals(ElevatedBackendId.WIRELESS_DEBUGGING, ElevatedAccess.resolve(ElevatedChoice.AUTO, absent, absent, ready))
        assertEquals(ElevatedBackendId.SHIZUKU_APP, ElevatedAccess.resolve(ElevatedChoice.AUTO, ready, absent, ready))
        assertNull(ElevatedAccess.resolve(ElevatedChoice.AUTO, absent, absent, needs))
        assertEquals(ElevatedBackendId.WIRELESS_DEBUGGING, ElevatedAccess.resolve(ElevatedChoice.WIRELESS_DEBUGGING, ready, ready, needs))
        assertNull(ElevatedAccess.resolve(ElevatedChoice.WIRELESS_DEBUGGING, ready, ready, absent))
    }

    @Test
    fun `paired with its switch off says to turn it on, and it is offered once paired`() {
        assertEquals(ElevatedAccess.WIRELESS_OFF, ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, absent, absent, wireless = needs))
        assertNull(ElevatedAccess.unavailableReason(ElevatedChoice.AUTO, absent, absent, wireless = ready))
        assertTrue(ElevatedChoice.WIRELESS_DEBUGGING in ElevatedAccess.options(absent, absent, needs))
        assertTrue(ElevatedAccess.options(absent, absent, absent).isEmpty())
    }

    @Test
    fun `the shell runs commands through wireless debugging when it is the one ready`() {
        val wireless = FakeBackend(ready, TaskPrivileges(forceStop = true, shell = true), "wireless")
        val shell = ElevatedShell(FakeBackend(absent, TaskPrivileges.NONE, "app"), FakeBackend(absent, TaskPrivileges.NONE, "plugin"), { ElevatedChoice.AUTO }, wireless)
        assertEquals(ElevatedBackendId.WIRELESS_DEBUGGING, shell.active())
        assertEquals("wireless", shell.exec(listOf("id"))?.stdout)
    }
}
