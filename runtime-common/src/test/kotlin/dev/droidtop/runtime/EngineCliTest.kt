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
    fun `ps and images print each tool's own columns`() {
        val now = 1_000_000_000L
        val rows = listOf(
            EngineCli.PsRow(
                "droidtop-sibling-1a2b", "gitea", "docker.io/gitea/gitea:1.22", "Up", up = true,
                ports = listOf(ExposedPort(22), ExposedPort(3000)),
                command = listOf("/usr/bin/entrypoint", "/bin/s6-svscan", "/etc/s6"),
                createdMs = now - 300_000,
            ),
            EngineCli.PsRow("droidtop-sibling-9f00", "arch", "docker.io/library/archlinux:latest", "Created", up = false, ports = emptyList()),
        )
        val running = EngineRequest.Ps(all = false, quiet = false)
        assertEquals(
            "CONTAINER ID            IMAGE              COMMAND                  CREATED         STATUS   PORTS              NAMES\n" +
                "droidtop-sibling-1a2b   gitea/gitea:1.22   \"/usr/bin/entrypoint\u2026\"   5 minutes ago   Up       22/tcp, 3000/tcp   gitea\n",
            EngineCli.DOCKER.formatPs(rows, running, now),
        )
        assertEquals(
            "CONTAINER ID            IMAGE                        COMMAND                CREATED         STATUS   PORTS              NAMES\n" +
                "droidtop-sibling-1a2b   docker.io/gitea/gitea:1.22   /usr/bin/entrypoi...   5 minutes ago   Up       22/tcp, 3000/tcp   gitea\n",
            EngineCli.PODMAN.formatPs(rows, running, now),
        )
        // -q prints ids, as both tools do, so `docker rm $(docker ps -aq)` names what it means.
        assertEquals("droidtop-sibling-1a2b\ndroidtop-sibling-9f00\n", EngineCli.DOCKER.formatPs(rows, EngineRequest.Ps(all = true, quiet = true), now))
        // A container made before droidtop kept the time has no CREATED.
        assertTrue(EngineCli.DOCKER.formatPs(rows, EngineRequest.Ps(all = true, quiet = false), now).lines()[2].contains("\"\"   "))
        assertEquals("About a minute ago", EngineCli.DOCKER.ago(90_000))
        assertEquals("3 days ago", EngineCli.DOCKER.ago(3 * 86_400_000L))

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
        assertEquals(
            "REPOSITORY                              TAG           IMAGE ID       SIZE\n" +
                "docker.io/library/nginx                 1.27-alpine   0123456789ab   21.4 MB\n" +
                "ghcr.io/home-assistant/home-assistant   latest        fedcba987654   1.5 GB\n",
            EngineCli.PODMAN.formatImages(images, EngineRequest.Images(quiet = false)),
        )
        assertEquals("docker.io/library/alpine", EngineCli.PODMAN.fullRepository("alpine:3"))
        assertEquals("docker.io/library/nginx:1.27-alpine", EngineCli.PODMAN.fullImage("nginx:1.27-alpine"))
        assertEquals("docker.io/library/alpine:latest", EngineCli.PODMAN.fullImage("alpine"))
        assertEquals("ghcr.io/x/y@sha256:abc", EngineCli.PODMAN.fullImage("ghcr.io/x/y@sha256:abc"))
        assertEquals("docker.io/gitea/gitea", EngineCli.PODMAN.fullRepository("gitea/gitea"))
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
        val labelled = ContainerProgram.RunOverrides(labels = mapOf("a" to "1"))
        assertEquals(labelled, ContainerProgram.RunOverrides.decode(labelled.encode()))
        assertEquals(
            mapOf("com.docker.compose.project" to "demo", "tier" to ""),
            (EngineCli.DOCKER.parse(listOf("run", "-l", "com.docker.compose.project=demo", "--label=tier", "alpine")) as EngineRequest.Run).labels,
        )
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
        assertTrue((podman.parse(listOf("generate", "kube", "web")) as EngineRequest.Answer).text.contains("podman generate: not built in droidtop yet"))
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

    @Test
    fun `podman reads pods, docker has none`() {
        val podman = EngineCli.PODMAN
        assertEquals(EngineRequest.PodCreate("web"), podman.parse(listOf("pod", "create", "--name", "web")))
        assertEquals(EngineRequest.PodCreate(null), podman.parse(listOf("pod", "create")))
        assertTrue((podman.parse(listOf("pod", "create", "-p", "8080:80")) as EngineRequest.Answer).text.contains("-p is not built in droidtop yet"))
        assertEquals(EngineRequest.PodList(quiet = true), podman.parse(listOf("pod", "ps", "-q")))
        assertEquals(EngineRequest.PodRemove(listOf("web"), force = true), podman.parse(listOf("pod", "rm", "-f", "web")))
        assertEquals(EngineRequest.PodStart(listOf("web")), podman.parse(listOf("pod", "start", "web")))
        assertEquals(EngineRequest.PodStop(listOf("web", "db")), podman.parse(listOf("pod", "stop", "web", "db")))
        assertTrue((podman.parse(listOf("pod", "top", "web")) as EngineRequest.Answer).text.contains("podman pod top: not built in droidtop yet"))
        val joined = podman.parse(listOf("run", "--pod", "web", "nginx")) as EngineRequest.Run
        assertEquals("web" to false, joined.pod to joined.newPod)
        val made = podman.parse(listOf("run", "--pod=new:api", "nginx")) as EngineRequest.Run
        assertEquals("api" to true, made.pod to made.newPod)
        // Docker has no pods.
        assertEquals(1, answer(listOf("run", "--pod", "web", "nginx")).code)
        assertTrue(answer(listOf("pod", "ps")).text.contains("'pod' is not a docker command"))
        assertTrue(podman.usage.contains("pod create|ps|rm|start|stop"))
        assertFalse(EngineCli.DOCKER.usage.contains("pod create"))

        val now = 10_000_000L
        val rows = listOf(
            EngineCli.PodRow(Pod("0123456789abcdef", "web", now - 120_000, listOf("a", "b")), containers = 2, running = 1),
            EngineCli.PodRow(Pod("fedcba9876543210", "empty", now - 5_000), containers = 0, running = 0),
        )
        val table = podman.formatPods(rows, quiet = false, nowMs = now)
        assertTrue(table.startsWith("POD ID         NAME    STATUS     CREATED         # OF CONTAINERS\n"))
        assertTrue(table.contains("0123456789ab   web     Degraded   2 minutes ago   2"))
        assertTrue(table.contains("fedcba987654   empty   Created    5 seconds ago   0"))
        assertEquals("0123456789ab\nfedcba987654\n", podman.formatPods(rows, quiet = true, nowMs = now))
    }

    @Test
    fun `pods keep their members by container id and lose them when a container goes`() {
        val web = Pod("aaaa1111bbbb2222", "web", 0)
        var pods = ContainerPods.with(emptyList(), web)
        assertEquals("a pod is already called web", ContainerPods.problemWith(pods, "web"))
        assertTrue(ContainerPods.problemWith(pods, "-bad")!!.contains("not a pod name"))
        pods = ContainerPods.withMember(pods, web.id, "droidtop-sibling-1")
        pods = ContainerPods.withMember(pods, web.id, "droidtop-sibling-1")
        assertEquals(listOf("droidtop-sibling-1"), pods.single().members)
        assertEquals(web.id, ContainerPods.find(pods, "web")?.id)
        assertEquals(web.id, ContainerPods.find(pods, "aaaa11")?.id)
        assertNull(ContainerPods.find(pods, "aa"))
        pods = ContainerPods.withoutMember(pods, "droidtop-sibling-1")
        assertEquals(emptyList<String>(), pods.single().members)
        assertEquals(emptyList<Pod>(), ContainerPods.without(pods, web.id))
        val dir = tmp.newFolder("pods")
        ContainerPods.save(dir, pods)
        assertEquals(pods, ContainerPods.read(dir))
        assertEquals(64, ContainerPods.newId().length)
    }

    @Test
    fun `create, restart and inspect are read on both fronts`() {
        assertEquals(
            EngineRequest.Run("nginx", "web", start = false),
            EngineCli.DOCKER.parse(listOf("create", "--name", "web", "nginx")),
        )
        assertTrue((EngineCli.PODMAN.parse(listOf("create", "--restart", "x", "nginx")) as EngineRequest.Answer).text.startsWith("podman create: --restart takes"))
        assertEquals(EngineRequest.Restart(listOf("a", "b")), EngineCli.DOCKER.parse(listOf("restart", "a", "b")))
        assertEquals(1, answer(listOf("restart", "-t", "3", "a")).code)
        assertEquals(EngineRequest.Inspect(listOf("web")), EngineCli.PODMAN.parse(listOf("inspect", "--type", "container", "web")))
        assertTrue(answer(listOf("inspect", "-f", "{{.Id}}", "web")).text.contains("--format is not built in droidtop yet"))
        assertTrue(answer(listOf("inspect", "--type=image", "nginx")).text.contains("not built in droidtop yet"))
        assertEquals(1, answer(listOf("inspect")).code)
    }

    @Test
    fun `-p on a port's own number is taken, another number needs network namespaces`() {
        val none = EngineFeatures("b", EngineFeature.entries.associateWith { FeatureState(kernel = false) })
        val run = EngineCli.DOCKER.parse(listOf("run", "-p", "8080:8080", "--publish=0.0.0.0:53:53/udp", "app"), none) as EngineRequest.Run
        assertEquals(listOf(ExposedPort(8080), ExposedPort(53, "udp")), run.publish)
        val remap = EngineCli.DOCKER.parse(listOf("run", "-p", "8080:80", "nginx"), none) as EngineRequest.Answer
        assertTrue(remap.text.contains("-p needs network namespaces, which this device's kernel does not have"))
        assertTrue(remap.text.contains("-p 80:80 is what happens"))
        // A host port Docker would choose is a remap too.
        assertTrue((EngineCli.DOCKER.parse(listOf("run", "-p", "80", "nginx"), none) as EngineRequest.Answer).text.contains("network namespaces"))
        assertTrue(answer(listOf("run", "-p", "127.0.0.1:80:80", "nginx")).text.contains("on one address (127.0.0.1) is not built"))
        assertTrue(answer(listOf("run", "-p", "x:y", "nginx")).text.contains("not a port"))
        assertTrue(answer(listOf("run", "-p", "80:80/sctp", "nginx")).text.contains("not a port"))
    }

    @Test
    fun `inspect says what droidtop knows in Docker's shape, with Podman's names on its front`() {
        val facts = EngineInspect.Facts(
            id = "droidtop-sibling-1a2b",
            name = "web",
            image = "docker.io/library/nginx:1.27-alpine",
            imageDigest = "sha256:abc",
            createdMs = 0L,
            status = "exited",
            exitCode = 3,
            error = null,
            config = ImageRunConfig(
                env = listOf("PATH=/bin"),
                entrypoint = listOf("/docker-entrypoint.sh"),
                cmd = listOf("nginx", "-g", "daemon off;"),
                workingDir = "/srv",
                ports = listOf(ExposedPort(80)),
            ),
            restart = ContainerProgram.RestartPolicy.OnFailure(2),
            folders = listOf("Music"),
            podId = "pod123",
        )
        val docker = EngineInspect.container(facts, EngineCli.Front.DOCKER).toString()
        assertTrue(docker.contains("\"Id\":\"droidtop-sibling-1a2b\""))
        assertTrue(docker.contains("\"Name\":\"/web\""))
        assertTrue(docker.contains("\"Created\":\"1970-01-01T00:00:00Z\""))
        assertTrue(docker.contains("\"Path\":\"/docker-entrypoint.sh\""))
        assertTrue(docker.contains("\"Args\":[\"nginx\",\"-g\",\"daemon off;\"]"))
        assertTrue(docker.contains("\"Status\":\"exited\""))
        assertTrue(docker.contains("\"ExitCode\":3"))
        assertTrue(docker.contains("\"RestartPolicy\":{\"Name\":\"on-failure\",\"MaximumRetryCount\":2}"))
        assertTrue(docker.contains("\"NetworkMode\":\"host\""))
        assertTrue(docker.contains("\"Destination\":\"${ContainerLayout.EXTRA_MOUNTS_DIR}/Music\""))
        assertTrue(docker.contains("\"ExposedPorts\":{\"80/tcp\":{}}"))
        assertTrue(docker.contains("\"Cmd\":[\"nginx\",\"-g\",\"daemon off;\"]"))
        assertFalse(docker.contains("\"Pod\""))
        val podman = EngineInspect.container(facts, EngineCli.Front.PODMAN).toString()
        assertTrue(podman.contains("\"Name\":\"web\""))
        assertTrue(podman.contains("\"Pod\":\"pod123\""))
        assertTrue(podman.contains("\"ImageName\":\"docker.io/library/nginx:1.27-alpine\""))
        // Nothing known: Docker's empty values, never invented ones.
        val bare = EngineInspect.container(facts.copy(createdMs = null, config = null, exitCode = null), EngineCli.Front.DOCKER).toString()
        assertTrue(bare.contains("\"Created\":\"0001-01-01T00:00:00Z\""))
        assertTrue(bare.contains("\"Cmd\":null"))
        assertTrue(EngineInspect.format(listOf(facts), EngineCli.Front.DOCKER).startsWith("[\n"))
    }
}
