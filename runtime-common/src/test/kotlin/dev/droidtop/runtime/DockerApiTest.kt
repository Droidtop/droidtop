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
        assertEquals(DockerApi.Call.NotBuilt("POST /containers/create"), DockerApi.route("POST", "/v1.43/containers/create", emptyMap()))
        assertEquals(DockerApi.Call.NotBuilt("POST /exec/abc/start"), DockerApi.route("POST", "/exec/abc/start", emptyMap()))
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
}
