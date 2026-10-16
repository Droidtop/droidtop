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
        assertEquals(DockerApi.Call.NotBuilt("POST /libpod/pods/create"), LibpodApi.route("POST", "/v5.2.5/libpod/pods/create", emptyMap()))
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

    @Test
    fun `the write routes are the same calls, with Podman's wait, rm, pull and logs read its way`() {
        fun post(path: String, query: Map<String, String> = emptyMap()) = LibpodApi.route("POST", "/v5.2.5/libpod$path", query)
        assertEquals(DockerApi.Call.Create(null), post("/containers/create"))
        assertEquals(DockerApi.Call.Start("web"), post("/containers/web/start"))
        assertEquals(DockerApi.Call.Stop("web"), post("/containers/web/stop", mapOf("ignore" to "false")))
        assertEquals(DockerApi.Call.Kill("web", "SIGKILL"), post("/containers/web/kill", mapOf("signal" to "SIGKILL")))
        assertEquals(DockerApi.Call.Wait("web", "not-running"), post("/containers/web/wait", mapOf("interval" to "250ms")))
        assertEquals(DockerApi.Call.Wait("web", "not-running"), post("/containers/web/wait", mapOf("condition" to "exited")))
        assertTrue(post("/containers/web/wait", mapOf("condition" to "healthy")) is DockerApi.Call.NotBuilt)
        assertEquals(
            DockerApi.Call.Remove("web", force = true, ignoreMissing = true),
            LibpodApi.route("DELETE", "/v5.2.5/libpod/containers/web", mapOf("force" to "true", "ignore" to "true", "depend" to "false", "volumes" to "false")),
        )
        assertEquals(DockerApi.Call.Pull("nginx", "missing"), post("/images/pull", mapOf("reference" to "nginx", "policy" to "missing", "arch" to "", "alltags" to "false")))
        assertTrue(post("/images/pull", mapOf("reference" to "nginx", "arch" to "arm")) is DockerApi.Call.NotBuilt)
        assertTrue(post("/images/pull", mapOf("reference" to "nginx", "policy" to "sometimes")) is DockerApi.Call.Refused)
        // Podman's client sends Go's zero time for no since or until, and -1 for every line.
        val zero = EngineInspect.ZERO_TIME
        assertEquals(
            DockerApi.Call.Logs("web", EngineCli.MAX_TAIL, stdout = true, stderr = true),
            LibpodApi.route("GET", "/v5.2.5/libpod/containers/web/logs", mapOf("since" to zero, "until" to zero, "tail" to "-1", "stdout" to "true", "stderr" to "true", "follow" to "false", "timestamps" to "false")),
        )
        assertEquals(DockerApi.Call.ExecCreate("web"), post("/containers/web/exec"))
        assertEquals(DockerApi.Call.ExecStart("e1"), post("/exec/e1/start"))
        assertEquals(DockerApi.Call.ExecRemove("e1"), post("/exec/e1/remove"))
    }

    @Test
    fun `a create body is Podman's own, read into the one run request`() {
        val network = Network(ContainerNetworks.newId(), "demo_default", 1L)
        // What podman create --name y -e A=B -l k=v --restart on-failure:3 -v Music:... -p 8080:8080 --pod demo
        // --network demo_default nginx echo hi sends (Podman 5.2.5), trimmed of the fields droidtop leaves alone.
        val body = """{"name":"y","pod":"demo","command":["echo","hi"],"env":{"A":"B"},"terminal":false,"stdin":false,""" +
            """"labels":{"k":"v"},"stop_timeout":10,"restart_policy":"on-failure","restart_tries":3,"systemd":"true","remove":false,""" +
            """"image":"nginx","raw_image_name":"nginx","volumes":[{"Name":"Music","Dest":"/run/droidtop-mounts/Music","Options":null,"IsAnonymous":false,"SubPath":""}],""" +
            """"umask":"0022","netns":{"nsmode":"bridge"},"portmappings":[{"host_ip":"","container_port":8080,"host_port":8080,"range":1,"protocol":""}],""" +
            """"Networks":{"demo_default":{"interface_name":""}},"idmappings":{"HostUIDMapping":true}}"""
        val (run, refused) = LibpodApi.createRequest(body, null, listOf(network))
        assertNull(refused)
        run!!
        assertEquals("nginx", run.image)
        assertEquals("y", run.name)
        assertEquals(listOf("echo", "hi"), run.args)
        assertEquals(listOf("A=B"), run.env)
        assertEquals(mapOf("k" to "v"), run.labels)
        assertEquals(ContainerProgram.RestartPolicy.OnFailure(3), run.restart)
        assertEquals(listOf("Music"), run.volumes)
        assertEquals(listOf(ExposedPort(8080)), run.publish)
        assertEquals("demo", run.pod)
        assertEquals(listOf(network.id), run.networks.map { it.networkId })
        assertEquals(false, run.start)

        fun answer(json: String) = LibpodApi.createRequest(json, null, listOf(network)).let { (r, a) -> assertNull(r); a!!.status to a.body.toString(Charsets.UTF_8) }
        val image = "\"image\":\"nginx\""
        assertEquals(501, answer("{$image,\"work_dir\":\"/srv\"}").first)
        assertEquals(501, answer("{$image,\"mounts\":[{\"destination\":\"/x\",\"type\":\"bind\",\"source\":\"/etc\"}]}").first)
        assertTrue(answer("{$image,\"resource_limits\":{\"memory\":{\"limit\":1}}}").second.contains("--memory"))
        assertEquals(501, answer("{$image,\"portmappings\":[{\"container_port\":80,\"host_port\":8080}]}").first)
        assertEquals(501, answer("{$image,\"terminal\":true}").first)
        assertEquals(501, answer("{$image,\"netns\":{\"nsmode\":\"none\"}}").first)
        assertEquals(404, answer("{$image,\"Networks\":{\"nope\":{}}}").first)
        assertEquals(409, answer("{$image,\"remove\":true,\"restart_policy\":\"always\"}").first)
        assertEquals(400, answer("{$image,\"privileged\":true}").first)
        assertEquals(400, answer("{$image,\"volumes\":[{\"Name\":\"\",\"Dest\":\"/data\",\"IsAnonymous\":true}]}").first)
        assertEquals(true, LibpodApi.createRequest("{$image,\"remove\":true,\"user\":\"root\",\"Networks\":{\"podman\":{}}}", null, emptyList()).first?.autoRemove)
    }

    @Test
    fun `pull, rm, wait and a missing thing answer as Podman's client reads them`() {
        val lines = LibpodApi.pulled("alpine", digest).trim().lines().map { obj(it) }
        assertEquals("ab".repeat(32), lines.last().str("id"))
        assertEquals("ab".repeat(32), lines.last().getValue("images").jsonArray.single().jsonPrimitive.content)
        assertEquals("no", obj(LibpodApi.pullFailed("no")).str("error"))
        assertEquals("web", list(LibpodApi.removed(listOf("web"))).single().jsonObject.str("Id"))
        assertEquals("[]", LibpodApi.removed(emptyList()).trim())
        assertEquals("3", LibpodApi.waited(3).trim())
        // Podman's client knows a missing container by this text (errorhandling.Contains with define.ErrNoSuchCtr).
        val missing = LibpodApi.missing(DockerApi.Kind.CONTAINER, "nope")
        assertEquals(404, missing.status)
        assertTrue(obj(missing.body.toString(Charsets.UTF_8)).str("message").contains("no such container"))
        assertTrue(obj(LibpodApi.missing(DockerApi.Kind.POD, "p").body.toString(Charsets.UTF_8)).str("message").contains("no such pod"))
    }
}
