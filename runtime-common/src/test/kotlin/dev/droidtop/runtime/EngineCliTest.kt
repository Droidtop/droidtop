package dev.droidtop.runtime

import dev.droidtop.runtime.ContainerProgram.RestartPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/SPEC.md 3d "The docker command" and "The podman command" (Droidtop/tracker#555 D6c, D11): a closed set of requests. */
class EngineCliTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun answer(args: List<String>) = EngineCli.DOCKER.parse(args) as EngineRequest.Answer

    @Test
    fun `the subcommands it answers are read into their own requests`() {
        assertEquals(EngineRequest.Ps(all = false, quiet = false), EngineCli.DOCKER.parse(listOf("ps")))
        assertEquals(EngineRequest.Ps(all = true, quiet = true), EngineCli.DOCKER.parse(listOf("ps", "-aq")))
        assertEquals(EngineRequest.Ps(all = true, quiet = false), EngineCli.DOCKER.parse(listOf("ps", "--all")))
        assertEquals(EngineRequest.Images(quiet = true), EngineCli.DOCKER.parse(listOf("images", "-q")))
        assertEquals(EngineRequest.Logs("gitea", EngineCli.DEFAULT_TAIL), EngineCli.DOCKER.parse(listOf("logs", "gitea")))
        assertEquals(EngineRequest.Logs("gitea", 5), EngineCli.DOCKER.parse(listOf("logs", "-n", "5", "gitea")))
        assertEquals(EngineRequest.Logs("gitea", EngineCli.MAX_TAIL), EngineCli.DOCKER.parse(listOf("logs", "--tail=all", "gitea")))
        assertEquals(EngineRequest.Logs("gitea", EngineCli.MAX_TAIL), EngineCli.DOCKER.parse(listOf("logs", "--tail", "999999", "gitea")))
        assertEquals(EngineRequest.Start(listOf("a", "b")), EngineCli.DOCKER.parse(listOf("start", "a", "b")))
        assertEquals(EngineRequest.Stop(listOf("a")), EngineCli.DOCKER.parse(listOf("stop", "a")))
    }

    @Test
    fun `exec takes env, a folder and the command, and runs on pipes whatever -t says`() {
        assertEquals(
            EngineRequest.Exec("arch", listOf("ls", "-la", "/"), mapOf("A" to "1=2", "B" to ""), "/srv"),
            EngineCli.DOCKER.parse(listOf("exec", "-it", "-e", "A=1=2", "--env", "B=", "-w", "/srv", "arch", "ls", "-la", "/")),
        )
        // Flags after the container belong to the command.
        assertEquals(EngineRequest.Exec("arch", listOf("ls", "-e"), emptyMap(), null), EngineCli.DOCKER.parse(listOf("exec", "arch", "ls", "-e")))
        assertEquals(1, answer(listOf("exec", "arch")).code)
        assertEquals(1, answer(listOf("exec", "-e", "1BAD=x", "arch", "ls")).code)
        assertEquals(1, answer(listOf("exec", "--privileged", "arch", "ls")).code)
    }

    @Test
    fun `anything else is an answer, never a request`() {
        assertEquals(0, answer(emptyList()).code)
        assertTrue(answer(listOf("--help")).text.contains("exec"))
        assertTrue(answer(listOf("compose", "up")).text.contains("not built in droidtop yet"))
        assertTrue(answer(listOf("build", ".")).text.contains("do not have this"))
        assertTrue(answer(listOf("frobnicate")).text.contains("not a docker command"))
        assertEquals(1, answer(listOf("ps", "--format", "{{.ID}}")).code)
        assertEquals(1, answer(listOf("logs", "-f", "gitea")).code)
        assertEquals(1, answer(listOf("logs", "-n", "x", "gitea")).code)
        assertEquals(1, answer(listOf("logs")).code)
        assertEquals(1, answer(listOf("stop")).code)
        assertEquals(1, answer(listOf("stop", "-t", "3", "a")).code)
    }

    @Test
    fun `ps and images print Docker's columns`() {
        val rows = listOf(
            EngineCli.PsRow("gitea", "docker.io/gitea/gitea:1.22", "Up", up = true, ports = listOf(ExposedPort(22), ExposedPort(3000))),
            EngineCli.PsRow("arch", "docker.io/library/archlinux:latest", "Created", up = false, ports = emptyList()),
        )
        assertEquals(
            "NAME    IMAGE                        STATUS   PORTS\n" +
                "gitea   docker.io/gitea/gitea:1.22   Up       22/tcp, 3000/tcp\n",
            EngineCli.DOCKER.formatPs(rows, EngineRequest.Ps(all = false, quiet = false)),
        )
        assertEquals("gitea\narch\n", EngineCli.DOCKER.formatPs(rows, EngineRequest.Ps(all = true, quiet = true)))
        val images = listOf(
            OciImageStore.Entry("sha256:0123456789abcdef0123", "docker.io/library/nginx:1.27-alpine", 21_400_000),
            OciImageStore.Entry("sha256:fedcba9876543210fedc", "ghcr.io/home-assistant/home-assistant", 1_520_000_000),
        )
        assertEquals(
            "REPOSITORY                              TAG           IMAGE ID       SIZE\n" +
                "nginx                                   1.27-alpine   0123456789ab   21MB\n" +
                "ghcr.io/home-assistant/home-assistant   latest        fedcba987654   1.5GB\n",
            EngineCli.DOCKER.formatImages(images, EngineRequest.Images(quiet = false)),
        )
        assertEquals("localhost:5000/tools", EngineCli.DOCKER.repository("localhost:5000/tools:1"))
        assertEquals("<none>", EngineCli.DOCKER.tag("docker.io/library/alpine@sha256:abc"))
    }

    @Test
    fun `docker is droidtop's own command on the PATH, and no export can take its name`() {
        assertFalse(ContainerEnter.validName(EngineCli.DOCKER.name))
        assertTrue(EngineCli.DOCKER.token.matches(Regex("[0-9a-f]{16}")))
        assertTrue(EngineCli.DOCKER.wrapperScript().contains("exec sh ${ContainerEnter.HELPER} ${EngineCli.DOCKER.token} \"\$@\""))
        val files = tmp.newFolder("files")
        ContainerEnter.writeWrappers(files, emptyList())
        val docker = File(ContainerEnter.hostCommandsDir(files), EngineCli.DOCKER.name)
        assertEquals(EngineCli.DOCKER.wrapperScript(), docker.readText())
        assertTrue(docker.canExecute())
    }

    @Test
    fun `pull, run and rm take only what droidtop does`() {
        assertEquals(EngineRequest.Pull("docker.io/library/nginx:1.27-alpine"), EngineCli.DOCKER.parse(listOf("pull", "docker.io/library/nginx:1.27-alpine")))
        assertEquals(1, answer(listOf("pull", "nginx", "redis")).code)
        assertEquals(1, answer(listOf("pull", "-a", "nginx")).code)
        assertEquals(1, answer(listOf("pull", "nginx;id")).code)
        assertEquals(1, answer(listOf("pull", "../../etc")).code)

        assertEquals(EngineRequest.Run("gitea/gitea:1.22", "git"), EngineCli.DOCKER.parse(listOf("run", "-d", "--name", "git", "gitea/gitea:1.22")))
        assertEquals(EngineRequest.Run("redis", "cache"), EngineCli.DOCKER.parse(listOf("run", "-it", "--name=cache", "redis")))
        assertEquals(EngineRequest.Run("redis", null), EngineCli.DOCKER.parse(listOf("run", "redis")))
        // Known flags droidtop has not built say so; a command after the image is refused, not ignored.
        assertTrue(answer(listOf("run", "-p", "8080:80", "nginx")).text.contains("-p is not built in droidtop yet"))
        assertTrue(answer(listOf("run", "--entrypoint=sh", "nginx")).text.contains("not built in droidtop yet"))
        assertEquals(1, answer(listOf("run", "--privileged", "nginx")).code)
        assertEquals(1, answer(listOf("run")).code)

        assertEquals(EngineRequest.Remove(listOf("a", "b"), force = false), EngineCli.DOCKER.parse(listOf("rm", "a", "b")))
        assertEquals(EngineRequest.Remove(listOf("a"), force = true), EngineCli.DOCKER.parse(listOf("rm", "-f", "a")))
        assertEquals(1, answer(listOf("rm", "-v", "a")).code)
        assertEquals(1, answer(listOf("rm")).code)
    }

    @Test
    fun `run takes a command, environment and folders the person mapped, never a host path`() {
        val mounts = ContainerLayout.EXTRA_MOUNTS_DIR
        assertEquals(
            EngineRequest.Run("alpine", "box", listOf("sh", "-c", "echo hi"), listOf("A=1", "B="), listOf("Music", "Saves")),
            EngineCli.DOCKER.parse(listOf("run", "--name", "box", "-e", "A=1", "--env=B=", "-v", "Music", "--volume=Saves:$mounts/Saves:ro", "alpine", "sh", "-c", "echo hi")),
        )
        // Flags after the image belong to its command.
        assertEquals(listOf("ls", "-e"), (EngineCli.DOCKER.parse(listOf("run", "alpine", "ls", "-e")) as EngineRequest.Run).args)
        assertEquals(null, (EngineCli.DOCKER.parse(listOf("run", "alpine")) as EngineRequest.Run).args)
        assertTrue(answer(listOf("run", "-v", "/sdcard/Music:/music", "alpine")).text.contains("folder you mapped in Containers"))
        assertTrue(answer(listOf("run", "-v", "Music:/music", "alpine")).text.contains("folder you mapped in Containers"))
        assertTrue(answer(listOf("run", "-v", "../x", "alpine")).text.contains("folder you mapped in Containers"))
        assertEquals(1, answer(listOf("run", "-e", "1A=x", "alpine")).code)
    }

    @Test
    fun `run options that need a kernel feature say which, and info is answered by droidtop`() {
        val none = EngineFeatures("b", EngineFeature.entries.associateWith { FeatureState(kernel = false) })
        val memory = EngineCli.DOCKER.parse(listOf("run", "--memory", "512m", "alpine"), none) as EngineRequest.Answer
        assertEquals(1, memory.code)
        assertTrue(memory.text.contains("--memory needs cgroups, which this device's kernel does not have"))
        assertTrue((EngineCli.DOCKER.parse(listOf("run", "--cpus=2", "alpine"), none) as EngineRequest.Answer).text.contains("cgroups"))
        // What the containers already are is taken as given.
        assertEquals(EngineRequest.Run("alpine", null), EngineCli.DOCKER.parse(listOf("run", "--network", "host", "alpine"), none))
        assertEquals(EngineRequest.Run("alpine", null), EngineCli.DOCKER.parse(listOf("run", "--pid=host", "alpine"), none))
        assertEquals(EngineRequest.Info, EngineCli.DOCKER.parse(listOf("info")))
        assertEquals(1, answer(listOf("info", "-f", "x")).code)
        val info = EngineCli.DOCKER.formatInfo(none, null)
        assertTrue(info.contains("the desktop is not running"))
        assertTrue(info.contains("overlayfs: not in this device's kernel"))
    }

    @Test
    fun `what run asked for replaces the command, never the entrypoint, and adds to the environment`() {
        val image = ImageRunConfig(env = listOf("PATH=/bin", "A=0"), entrypoint = listOf("/entry.sh"), cmd = listOf("serve"))
        val overrides = ContainerProgram.RunOverrides(args = listOf("sh"), env = listOf("A=1"))
        val run = overrides.applyTo(image)
        assertEquals(listOf("/entry.sh", "sh"), run.program())
        assertEquals("1", run.environment()["A"])
        assertEquals(listOf("/entry.sh", "serve"), ContainerProgram.RunOverrides(env = listOf("B=2")).applyTo(image).program())
        assertEquals(overrides, ContainerProgram.RunOverrides.decode(overrides.encode()))
        assertEquals(ContainerProgram.RunOverrides(null, emptyList()), ContainerProgram.RunOverrides.decode(ContainerProgram.RunOverrides().encode()))
        assertEquals(null, ContainerProgram.RunOverrides.decode("not json"))
    }

    @Test
    fun `run takes a restart policy on either front, no by default`() {
        assertEquals(RestartPolicy.No, (EngineCli.DOCKER.parse(listOf("run", "nginx")) as EngineRequest.Run).restart)
        assertEquals(RestartPolicy.UnlessStopped, (EngineCli.DOCKER.parse(listOf("run", "--restart", "unless-stopped", "nginx")) as EngineRequest.Run).restart)
        assertEquals(RestartPolicy.OnFailure(3), (EngineCli.PODMAN.parse(listOf("run", "--restart=on-failure:3", "nginx")) as EngineRequest.Run).restart)
        assertTrue(answer(listOf("run", "--restart", "sometimes", "nginx")).text.contains("--restart takes no, on-failure[:N], always or unless-stopped"))
        assertEquals(1, answer(listOf("run", "--restart")).code)
    }

    @Test
    fun `podman is a second front over the same requests, with its own name, token and wrapper`() {
        val podman = EngineCli.PODMAN
        assertEquals("podman", podman.name)
        assertTrue(podman.token != EngineCli.DOCKER.token)
        assertEquals(podman, EngineCli.forToken(podman.token))
        assertEquals(EngineCli.DOCKER, EngineCli.forToken(EngineCli.DOCKER.token))
        assertNull(EngineCli.forToken("0123456789abcdef"))
        // The same syntax reads into the same request on either front.
        val args = listOf("run", "-d", "--name", "web", "-e", "A=1", "nginx")
        assertEquals(EngineCli.DOCKER.parse(args), podman.parse(args))
        assertEquals(EngineRequest.Ps(all = true, quiet = false), podman.parse(listOf("ps", "-a")))
        // Its words are its own.
        assertTrue((podman.parse(listOf("frobnicate")) as EngineRequest.Answer).text.contains("'frobnicate' is not a podman command"))
        assertTrue((podman.parse(listOf("pod", "create")) as EngineRequest.Answer).text.contains("podman pod: not built in droidtop yet"))
        assertTrue((EngineCli.DOCKER.parse(listOf("pod")) as EngineRequest.Answer).text.contains("not a docker command"))
        assertTrue((podman.parse(listOf("run", "--memory", "1g", "alpine")) as EngineRequest.Answer).text.startsWith("podman run: --memory"))
        assertTrue(podman.usage.startsWith("Usage: podman COMMAND"))
        assertTrue(podman.wrapperScript().contains("exec sh ${ContainerEnter.HELPER} ${podman.token} \"\$@\""))
        // Neither front's name can be taken by an exported command, and both wrappers are written.
        assertFalse(ContainerEnter.validName("podman"))
        val files = tmp.newFolder("both")
        ContainerEnter.writeWrappers(files, emptyList())
        assertEquals(podman.wrapperScript(), File(ContainerEnter.hostCommandsDir(files), "podman").readText())
    }
}
