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
            user: ContainerUser.Spec?,
        ): ContainerExecResult {
            lastContainer = container
            lastCommand = command
            lastEnv = env
            return result
        }

        override suspend fun listContainers(): List<ContainerInfo> = error("not used")
        override suspend fun createPrimary(image: RootfsImage, provisioning: PrimaryProvisioning): Container = error("not used")
        override suspend fun createSibling(image: RootfsImage, name: String?): Container = error("not used")
        override suspend fun rename(container: Container, name: String) = error("not used")
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
        override suspend fun recreateFromImage(container: Container): Container = error("not used")
        override suspend fun sockets(container: Container): ContainerSockets = error("not used")
        override suspend fun setSockets(container: Container, sockets: ContainerSockets) = error("not used")
        override val audioSharingUnavailableReason: String? = null
        override suspend fun mounts(container: Container): EnvironmentMounts = error("not used")
        override suspend fun setMounts(container: Container, mounts: EnvironmentMounts) = error("not used")
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

    @Test
    fun `a terminal window is titled with its container's name`() = runBlocking {
        val runtime = RecordingRuntime(ok())
        ContainerTerminal.open(runtime, primary, "Debian")
        assertEquals(listOf(ContainerTerminal.PACKAGE, "--title=Debian"), runtime.lastCommand)
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

    // ---- droidtop's own terminal (Droidtop/tracker#909) ----

    /** Runs [argv] with [input] on stdin and [path] as PATH, and returns its exit code, stdout and stderr. */
    private fun run(argv: List<String>, input: String, path: String? = null): Triple<Int, String, String> {
        val builder = ProcessBuilder(argv)
        path?.let { builder.environment()["PATH"] = it }
        val process = builder.start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return Triple(process.waitFor(), out, err)
    }

    private fun assumeShell() = org.junit.Assume.assumeTrue(File("/bin/sh").canExecute() && File("/etc/passwd").isFile)

    @Test
    fun `the terminal adds xterm-256color and truecolor to the shell's environment`() {
        assertEquals("xterm-256color", ContainerTerminal.SHELL_ENVIRONMENT["TERM"])
        assertEquals("truecolor", ContainerTerminal.SHELL_ENVIRONMENT["COLORTERM"])
    }

    @Test
    fun `the login shell is a constant script, not a command line built from outside`() {
        val command = ContainerTerminal.LOGIN_SHELL_COMMAND
        assertEquals(listOf("/bin/sh", "-c"), command.take(2))
        assertEquals(3, command.size)
        assertTrue(command[2].endsWith("exec \"\$s\" -l"))
        assertTrue(command[2].contains("< /etc/passwd"))
    }

    @Test
    fun `the login shell is the user's own from passwd, started in their home`() {
        assumeShell()
        val (code, out, _) = run(ContainerTerminal.LOGIN_SHELL_COMMAND, "echo \"[\$SHELL|\$HOME|\$(pwd)]\"\n")
        assertEquals(0, code)
        val line = Regex("\\[(.*)\\|(.*)\\|(.*)]").find(out)
        assertNotNull("no answer from the shell: $out", line)
        val (shell, home, cwd) = line!!.destructured
        assertTrue("not executable: $shell", File(shell).canExecute())
        if (File(home).isDirectory) assertEquals(File(home).canonicalPath, File(cwd).canonicalPath)
    }

    @Test
    fun `a terminal made in the container is sized first and runs the login shell`() {
        val argv = ContainerTerminal.onContainerTerminal(rows = 40, columns = 120)
        assertEquals(listOf("/bin/sh", "-c"), argv.take(2))
        assertTrue(argv[2].contains("exec script -qfc \"\$1\" /dev/null"))
        assertEquals("sh", argv[3])
        assertTrue(argv[4].startsWith("stty rows 40 cols 120 2>/dev/null; "))
        assertTrue(argv[4].endsWith(ContainerTerminal.LOGIN_SHELL_COMMAND[2]))
        assertEquals(5, argv.size)
    }

    @Test
    fun `scripts the terminal runs parse as POSIX shell`() {
        assumeShell()
        val scripts = listOf(ContainerTerminal.LOGIN_SHELL_COMMAND[2]) + ContainerTerminal.onContainerTerminal(24, 80).let { listOf(it[2], it[4]) }
        scripts.forEach { script ->
            val (code, _, err) = run(listOf("/bin/sh", "-n", "-c", script), "")
            assertEquals("does not parse: $script\n$err", 0, code)
        }
    }

    @Test
    fun `a container without script still gets the shell, and is told it has no terminal`() {
        assumeShell()
        // No PATH: neither script nor stty is found; the login shell is still the passwd one (or /bin/sh).
        val (code, out, err) = run(ContainerTerminal.onContainerTerminal(24, 80), "echo reached\n", path = "/nonexistent")
        assertEquals(0, code)
        assertTrue("no shell ran: $out / $err", out.contains("reached"))
        assertTrue(err.contains("no script program"))
    }
}
