package dev.droidtop.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContainerTerminalTest {

    /**
     * Records what was exec'd, and answers with a canned result. Only
     * [exec] is reachable from [ContainerTerminal], so everything else
     * fails loudly rather than quietly returning a plausible value.
     */
    private class RecordingRuntime(private val result: ContainerExecResult) : ContainerRuntime {
        var lastCommand: List<String>? = null
        var lastEnv: Map<String, String>? = null
        var lastContainer: Container? = null

        override val backend: ContainerBackend = ContainerBackend.DROIDSPACES

        override suspend fun exec(
            container: Container,
            command: List<String>,
            env: Map<String, String>,
        ): ContainerExecResult {
            lastContainer = container
            lastCommand = command
            lastEnv = env
            return result
        }

        override suspend fun listContainers(): List<ContainerInfo> = error("not used")
        override suspend fun createPrimary(image: RootfsImage, provisioning: PrimaryProvisioning): Container = error("not used")
        override suspend fun createSibling(image: RootfsImage): Container = error("not used")
        override suspend fun start(container: Container, provisioning: PrimaryProvisioning?, onProgress: (String) -> Unit) = error("not used")
        override suspend fun checkSystemRequirements(): ContainerExecResult = error("not used")
        override suspend fun stop(container: Container) = error("not used")
        override suspend fun destroy(container: Container) = error("not used")
        override fun primaryWaylandSocketPath(): String = error("not used")
        override fun hostSocketDir(): File = error("not used")
        override val deviceSharingUnavailableReason: String? = null
        override suspend fun sharedDevices(container: Container): List<String> = error("not used")
        override suspend fun setSharedDevices(container: Container, devicePaths: List<String>) = error("not used")
        override fun hostStorageToContainerPath(hostPath: File): String = error("not used")
    }

    private val primary = Container(
        id = "droidtop-primary",
        role = ContainerRole.PRIMARY,
        backend = ContainerBackend.DROIDSPACES,
        rootfsPath = "/data/rootfs/droidtop-primary",
    )

    private fun ok() = ContainerExecResult(exitCode = 0, stdout = "", stderr = "")

    @Test
    fun `opens a terminal by exec-ing it inside the given container`() = runBlocking {
        val runtime = RecordingRuntime(ok())
        ContainerTerminal.open(runtime, primary)

        assertEquals(primary, runtime.lastContainer)
        assertEquals(listOf(ContainerTerminal.PACKAGE), runtime.lastCommand)
    }

    /**
     * WAYLAND_DISPLAY/XDG_RUNTIME_DIR are injected once at container-config
     * time (DroidSpacesRuntime writes them into the container's env file),
     * so the terminal must NOT also set them here — two mechanisms pointing
     * at the same socket is how they drift apart.
     */
    @Test
    fun `passes no per-exec environment of its own`() = runBlocking {
        val runtime = RecordingRuntime(ok())
        ContainerTerminal.open(runtime, primary)
        assertEquals(emptyMap<String, String>(), runtime.lastEnv)
    }

    /**
     * The package installed by CompositorProvisioning and the binary run
     * here are the same constant, so a rename cannot leave a primary
     * container that provisions one terminal and launches another.
     */
    @Test
    fun `the provisioned package is the binary that gets launched`() {
        for (command in listOf(
            CompositorProvisioning.plan("debian", "sway")?.installCommand,
            CompositorProvisioning.plan("alpine", "sway")?.installCommand,
            CompositorProvisioning.plan("alpine", "labwc")?.installCommand,
        )) {
            assertNotNull(command)
            assertTrue(
                "compositor provisioning must install ${ContainerTerminal.PACKAGE}: " + command,
                command!!.split(" ").contains(ContainerTerminal.PACKAGE),
            )
        }
        assertEquals(listOf(ContainerTerminal.PACKAGE), ContainerTerminal.LAUNCH_COMMAND)
    }

    @Test
    fun `a clean exit is not a failure`() {
        assertNull(ContainerTerminal.failureMessage(ok()))
    }

    @Test
    fun `a missing terminal says so, and says where it should have come from`() {
        val message = ContainerTerminal.failureMessage(
            ContainerExecResult(exitCode = 127, stdout = "", stderr = "sh: foot: not found"),
        )
        assertNotNull(message)
        assertTrue(message!!.contains(ContainerTerminal.PACKAGE))
        assertTrue(message.contains("provisioned"))
    }

    @Test
    fun `any other failure carries the exit code and whatever the shell said`() {
        val message = ContainerTerminal.failureMessage(
            ContainerExecResult(exitCode = 1, stdout = "", stderr = "failed to connect to wayland display"),
        )
        assertNotNull(message)
        assertTrue(message!!.contains("1"))
        assertTrue(message.contains("wayland"))
    }

    @Test
    fun `a silent failure still reports its exit code`() {
        val message = ContainerTerminal.failureMessage(
            ContainerExecResult(exitCode = 3, stdout = "", stderr = ""),
        )
        assertEquals("The terminal exited with code 3.", message)
    }

    @Test
    fun `a container gets a terminal through whichever package manager it has`() = runBlocking {
        val runtime = RecordingRuntime(ok())
        assertNull(ContainerTerminal.ensureInstalled(runtime, primary))
        val script = runtime.lastCommand!!.last()
        assertTrue(script.startsWith("command -v ${ContainerTerminal.PACKAGE} >/dev/null 2>&1 && exit 0;"))
        for (manager in listOf("apk", "apt-get", "dnf", "zypper", "pacman", "xbps-install")) {
            assertTrue("$manager missing: $script", script.contains("command -v $manager >/dev/null 2>&1; then"))
        }
        assertTrue(script.endsWith("exit 2; fi"))
    }

    @Test
    fun `a failed install says which container and what the package manager said`() = runBlocking {
        val runtime = RecordingRuntime(ContainerExecResult(exitCode = 2, stdout = "", stderr = "No package manager droidtop knows"))
        val message = ContainerTerminal.ensureInstalled(runtime, primary)
        assertNotNull(message)
        assertTrue(message!!.contains("droidtop-primary"))
        assertTrue(message.contains("No package manager"))
    }
}
