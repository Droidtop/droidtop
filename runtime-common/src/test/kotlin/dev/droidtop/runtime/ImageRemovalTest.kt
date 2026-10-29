package dev.droidtop.runtime

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRemovalTest {
    private val nginx = OciImageStore.Entry("sha256:" + "a".repeat(64), "nginx", 1000)
    private val alpine = OciImageStore.Entry("sha256:" + "b".repeat(64), "docker.io/library/alpine:3.20", 500)
    private val entries = listOf(nginx, alpine)
    private val web = ContainerInfo(Container("c1", ContainerRole.SIBLING, ContainerBackend.PROOT, "/r/c1"), running = false, digest = nginx.digest, name = "web")

    @Test
    fun `an image comes off by any of its names, and one a container came from only with force`() {
        assertEquals(
            listOf(ImageRemoval.Removed("alpine:3.20", alpine), ImageRemoval.Missing("redis")),
            ImageRemoval.plan(entries, listOf(web), listOf("alpine:3.20", "redis"), force = false),
        )
        assertEquals(listOf(ImageRemoval.InUse("nginx", nginx, listOf("web"))), ImageRemoval.plan(entries, listOf(web), listOf("nginx"), force = false))
        assertEquals(listOf(ImageRemoval.Removed("docker.io/library/nginx:latest", nginx)), ImageRemoval.plan(entries, listOf(web), listOf("docker.io/library/nginx:latest"), force = true))
        // By a start of its id; and a second name for an image already removed is missing, as Docker answers it.
        assertEquals(
            listOf(ImageRemoval.Removed("bbbb", alpine), ImageRemoval.Missing("alpine:3.20")),
            ImageRemoval.plan(entries, emptyList(), listOf("bbbb", "alpine:3.20"), force = false),
        )
    }

    @Test
    fun `rmi is read and answered in each tool's words`() {
        assertEquals(EngineRequest.RemoveImages(listOf("nginx", "alpine"), force = true), EngineCli.DOCKER.parse(listOf("rmi", "-f", "nginx", "alpine")))
        assertTrue((EngineCli.PODMAN.parse(listOf("rmi")) as EngineRequest.Answer).text.contains("name at least one image"))
        assertTrue((EngineCli.DOCKER.parse(listOf("rmi", "--no-prune", "nginx")) as EngineRequest.Answer).text.contains("unknown flag"))
        val outcomes = listOf(ImageRemoval.Removed("nginx", nginx), ImageRemoval.Missing("redis"), ImageRemoval.InUse("x", alpine, listOf("web")))
        val docker = EngineCli.DOCKER.formatRmi(outcomes)
        assertEquals("Untagged: nginx:latest\nDeleted: ${nginx.digest}\n", docker.out)
        assertTrue(docker.err.startsWith("Error response from daemon: No such image: redis\n"))
        assertTrue(docker.err.contains("container web"))
        assertEquals(1, docker.code)
        val podman = EngineCli.PODMAN.formatRmi(outcomes)
        assertEquals("Untagged: docker.io/library/nginx:latest\nDeleted: ${"a".repeat(64)}\n", podman.out)
        assertTrue(podman.err.startsWith("Error: redis: image not known\n"))
        assertEquals(2, podman.code)
        assertEquals(0, EngineCli.PODMAN.formatRmi(listOf(ImageRemoval.Removed("nginx", nginx))).code)
    }

    @Test
    fun `both APIs route an image removal, a name with slashes whole`() {
        assertEquals(DockerApi.Call.ImageRemove(listOf("docker.io/library/nginx:latest"), force = true), DockerApi.route("DELETE", "/v1.43/images/docker.io/library/nginx:latest", mapOf("force" to "1")))
        assertEquals(DockerApi.Call.ImageRemove(listOf("nginx"), force = false), LibpodApi.route("DELETE", "/v5.2.5/libpod/images/nginx", emptyMap()))
        // Podman's client names every image as its own `images`; the request keeps them all.
        val request = EngineHttp.read(
            ByteArrayInputStream("DELETE /v5.2.5/libpod/images/remove?images=nginx&images=alpine&force=false&ignore=true HTTP/1.1\r\nHost: d\r\n\r\n".toByteArray(Charsets.ISO_8859_1)),
        )!!
        assertEquals(listOf("nginx", "alpine"), request.queryAll["images"])
        assertEquals(
            DockerApi.Call.ImageRemove(listOf("nginx", "alpine"), force = false, batch = true, ignoreMissing = true),
            LibpodApi.route(request.method, request.path, request.query, request.queryAll),
        )
        assertTrue(LibpodApi.route("DELETE", "/v5.2.5/libpod/images/remove", mapOf("all" to "true")) is DockerApi.Call.NotBuilt)
    }

    @Test
    fun `the APIs answer a removal in their own shapes`() {
        val removed = DockerApi.imageRemoval(ImageRemoval.Removed("nginx", nginx))
        assertEquals(200, removed.status)
        assertEquals("""[{"Untagged":"nginx:latest"},{"Deleted":"${nginx.digest}"}]""", String(removed.body).trim())
        assertEquals(404, DockerApi.imageRemoval(ImageRemoval.Missing("redis")).status)
        assertEquals(409, DockerApi.imageRemoval(ImageRemoval.InUse("nginx", nginx, listOf("web"))).status)

        assertEquals(409, LibpodApi.imageRemoval(listOf(ImageRemoval.InUse("nginx", nginx, listOf("web"))), batch = false, ignoreMissing = false).status)
        assertEquals(404, LibpodApi.imageRemoval(listOf(ImageRemoval.Missing("redis")), batch = false, ignoreMissing = false).status)
        val report = LibpodApi.imageRemoval(listOf(ImageRemoval.Removed("nginx", nginx), ImageRemoval.Missing("redis")), batch = true, ignoreMissing = false)
        assertEquals(200, report.status)
        assertEquals(
            """{"Deleted":["${"a".repeat(64)}"],"Untagged":["docker.io/library/nginx:latest"],"Errors":["redis: image not known"],"ExitCode":1}""",
            String(report.body).trim(),
        )
        val ignored = LibpodApi.imageRemoval(listOf(ImageRemoval.Missing("redis")), batch = true, ignoreMissing = true)
        assertTrue(String(ignored.body).contains("\"ExitCode\":0"))
    }
}
