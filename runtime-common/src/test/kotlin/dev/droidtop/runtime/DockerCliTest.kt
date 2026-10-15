package dev.droidtop.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** docs/SPEC.md 3d "The docker command" (Droidtop/tracker#555 D6c): a closed set of requests, answered by droidtop. */
class DockerCliTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun answer(args: List<String>) = DockerCli.parse(args) as DockerCommand.Answer

    @Test
    fun `the subcommands it answers are read into their own requests`() {
        assertEquals(DockerCommand.Ps(all = false, quiet = false), DockerCli.parse(listOf("ps")))
        assertEquals(DockerCommand.Ps(all = true, quiet = true), DockerCli.parse(listOf("ps", "-aq")))
        assertEquals(DockerCommand.Ps(all = true, quiet = false), DockerCli.parse(listOf("ps", "--all")))
        assertEquals(DockerCommand.Images(quiet = true), DockerCli.parse(listOf("images", "-q")))
        assertEquals(DockerCommand.Logs("gitea", DockerCli.DEFAULT_TAIL), DockerCli.parse(listOf("logs", "gitea")))
        assertEquals(DockerCommand.Logs("gitea", 5), DockerCli.parse(listOf("logs", "-n", "5", "gitea")))
        assertEquals(DockerCommand.Logs("gitea", DockerCli.MAX_TAIL), DockerCli.parse(listOf("logs", "--tail=all", "gitea")))
        assertEquals(DockerCommand.Logs("gitea", DockerCli.MAX_TAIL), DockerCli.parse(listOf("logs", "--tail", "999999", "gitea")))
        assertEquals(DockerCommand.Start(listOf("a", "b")), DockerCli.parse(listOf("start", "a", "b")))
        assertEquals(DockerCommand.Stop(listOf("a")), DockerCli.parse(listOf("stop", "a")))
    }

    @Test
    fun `exec takes env, a folder and the command, and runs on pipes whatever -t says`() {
        assertEquals(
            DockerCommand.Exec("arch", listOf("ls", "-la", "/"), mapOf("A" to "1=2", "B" to ""), "/srv"),
            DockerCli.parse(listOf("exec", "-it", "-e", "A=1=2", "--env", "B=", "-w", "/srv", "arch", "ls", "-la", "/")),
        )
        // Flags after the container belong to the command.
        assertEquals(DockerCommand.Exec("arch", listOf("ls", "-e"), emptyMap(), null), DockerCli.parse(listOf("exec", "arch", "ls", "-e")))
        assertEquals(1, answer(listOf("exec", "arch")).code)
        assertEquals(1, answer(listOf("exec", "-e", "1BAD=x", "arch", "ls")).code)
        assertEquals(1, answer(listOf("exec", "--privileged", "arch", "ls")).code)
    }

    @Test
    fun `anything else is an answer, never a request`() {
        assertEquals(0, answer(emptyList()).code)
        assertTrue(answer(listOf("--help")).text.contains("exec"))
        assertTrue(answer(listOf("run", "nginx")).text.contains("not built in droidtop yet"))
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
            DockerCli.PsRow("gitea", "docker.io/gitea/gitea:1.22", "Up", up = true, ports = listOf(ExposedPort(22), ExposedPort(3000))),
            DockerCli.PsRow("arch", "docker.io/library/archlinux:latest", "Created", up = false, ports = emptyList()),
        )
        assertEquals(
            "NAME    IMAGE                        STATUS   PORTS\n" +
                "gitea   docker.io/gitea/gitea:1.22   Up       22/tcp, 3000/tcp\n",
            DockerCli.formatPs(rows, DockerCommand.Ps(all = false, quiet = false)),
        )
        assertEquals("gitea\narch\n", DockerCli.formatPs(rows, DockerCommand.Ps(all = true, quiet = true)))
        val images = listOf(
            OciImageStore.Entry("sha256:0123456789abcdef0123", "docker.io/library/nginx:1.27-alpine", 21_400_000),
            OciImageStore.Entry("sha256:fedcba9876543210fedc", "ghcr.io/home-assistant/home-assistant", 1_520_000_000),
        )
        assertEquals(
            "REPOSITORY                              TAG           IMAGE ID       SIZE\n" +
                "nginx                                   1.27-alpine   0123456789ab   21MB\n" +
                "ghcr.io/home-assistant/home-assistant   latest        fedcba987654   1.5GB\n",
            DockerCli.formatImages(images, DockerCommand.Images(quiet = false)),
        )
        assertEquals("localhost:5000/tools", DockerCli.repository("localhost:5000/tools:1"))
        assertEquals("<none>", DockerCli.tag("docker.io/library/alpine@sha256:abc"))
    }

    @Test
    fun `docker is droidtop's own command on the PATH, and no export can take its name`() {
        assertFalse(ContainerEnter.validName(DockerCli.NAME))
        assertTrue(DockerCli.TOKEN.matches(Regex("[0-9a-f]{16}")))
        assertTrue(DockerCli.wrapperScript().contains("exec sh ${ContainerEnter.HELPER} ${DockerCli.TOKEN} \"\$@\""))
        val files = tmp.newFolder("files")
        ContainerEnter.writeWrappers(files, emptyList())
        val docker = File(ContainerEnter.hostCommandsDir(files), DockerCli.NAME)
        assertEquals(DockerCli.wrapperScript(), docker.readText())
        assertTrue(docker.canExecute())
    }
}
