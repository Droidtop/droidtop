package dev.droidtop.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** docs/SPEC.md 3d, "The Docker Engine API" (Droidtop/tracker#555 D14): the socket's HTTP and the routes it answers. */
class DockerApiTest {
    private fun read(text: String) = EngineHttp.read(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)))

    @Test
    fun `requests are read one after another on a kept connection, with their bodies`() {
        val input = ByteArrayInputStream(
            ("GET /v1.43/containers/json?all=1&filters=%7B%7D HTTP/1.1\r\nHost: docker\r\n\r\n" +
                "POST /v1.43/containers/create?name=web HTTP/1.1\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}" +
                "POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2;ext\r\nde\r\n0\r\n\r\n").toByteArray(),
        )
        val first = EngineHttp.read(input)!!
        assertEquals("GET", first.method)
        assertEquals("/v1.43/containers/json", first.path)
        assertEquals(mapOf("all" to "1", "filters" to "{}"), first.query)
        assertEquals("docker", first.headers["host"])
        val second = EngineHttp.read(input)!!
        assertEquals("{}", String(second.body))
        assertTrue(second.closes)
        assertEquals("abcde", String(EngineHttp.read(input)!!.body))
        assertNull(EngineHttp.read(input))
    }

    @Test
    fun `what a container can make droidtop read is bounded`() {
        fun status(text: String): Int = try {
            read(text)
            fail("read $text")
            0
        } catch (e: EngineHttp.BadRequest) {
            e.status
        }
        assertEquals(413, status("POST / HTTP/1.1\r\nContent-Length: ${EngineHttp.MAX_BODY_BYTES + 1}\r\n\r\n"))
        assertEquals(413, status("GET /" + "a".repeat(EngineHttp.MAX_HEAD_BYTES) + " HTTP/1.1\r\n\r\n"))
        assertEquals(400, status("NONSENSE\r\n\r\n"))
        assertEquals(400, status("GET / HTTP/1.1\r\nno colon\r\n\r\n"))
        assertEquals(400, status("POST / HTTP/1.1\r\nContent-Length: 5\r\n\r\nab"))
    }

    @Test
    fun `a response carries its length and the daemon's headers`() {
        val out = ByteArrayOutputStream()
        EngineHttp.write(out, EngineHttp.Response.text(200, "OK"), DockerApi.headers)
        val text = out.toString(Charsets.UTF_8.name())
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 2\r\n"))
        assertTrue(text.contains("Api-Version: ${DockerApi.API_VERSION}\r\n"))
        assertTrue(text.endsWith("\r\n\r\nOK"))
    }

    @Test
    fun `routes with or without the version prefix, and a route not built says so`() {
        assertEquals(DockerApi.Call.Ping, DockerApi.route("HEAD", "/_ping", emptyMap()))
        assertEquals(DockerApi.Call.Version, DockerApi.route("GET", "/v1.41/version", emptyMap()))
        assertEquals(DockerApi.Call.Containers(all = true), DockerApi.route("GET", "/v1.43/containers/json", mapOf("all" to "true")))
        assertEquals(DockerApi.Call.Containers(all = false), DockerApi.route("GET", "/containers/json/", emptyMap()))
        assertEquals(DockerApi.Call.Container("web"), DockerApi.route("GET", "/v1.43/containers/web/json", emptyMap()))
        assertEquals(DockerApi.Call.Images, DockerApi.route("GET", "/images/json", emptyMap()))
        assertEquals(DockerApi.Call.NotBuilt("POST /containers/web/attach"), DockerApi.route("POST", "/v1.43/containers/web/attach", emptyMap()))
        assertEquals(DockerApi.Call.NotBuilt("POST /exec/abc/resize"), DockerApi.route("POST", "/exec/abc/resize", emptyMap()))
        assertEquals(DockerApi.Call.NotFound, DockerApi.route("GET", "/nonsense", emptyMap()))
        assertTrue(String(DockerApi.notBuilt("POST /containers/create").body).contains("not built in droidtop's engine yet"))
        assertEquals(501, DockerApi.notBuilt("x").status)
    }

    @Test
    fun `version, info, containers and images are in Docker's shape`() {
        assertEquals("arm64", DockerApi.arch("aarch64"))
        assertEquals("amd64", DockerApi.arch("x86_64"))
        val version = DockerApi.version("arm64", "5.10.0")
        assertTrue(version.contains("\"ApiVersion\":\"1.43\""))
        assertTrue(version.contains("\"Arch\":\"arm64\""))
        val none = EngineFeatures("b", EngineFeature.entries.associateWith { FeatureState(kernel = false) })
        val info = DockerApi.info(DockerApi.InfoFacts(3, 1, 2, "arm64", "5.10.0", 8, 8_000_000_000, none))
        assertTrue(info.contains("\"ContainersStopped\":2"))
        assertTrue(info.contains("\"MemoryLimit\":false"))
        assertTrue(info.contains("cgroups: not in this device's kernel"))

        val facts = EngineInspect.Facts(
            id = "droidtop-sibling-1a2b", name = "web", image = "docker.io/library/nginx:1.27-alpine", imageDigest = "sha256:abc",
            createdMs = 5_000L, status = "running", exitCode = null, error = null,
            config = ImageRunConfig(entrypoint = listOf("/docker-entrypoint.sh"), cmd = listOf("nginx")),
            restart = ContainerProgram.RestartPolicy.No, folders = emptyList(), podId = null,
        )
        val stopped = facts.copy(id = "droidtop-sibling-9f00", name = "old", status = "exited", exitCode = 1)
        val rows = listOf(DockerApi.Summary(facts, "Up"), DockerApi.Summary(stopped, "Exited (1)"))
        val running = DockerApi.containers(rows, all = false)
        assertTrue(running.contains("\"Names\":[\"/web\"]"))
        assertTrue(running.contains("\"Command\":\"/docker-entrypoint.sh nginx\""))
        assertTrue(running.contains("\"Created\":5"))
        assertTrue(running.contains("\"NetworkMode\":\"host\""))
        assertTrue(!running.contains("/old"))
        assertTrue(DockerApi.containers(rows, all = true).contains("\"Status\":\"Exited (1)\""))
        assertTrue(DockerApi.container(facts).contains("\"Name\":\"/web\""))

        val images = DockerApi.images(
            listOf(
                OciImageStore.Entry("sha256:0123", "docker.io/library/nginx:1.27-alpine", 21_400_000),
                OciImageStore.Entry("sha256:4567", "docker.io/gitea/gitea:1.22", 100),
            ),
        )
        assertTrue(images.contains("\"RepoTags\":[\"nginx:1.27-alpine\"]"))
        assertTrue(images.contains("\"RepoTags\":[\"gitea/gitea:1.22\"]"))
        assertTrue(images.contains("\"Size\":21400000"))
        assertEquals("unix:///run/droidtop-app-storage/docker.sock", DockerApi.DOCKER_HOST)
        assertEquals(DockerApi.DOCKER_HOST, ContainerLayout.clientEnvironment(null)["DOCKER_HOST"])
    }

    @Test
    fun `the write routes are read into calls, and what droidtop will not do is said`() {
        assertEquals(DockerApi.Call.Create("web"), DockerApi.route("POST", "/v1.43/containers/create", mapOf("name" to "web")))
        assertEquals(DockerApi.Call.Start("web"), DockerApi.route("POST", "/containers/web/start", emptyMap()))
        assertEquals(DockerApi.Call.Stop("web"), DockerApi.route("POST", "/containers/web/stop", mapOf("t" to "10")))
        assertEquals(DockerApi.Call.Kill("web", "SIGTERM"), DockerApi.route("POST", "/containers/web/kill", mapOf("signal" to "SIGTERM")))
        assertTrue(DockerApi.route("POST", "/containers/web/kill", mapOf("signal" to "SIGHUP")) is DockerApi.Call.NotBuilt)
        assertEquals(DockerApi.Call.Remove("web", force = true), DockerApi.route("DELETE", "/v1.43/containers/web", mapOf("force" to "1", "v" to "1")))
        assertEquals(DockerApi.Call.Pull("nginx:1.27"), DockerApi.route("POST", "/images/create", mapOf("fromImage" to "nginx", "tag" to "1.27")))
        assertEquals(DockerApi.Call.Pull("ghcr.io/x/y:2"), DockerApi.route("POST", "/images/create", mapOf("fromImage" to "ghcr.io/x/y:2", "tag" to "latest")))
        assertTrue(DockerApi.route("POST", "/images/create", mapOf("fromImage" to "a;b")) is DockerApi.Call.Refused)
        assertEquals(DockerApi.Call.Logs("web", 20, stdout = true, stderr = true), DockerApi.route("GET", "/containers/web/logs", mapOf("stdout" to "1", "stderr" to "1", "tail" to "20")))
        assertTrue(DockerApi.route("GET", "/containers/web/logs", mapOf("stdout" to "1", "follow" to "1")) is DockerApi.Call.NotBuilt)
        assertEquals(DockerApi.Call.Refused(400, "You must choose at least one stream"), DockerApi.route("GET", "/containers/web/logs", emptyMap()))
        assertEquals(DockerApi.Call.Wait("web", "not-running"), DockerApi.route("POST", "/containers/web/wait", emptyMap()))
        assertTrue(DockerApi.route("POST", "/containers/web/wait", mapOf("condition" to "removed")) is DockerApi.Call.NotBuilt)
        assertEquals(
            DockerApi.Call.Containers(all = true, filters = mapOf("label" to listOf("com.docker.compose.project=demo"))),
            DockerApi.route("GET", "/containers/json", mapOf("all" to "1", "filters" to "{\"label\":[\"com.docker.compose.project=demo\"]}")),
        )
        assertTrue(DockerApi.route("GET", "/containers/json", mapOf("filters" to "nope")) is DockerApi.Call.Refused)
    }

    private val none = EngineFeatures("b", EngineFeature.entries.associateWith { FeatureState(kernel = false) })

    private fun create(body: String) = DockerApi.createRequest(body, "web", none)

    @Test
    fun `a create body becomes the one run request, start left to start`() {
        val (run, refused) = create(
            """{"Image":"nginx:1.27","Cmd":["nginx","-g","daemon off;"],"Env":["A=1"],"Labels":{"com.docker.compose.project":"demo"},
               "HostConfig":{"Binds":["Music:/run/droidtop-mounts/Music:ro"],"RestartPolicy":{"Name":"on-failure","MaximumRetryCount":3},
               "PortBindings":{"8080/tcp":[{"HostIp":"","HostPort":"8080"}]},"NetworkMode":"default","Memory":0},
               "NetworkingConfig":{"EndpointsConfig":{"bridge":{}}},"Tty":false,"OpenStdin":false}""",
        )
        assertNull(refused)
        assertEquals(
            EngineRequest.Run(
                image = "nginx:1.27", name = "web", args = listOf("nginx", "-g", "daemon off;"), env = listOf("A=1"),
                volumes = listOf("Music"), restart = ContainerProgram.RestartPolicy.OnFailure(3), publish = listOf(ExposedPort(8080)),
                start = false, labels = mapOf("com.docker.compose.project" to "demo"),
            ),
            run,
        )
    }

    @Test
    fun `a create body asking for what droidtop does not do is refused, never trimmed`() {
        fun answer(body: String): Pair<Int, String> = create(body).second!!.let { it.status to String(it.body) }
        assertEquals(400, answer("""{"Cmd":["sh"]}""").first)
        assertEquals(400, answer("""{"Image":"alpine","HostConfig":{"Privileged":true}}""").first)
        assertTrue(answer("""{"Image":"alpine","HostConfig":{"Memory":536870912}}""").second.contains("--memory needs cgroups"))
        assertTrue(answer("""{"Image":"alpine","HostConfig":{"PortBindings":{"80/tcp":[{"HostPort":"8080"}]}}}""").second.contains("network namespaces"))
        assertTrue(answer("""{"Image":"alpine","HostConfig":{"Binds":["/etc:/host-etc"]}}""").second.contains("a folder you mapped"))
        // A network droidtop does not have is not found, as Docker says; none needs network namespaces.
        assertEquals(404, answer("""{"Image":"alpine","HostConfig":{"NetworkMode":"demo_default"}}""").first)
        assertEquals(404, answer("""{"Image":"alpine","NetworkingConfig":{"EndpointsConfig":{"demo_default":{}}}}""").first)
        assertTrue(answer("""{"Image":"alpine","HostConfig":{"NetworkMode":"none"}}""").second.contains("network namespaces"))
        assertEquals(501, answer("""{"Image":"alpine","Entrypoint":["/bin/sh"]}""").first)
        assertEquals(501, answer("""{"Image":"alpine","User":"1000"}""").first)
        assertEquals(501, answer("""{"Image":"alpine","HostConfig":{"Mounts":[{"Type":"volume","Source":"data","Target":"/data"}]}}""").first)
        assertEquals(501, answer("""{"Image":"alpine","HostConfig":{"CapAdd":["NET_ADMIN"]}}""").first)
        assertNull(create("""{"Image":"alpine","User":"root"}""").second)
    }

    @Test
    fun `filters pick by label, name, id and status, and logs are Docker's frames`() {
        val base = EngineInspect.Facts(
            id = "droidtop-sibling-1a2b", name = "demo-web-1", image = "nginx", imageDigest = null, createdMs = null,
            status = "running", exitCode = null, error = null, config = null, restart = ContainerProgram.RestartPolicy.No,
            folders = emptyList(), podId = null, labels = mapOf("com.docker.compose.project" to "demo"),
        )
        val row = DockerApi.Summary(base, "Up")
        assertTrue(DockerApi.matches(row, mapOf("label" to listOf("com.docker.compose.project=demo"))))
        assertTrue(DockerApi.matches(row, mapOf("label" to listOf("com.docker.compose.project"))))
        assertTrue(!DockerApi.matches(row, mapOf("label" to listOf("com.docker.compose.project=other"))))
        assertTrue(DockerApi.matches(row, mapOf("name" to listOf("web"), "status" to listOf("running"))))
        assertTrue(!DockerApi.matches(row, mapOf("status" to listOf("exited"))))
        assertEquals(setOf("ancestor"), DockerApi.unknownFilters(mapOf("ancestor" to listOf("nginx"), "id" to listOf("droidtop"))))
        assertTrue(DockerApi.summary(row).toString().contains("\"Labels\":{\"com.docker.compose.project\":\"demo\"}"))

        val frames = DockerApi.logFrames(listOf("hi"))
        assertEquals(listOf<Byte>(1, 0, 0, 0, 0, 0, 0, 3), frames.take(8))
        assertEquals("hi\n", String(frames, 8, 3))
        assertTrue(DockerApi.created("droidtop-sibling-1a2b", listOf("note")).contains("\"Warnings\":[\"note\"]"))
        assertTrue(DockerApi.waited(3).contains("\"StatusCode\":3"))
        assertTrue(DockerApi.pulled("nginx", "sha256:abc").lines().first().contains("Pulling from nginx"))
    }

    @Test
    fun `networks are named groups a create joins, and what would need isolation is said`() {
        assertEquals(DockerApi.Call.Networks(emptyMap()), DockerApi.route("GET", "/v1.43/networks", emptyMap()))
        assertEquals(DockerApi.Call.NetworkCreate, DockerApi.route("POST", "/networks/create", emptyMap()))
        assertEquals(DockerApi.Call.NetworkGet("demo_default"), DockerApi.route("GET", "/networks/demo_default", emptyMap()))
        assertEquals(DockerApi.Call.NetworkRemove("demo_default"), DockerApi.route("DELETE", "/networks/demo_default", emptyMap()))
        assertEquals(DockerApi.Call.NetworkConnect("n", connect = false), DockerApi.route("POST", "/networks/n/disconnect", emptyMap()))
        assertEquals(DockerApi.Call.ImageGet("docker.io/library/nginx:1.27"), DockerApi.route("GET", "/v1.43/images/docker.io/library/nginx:1.27/json", emptyMap()))

        val (made, refused) = DockerApi.networkRequest("""{"Name":"demo_default","Driver":"bridge","Labels":{"com.docker.compose.project":"demo"}}""", emptyList(), none)
        assertNull(refused)
        val demo = made!!
        assertEquals("demo_default", demo.name)
        assertEquals(64, demo.id.length)
        assertEquals(409, DockerApi.networkRequest("""{"Name":"demo_default"}""", listOf(demo), none).second!!.status)
        assertEquals(409, DockerApi.networkRequest("""{"Name":"bridge"}""", emptyList(), none).second!!.status)
        assertEquals(501, DockerApi.networkRequest("""{"Name":"x","Internal":true}""", emptyList(), none).second!!.status)
        assertEquals(501, DockerApi.networkRequest("""{"Name":"x","IPAM":{"Config":[{"Subnet":"10.0.0.0/24"}]}}""", emptyList(), none).second!!.status)
        assertEquals(501, DockerApi.networkRequest("""{"Name":"x","Driver":"overlay"}""", emptyList(), none).second!!.status)

        val (run, _) = DockerApi.createRequest(
            """{"Image":"nginx","HostConfig":{"NetworkMode":"demo_default"},"NetworkingConfig":{"EndpointsConfig":{"demo_default":{"Aliases":["web","demo-web-1"]}}}}""",
            "demo-web-1", none, listOf(demo),
        )
        assertEquals(listOf(EngineRequest.NetworkJoin(demo.id, listOf("web", "demo-web-1"))), run!!.networks)

        var nets = ContainerNetworks.withMember(listOf(demo), demo.id, "droidtop-sibling-1", listOf("web"))
        val json = DockerApi.networks(ContainerNetworks.predefined() + nets, mapOf("droidtop-sibling-1" to "demo-web-1"), mapOf("label" to listOf("com.docker.compose.project=demo")))
        assertTrue(json.contains("\"Name\":\"demo_default\""))
        assertTrue(json.contains("\"droidtop-sibling-1\":{\"Name\":\"demo-web-1\""))
        assertTrue(!json.contains("\"Name\":\"bridge\""))
        assertTrue(DockerApi.networks(ContainerNetworks.predefined(), emptyMap(), emptyMap()).contains("\"Driver\":\"host\""))
        assertEquals(ContainerNetworks.predefinedId("bridge"), ContainerNetworks.predefinedId("bridge"))
        nets = ContainerNetworks.withoutMember(nets, "droidtop-sibling-1")
        assertEquals(emptyList<String>(), nets.single().members)
        assertEquals(emptyMap<String, List<String>>(), nets.single().aliases)
        assertEquals(setOf("driver"), DockerApi.unknownNetworkFilters(mapOf("driver" to listOf("bridge"), "name" to listOf("x"))))
        assertEquals("web", DockerApi.connectTarget("""{"Container":"web","EndpointConfig":{}}"""))
        assertNull(DockerApi.connectTarget("{}"))
        assertNull(DockerApi.connectTarget("not json"))
        assertTrue(DockerApi.networkCreated(demo.id).contains("\"Id\":\"${demo.id}\""))
        assertTrue(DockerApi.networkJson(demo, emptyMap()).endsWith("}\n"))
    }

    @Test
    fun `an image is found by any of Docker's spellings and described from its config`() {
        val entries = listOf(
            OciImageStore.Entry("sha256:0123456789abcdef", "docker.io/library/nginx:1.27-alpine", 21_400_000),
            OciImageStore.Entry("sha256:fedcba9876543210", "alpine", 3_000_000),
        )
        assertEquals("sha256:0123456789abcdef", DockerApi.findImage(entries, "nginx:1.27-alpine")?.digest)
        assertEquals("sha256:0123456789abcdef", DockerApi.findImage(entries, "docker.io/library/nginx:1.27-alpine")?.digest)
        assertEquals("sha256:fedcba9876543210", DockerApi.findImage(entries, "alpine:latest")?.digest)
        assertEquals("sha256:fedcba9876543210", DockerApi.findImage(entries, "fedcba98")?.digest)
        assertNull(DockerApi.findImage(entries, "nginx:1.26"))
        val json = DockerApi.image(entries[0], ImageRunConfig(cmd = listOf("nginx"), ports = listOf(ExposedPort(80))), "arm64")
        assertTrue(json.contains("\"RepoTags\":[\"nginx:1.27-alpine\"]"))
        assertTrue(json.contains("\"Cmd\":[\"nginx\"]"))
        assertTrue(json.contains("\"ExposedPorts\":{\"80/tcp\":{}}"))
    }

    @Test
    fun `exec runs a command on pipes and hands back its output and exit code`() {
        assertEquals(DockerApi.Call.ExecCreate("web"), DockerApi.route("POST", "/v1.43/containers/web/exec", emptyMap()))
        assertEquals(DockerApi.Call.ExecStart("abc"), DockerApi.route("POST", "/exec/abc/start", emptyMap()))
        assertEquals(DockerApi.Call.ExecGet("abc"), DockerApi.route("GET", "/exec/abc/json", emptyMap()))
        assertTrue(DockerApi.route("POST", "/exec/abc/resize", emptyMap()) is DockerApi.Call.NotBuilt)

        val (spec, refused) = DockerApi.execRequest("""{"AttachStdout":true,"AttachStderr":true,"Cmd":["ls","-la"],"Env":["A=1"],"WorkingDir":"/srv","Tty":false}""")
        assertNull(refused)
        assertEquals(DockerApi.ExecSpec(listOf("ls", "-la"), mapOf("A" to "1"), "/srv"), spec)
        assertEquals(501, DockerApi.execRequest("""{"Cmd":["sh"],"AttachStdin":true,"Tty":true}""").second!!.status)
        assertEquals(400, DockerApi.execRequest("""{"Cmd":[]}""").second!!.status)
        assertEquals(400, DockerApi.execRequest("""{"Cmd":["ls"],"Env":["1A=x"]}""").second!!.status)
        assertEquals(400, DockerApi.execRequest("""{"Cmd":["ls"],"Privileged":true}""").second!!.status)
        assertEquals(501, DockerApi.execRequest("""{"Cmd":["ls"],"User":"1000"}""").second!!.status)

        val frames = DockerApi.outputFrames("out\n", "err\n")
        assertEquals(listOf<Byte>(1, 0, 0, 0, 0, 0, 0, 4), frames.take(8))
        assertEquals(listOf<Byte>(2, 0, 0, 0, 0, 0, 0, 4), frames.drop(12).take(8))
        assertEquals(0, DockerApi.outputFrames("", "").size)
        assertTrue(DockerApi.detached("""{"Detach":true,"Tty":false}"""))
        assertTrue(!DockerApi.detached("{}"))
        val inspect = DockerApi.execInspect("abc", "droidtop-sibling-1", spec!!, running = false, exitCode = 3)
        assertTrue(inspect.contains("\"ExitCode\":3"))
        assertTrue(inspect.contains("\"entrypoint\":\"ls\""))

        val out = ByteArrayOutputStream()
        EngineHttp.write(out, EngineHttp.Response(101, "application/vnd.docker.multiplexed-stream", frames), DockerApi.headers)
        val text = out.toString(Charsets.ISO_8859_1.name())
        assertTrue(text.startsWith("HTTP/1.1 101 UPGRADED\r\n"))
        assertTrue(text.contains("Upgrade: tcp\r\n"))
        assertTrue(!text.contains("Content-Length"))

        val argv = ContainerEnter.directCommand(listOf("ls", "-la"))
        assertEquals(listOf("/bin/sh", "-c"), argv.take(2))
        assertEquals(listOf("sh", "ls", "-la"), argv.drop(3))
        assertEquals(mapOf("DROIDTOP_CWD" to "/srv"), ContainerEnter.execEnvironment("/srv"))
    }
}
