package dev.droidtop.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/SPEC.md 3d, "The libpod API" (Droidtop/tracker#555 D15): the routes podman.sock answers and their shapes. */
class LibpodApiTest {
    private val digest = "sha256:" + "ab".repeat(32)
    private val config = ImageRunConfig(env = listOf("PATH=/usr/bin"), entrypoint = listOf("/entry.sh"), cmd = listOf("nginx", "-g", "daemon off;"))
    private val web = EngineInspect.Facts(
        id = "droidtop-sibling-1a2b3c4d", name = "web", image = "nginx", imageDigest = digest, createdMs = 1_700_000_000_000,
        status = "running", exitCode = null, error = null, config = config, restart = ContainerProgram.RestartPolicy.No,
        folders = listOf("Music"), podId = "c".repeat(64), labels = mapOf("app" to "demo"), startedMs = 1_700_000_100_000,
        podName = "demo",
    )
    private val job = web.copy(id = "droidtop-sibling-99887766", name = "job", status = "exited", exitCode = 3, endedMs = 1_700_000_200_000, podId = null, podName = null)

    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun list(text: String) = Json.parseToJsonElement(text).jsonArray
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `a client's own version is answered, from 4 to 5, and only libpod paths are libpod's`() {
        assertEquals(LibpodApi.Version(5, 2, 5), LibpodApi.version("/v5.2.5/libpod/_ping"))
        assertEquals(LibpodApi.Version(4, 9, 0), LibpodApi.version("/v4.9/libpod/containers/json"))
        assertEquals(LibpodApi.OLDEST, LibpodApi.version("/v3.4.4/libpod/_ping"))
        assertEquals(LibpodApi.NEWEST, LibpodApi.version("/v6.0.0/libpod/_ping"))
        assertEquals(LibpodApi.NEWEST, LibpodApi.version("/libpod/_ping"))
        assertNull(LibpodApi.version("/v1.43/containers/json"))
        assertNull(LibpodApi.version("/v5.0.0/libpodx/_ping"))
        assertEquals("5.2.5", LibpodApi.headers(LibpodApi.Version(5, 2, 5))["Libpod-Api-Version"])
        assertEquals("unix:///run/droidtop-app-storage/podman.sock", LibpodApi.CONTAINER_HOST)
        assertEquals(LibpodApi.CONTAINER_HOST, ContainerLayout.clientEnvironment(null)["CONTAINER_HOST"])
    }

    @Test
    fun `the read routes are the same calls the Docker API asks for, and the rest says not built`() {
        fun get(path: String, query: Map<String, String> = emptyMap()) = LibpodApi.route("GET", "/v5.2.5/libpod$path", query)
        assertEquals(DockerApi.Call.Ping, get("/_ping"))
        assertEquals(DockerApi.Call.Info, get("/info"))
        assertEquals(DockerApi.Call.Containers(all = true, filters = mapOf("label" to listOf("a=b"))), get("/containers/json", mapOf("all" to "true", "filters" to """{"label":["a=b"]}""")))
        assertEquals(DockerApi.Call.Container("web"), get("/containers/web/json"))
        assertEquals(DockerApi.Call.Exists(DockerApi.Kind.CONTAINER, "web"), get("/containers/web/exists"))
        assertEquals(DockerApi.Call.ImageGet("docker.io/library/nginx:latest"), get("/images/docker.io/library/nginx:latest/json"))
        assertEquals(DockerApi.Call.Exists(DockerApi.Kind.IMAGE, "docker.io/library/nginx"), get("/images/docker.io/library/nginx/exists"))
        assertEquals(DockerApi.Call.Pods(emptyMap()), get("/pods/json"))
        assertEquals(DockerApi.Call.PodGet("demo"), get("/pods/demo/json"))
        assertEquals(DockerApi.Call.Exists(DockerApi.Kind.POD, "demo"), get("/pods/demo/exists"))
        assertEquals(DockerApi.Call.Networks(mapOf("name" to listOf("x"))), get("/networks/json", mapOf("filters" to """{"name":["x"]}""")))
        assertEquals(DockerApi.Call.Exists(DockerApi.Kind.NETWORK, "x"), get("/networks/x/exists"))
        assertTrue(get("/containers/json", mapOf("filters" to "[1]")) is DockerApi.Call.Refused)
        assertEquals(DockerApi.Call.NotBuilt("POST /libpod/containers/create"), LibpodApi.route("POST", "/v5.2.5/libpod/containers/create", emptyMap()))
        assertEquals(DockerApi.Call.NotFound, get("/nothing"))
        assertTrue(LibpodApi.notBuilt("x").body.toString(Charsets.UTF_8).contains("The libpod API"))
    }

    @Test
    fun `ps rows carry what Podman's ps writes its STATUS from, and running ones only without all`() {
        val rows = listOf(DockerApi.Summary(web, "Up"), DockerApi.Summary(job, "Exited (3)"))
        assertEquals(1, list(LibpodApi.containers(rows, all = false, filters = emptyMap())).size)
        val all = list(LibpodApi.containers(rows, all = true, filters = emptyMap())).map { it.jsonObject }
        val w = all.first()
        assertEquals("droidtop-sibling-1a2b3c4d", w.str("Id"))
        assertEquals("docker.io/library/nginx:latest", w.str("Image"))
        assertEquals("ab".repeat(32), w.str("ImageID"))
        assertEquals(listOf("/entry.sh", "nginx", "-g", "daemon off;"), w.getValue("Command").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("1700000100", w.str("StartedAt"))
        assertEquals("running", w.str("State"))
        // Status is a health check's in Podman's list; droidtop has none, and Podman's ps writes "Up ..." itself.
        assertEquals("", w.str("Status"))
        assertEquals("demo", w.str("PodName"))
        assertEquals(listOf("/run/droidtop-mounts/Music"), w.getValue("Mounts").jsonArray.map { it.jsonPrimitive.content })
        val j = all.last()
        assertEquals("true", j.str("Exited"))
        assertEquals("1700000200", j.str("ExitedAt"))
        assertEquals("3", j.str("ExitCode"))
        assertEquals("", j.str("Pod"))
        assertEquals(1, list(LibpodApi.containers(rows, all = true, filters = mapOf("name" to listOf("job")))).size)
    }

    @Test
    fun `inspect is the podman front's object, with the image's id and Podman 4's one-string Entrypoint`() {
        val five = obj(LibpodApi.inspect(web, LibpodApi.Version(5, 2, 5)))
        assertEquals("ab".repeat(32), five.str("Image"))
        assertEquals(digest, five.str("ImageDigest"))
        assertEquals("web", five.str("Name"))
        assertEquals(JsonArray(listOf(JsonPrimitive("/entry.sh"))), five.getValue("Config").jsonObject["Entrypoint"])
        assertEquals("2023-11-14T22:15:00Z", five.getValue("State").jsonObject.str("StartedAt"))
        assertEquals(EngineInspect.ZERO_TIME, five.getValue("State").jsonObject.str("FinishedAt"))
        val four = obj(LibpodApi.inspect(web, LibpodApi.Version(4, 9, 5)))
        assertEquals(JsonPrimitive("/entry.sh"), four.getValue("Config").jsonObject["Entrypoint"])
    }

    @Test
    fun `images, pods and networks are in Podman's shapes`() {
        val entry = OciImageStore.Entry(digest = digest, reference = "nginx", sizeBytes = 70)
        val image = list(LibpodApi.images(listOf(entry))).single().jsonObject
        assertEquals("ab".repeat(32), image.str("Id"))
        assertEquals("docker.io/library/nginx:latest", image.getValue("RepoTags").jsonArray.single().jsonPrimitive.content)
        assertEquals("docker.io/library/nginx@$digest", image.getValue("RepoDigests").jsonArray.single().jsonPrimitive.content)
        assertEquals("/entry.sh", obj(LibpodApi.image(entry, config, "arm64")).getValue("Config").jsonObject.getValue("Entrypoint").jsonArray.single().jsonPrimitive.content)

        val pod = Pod("c".repeat(64), "demo", 1_700_000_000_000, listOf(web.id, job.id))
        val facts = LibpodApi.PodFacts(pod, listOf(web, job))
        val listed = list(LibpodApi.pods(listOf(facts), emptyMap())).single().jsonObject
        assertEquals("Degraded", listed.str("Status"))
        assertEquals(listOf("web", "job"), listed.getValue("Containers").jsonArray.map { it.jsonObject.str("Names") })
        assertEquals(0, list(LibpodApi.pods(listOf(facts), mapOf("name" to listOf("other")))).size)
        assertEquals(setOf("label"), LibpodApi.unknownPodFilters(mapOf("label" to listOf("a"), "name" to listOf("b"))))
        val inspected = obj(LibpodApi.pod(facts))
        assertEquals("Degraded", inspected.str("State"))
        assertEquals("2", inspected.str("NumContainers"))

        val network = Network(ContainerNetworks.newId(), "demo_default", 1_700_000_000_000, mapOf("p" to "demo"), listOf(web.id))
        val n = list(LibpodApi.networks(ContainerNetworks.predefined() + network, mapOf(web.id to "web"), mapOf("label" to listOf("p=demo")))).single().jsonObject
        assertEquals("demo_default", n.str("name"))
        assertEquals("bridge", n.str("driver"))
        assertEquals("web", n.getValue("containers").jsonObject.getValue(web.id).jsonObject.str("name"))
    }

    @Test
    fun `version names the Podman Engine component the client reads, and info the counts`() {
        val v = obj(LibpodApi.version(LibpodApi.Version(4, 9, 5), "arm64", "5.10"))
        val engine = v.getValue("Components").jsonArray.single().jsonObject
        assertEquals("Podman Engine", engine.str("Name"))
        assertEquals("4.9.5", engine.getValue("Details").jsonObject.str("APIVersion"))
        val info = obj(LibpodApi.info(DockerApi.InfoFacts(3, 1, 2, "arm64", "5.10", 8, 8_000_000_000, null), LibpodApi.Version(5, 0, 0)))
        assertEquals("arm64", info.getValue("host").jsonObject.str("arch"))
        assertEquals("2", info.getValue("store").jsonObject.getValue("containerStore").jsonObject.str("stopped"))
        assertEquals("2", info.getValue("store").jsonObject.getValue("imageStore").jsonObject.str("number"))
    }
}
