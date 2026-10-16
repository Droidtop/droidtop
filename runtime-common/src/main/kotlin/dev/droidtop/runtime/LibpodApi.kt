package dev.droidtop.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The libpod API droidtop serves on `podman.sock` (docs/SPEC.md 3d, "The libpod API"; Droidtop/tracker#555 D15), so a
 * podman a container installed (remote whenever CONTAINER_HOST is set, as Podman's own clients are) reaches droidtop's
 * one engine. Its paths are `/v{X.Y.Z}/libpod/...`; every other path on the socket is Docker's API, as on Podman's own
 * service. A request is read into the same [DockerApi.Call]s the Docker API asks for and answered from the same facts
 * and actions; only the JSON is Podman's. Pure.
 */
object LibpodApi {
    /** The socket's file name in droidtop's app storage, beside [DockerApi.SOCKET_NAME]. */
    const val SOCKET_NAME = "podman.sock"

    /** CONTAINER_HOST as the desktop's environment gives it. */
    const val CONTAINER_HOST = "unix://${ContainerLayout.APP_STORAGE_DIR}/$SOCKET_NAME"

    /** A libpod API version, as a client's path names it. */
    data class Version(val major: Int, val minor: Int, val patch: Int) {
        override fun toString() = "$major.$minor.$patch"
    }

    /** The oldest libpod API Podman's clients take (Podman 4.0), and the newest whose shapes droidtop writes. */
    val OLDEST = Version(4, 0, 0)
    val NEWEST = Version(5, 0, 0)

    private val PREFIX = Regex("^(?:/v([0-9]{1,3})\\.([0-9]{1,3})(?:\\.([0-9]{1,3}))?)?/libpod(?=/|$)")

    /**
     * The version droidtop answers a libpod [path] in, or null for a path that is not libpod's. A client names its
     * own version in every path and reads the answers in that version's shapes (Podman 5 changed some: an inspect's
     * Entrypoint is a list there, a string in 4), so droidtop answers in the version asked, from [OLDEST] to [NEWEST].
     */
    fun version(path: String): Version? {
        val m = PREFIX.find(path) ?: return null
        val asked = m.groupValues[1].toIntOrNull()?.let { Version(it, m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 0) } ?: NEWEST
        return when {
            asked.major < OLDEST.major -> OLDEST
            asked.major > NEWEST.major -> NEWEST
            else -> asked
        }
    }

    /** The call a libpod [method] and [path] (with [query]) ask for. Pure. */
    fun route(method: String, path: String, query: Map<String, String>): DockerApi.Call {
        val p = path.replaceFirst(PREFIX, "").trimEnd('/').ifEmpty { "/" }
        val segments = p.removePrefix("/").split('/')
        val read = method == "GET" || method == "HEAD"
        fun filtered(make: (Map<String, List<String>>) -> DockerApi.Call): DockerApi.Call =
            DockerApi.filters(query["filters"])?.let(make) ?: DockerApi.Call.Refused(400, "filters is not the JSON object Podman sends")
        // An image's name may hold slashes (`docker.io/library/nginx`), so its routes are read from both ends.
        fun image(suffix: String): String? = p.takeIf { it.startsWith("/images/") && it.endsWith("/$suffix") && it.length > "/images//$suffix".length }
            ?.removePrefix("/images/")?.removeSuffix("/$suffix")
        return when {
            p == "/_ping" && read -> DockerApi.Call.Ping
            p == "/version" && read -> DockerApi.Call.Version
            p == "/info" && read -> DockerApi.Call.Info
            p == "/containers/json" && read -> filtered { DockerApi.Call.Containers(all = flag(query["all"]), filters = it) }
            segments.size == 3 && segments[0] == "containers" && segments[2] == "json" && read -> DockerApi.Call.Container(segments[1])
            segments.size == 3 && segments[0] == "containers" && segments[2] == "exists" && read -> DockerApi.Call.Exists(DockerApi.Kind.CONTAINER, segments[1])
            p == "/images/json" && read -> DockerApi.Call.Images
            image("json") != null && read -> DockerApi.Call.ImageGet(image("json")!!)
            image("exists") != null && read -> DockerApi.Call.Exists(DockerApi.Kind.IMAGE, image("exists")!!)
            p == "/pods/json" && read -> filtered { DockerApi.Call.Pods(it) }
            segments.size == 3 && segments[0] == "pods" && segments[2] == "json" && read -> DockerApi.Call.PodGet(segments[1])
            segments.size == 3 && segments[0] == "pods" && segments[2] == "exists" && read -> DockerApi.Call.Exists(DockerApi.Kind.POD, segments[1])
            p == "/networks/json" && read -> filtered { DockerApi.Call.Networks(it) }
            segments.size == 3 && segments[0] == "networks" && segments[2] == "json" && read -> DockerApi.Call.NetworkGet(segments[1])
            segments.size == 3 && segments[0] == "networks" && segments[2] == "exists" && read -> DockerApi.Call.Exists(DockerApi.Kind.NETWORK, segments[1])
            segments.firstOrNull() in setOf(
                "containers", "images", "pods", "networks", "volumes", "exec", "events", "system", "manifests", "secrets",
                "play", "generate", "kube", "build", "quadlets", "artifacts",
            ) -> DockerApi.Call.NotBuilt("$method /libpod$p")
            else -> DockerApi.Call.NotFound
        }
    }

    private fun flag(value: String?): Boolean = value == "1" || value == "true" || value == "True"

    /** Pod filters droidtop applies: name and id. Any other is said, never ignored. */
    fun unknownPodFilters(filters: Map<String, List<String>>): Set<String> = filters.keys - setOf("name", "id")

    fun notBuilt(what: String): EngineHttp.Response =
        DockerApi.error(501, "$what is not built in droidtop's engine yet (docs/SPEC.md 3d, \"The libpod API\")")

    /** The headers every libpod answer carries, as Podman's service sends them: its client reads the version on ping. */
    fun headers(version: Version): Map<String, String> = mapOf(
        "Api-Version" to DockerApi.API_VERSION,
        "Libpod-Api-Version" to version.toString(),
        "Server" to "Libpod/$version (droidtop)",
    )

    /** An image id as Podman writes it: the digest's hex, without `sha256:`. */
    private fun imageId(digest: String?): String = digest?.removePrefix("sha256:").orEmpty()

    private fun seconds(ms: Long?): Long = (ms ?: 0L) / 1000

    private fun strings(values: List<String>?): JsonElement =
        if (values == null) JsonNull else JsonArray(values.map { JsonPrimitive(it) })

    /** `GET /libpod/version`: Docker's version shape with a Podman Engine component, whose APIVersion the client reads. */
    fun version(v: Version, arch: String, kernel: String): String = buildJsonObject {
        putJsonObject("Platform") { put("Name", "droidtop's container engine") }
        putJsonArray("Components") {
            add(
                buildJsonObject {
                    put("Name", "Podman Engine")
                    put("Version", v.toString())
                    putJsonObject("Details") {
                        put("APIVersion", v.toString())
                        put("Arch", arch)
                        put("KernelVersion", kernel)
                        put("MinAPIVersion", OLDEST.toString())
                        put("Os", "linux")
                    }
                },
            )
        }
        put("Version", v.toString())
        put("ApiVersion", v.toString())
        put("MinAPIVersion", OLDEST.toString())
        put("Os", "linux")
        put("Arch", arch)
        put("KernelVersion", kernel)
    }.toString() + "\n"

    /**
     * `GET /libpod/info`, which `podman info` prints whole: the host, the counts and the API version. Each kernel
     * feature (D10) is a line under the host's runtime info, the one place Podman's info carries free text.
     */
    fun info(f: DockerApi.InfoFacts, v: Version): String = buildJsonObject {
        putJsonObject("host") {
            put("arch", f.arch)
            put("cpus", f.cpus)
            put("kernel", f.kernel)
            put("memTotal", f.memoryBytes)
            put("os", "linux")
            put("hostname", "droidtop")
            put("cgroupVersion", if (f.features?.state(EngineFeature.CGROUPS)?.kernel == true) "v2" else "")
            putJsonObject("distribution") { put("distribution", "android") }
            put("networkBackend", "droidtop")
            putJsonObject("runtimeInfo") {
                putJsonObject("droidtop") {
                    putJsonArray("features") { f.features?.let { EngineOptions.describe(it) }?.forEach { add(JsonPrimitive(it.trim())) } }
                }
            }
        }
        putJsonObject("store") {
            put("graphDriverName", "droidtop")
            putJsonObject("containerStore") {
                put("number", f.containers)
                put("paused", 0)
                put("running", f.running)
                put("stopped", f.containers - f.running)
            }
            putJsonObject("imageStore") { put("number", f.images) }
        }
        putJsonObject("registries") { }
        putJsonObject("plugins") { }
        putJsonObject("version") {
            put("APIVersion", v.toString())
            put("Version", v.toString())
            put("Os", "linux")
            put("OsArch", "linux/${f.arch}")
        }
    }.toString() + "\n"

    /**
     * `GET /libpod/containers/json`: running ones, or all with [all], as Podman lists them. Podman's `ps` writes STATUS
     * itself from State, StartedAt, ExitedAt and ExitCode; its Status is a health check's, which droidtop has none of.
     */
    fun containers(rows: List<DockerApi.Summary>, all: Boolean, filters: Map<String, List<String>>): String = JsonArray(
        rows.filter { (all || it.facts.status == "running" || it.facts.status == "restarting") && DockerApi.matches(it, filters) }
            .map { container(it.facts) },
    ).toString() + "\n"

    private fun container(f: EngineInspect.Facts): JsonObject = buildJsonObject {
        put("AutoRemove", false)
        put("Command", strings(f.config?.program()))
        put("Created", EngineInspect.time(f.createdMs))
        put("CreatedAt", "")
        put("Exited", f.status == "exited")
        put("ExitedAt", seconds(f.endedMs))
        put("ExitCode", f.exitCode ?: 0)
        put("Id", f.id)
        put("Image", EngineCli.PODMAN.fullImage(f.image))
        put("ImageID", imageId(f.imageDigest))
        put("IsInfra", false)
        putJsonObject("Labels") { f.labels.forEach { (k, v) -> put(k, v) } }
        putJsonArray("Mounts") { f.folders.forEach { add(JsonPrimitive("${ContainerLayout.EXTRA_MOUNTS_DIR}/$it")) } }
        putJsonArray("Names") { add(JsonPrimitive(f.name)) }
        putJsonObject("Namespaces") { }
        putJsonArray("Networks") { }
        put("Pid", 0)
        put("Pod", f.podId ?: "")
        put("PodName", f.podName ?: "")
        putJsonArray("Ports") { }
        put("Restarts", 0)
        put("StartedAt", seconds(f.startedMs))
        put("State", f.status)
        put("Status", "")
    }

    /**
     * `GET /libpod/containers/{id}/json`: [EngineInspect]'s one object as the podman front prints it, with the two
     * things libpod writes otherwise: Image is the image's id, and before Podman 5 Config.Entrypoint is one string.
     */
    fun inspect(f: EngineInspect.Facts, v: Version): String {
        val base = EngineInspect.container(f, EngineCli.Front.PODMAN)
        val config = base["Config"] as JsonObject
        val entrypoint: JsonElement = if (v.major >= 5) config["Entrypoint"] ?: JsonNull else JsonPrimitive(f.config?.entrypoint.orEmpty().joinToString(" "))
        return JsonObject(
            base + mapOf(
                "Image" to JsonPrimitive(imageId(f.imageDigest)),
                "ImageDigest" to JsonPrimitive(f.imageDigest ?: ""),
                "Config" to JsonObject(config + ("Entrypoint" to entrypoint)),
            ),
        ).toString() + "\n"
    }

    /** `GET /libpod/images/json`: the image store's entries, named as Podman names them. */
    fun images(entries: List<OciImageStore.Entry>): String = JsonArray(
        entries.map { e ->
            val name = EngineCli.PODMAN.fullImage(e.reference)
            buildJsonObject {
                put("Id", imageId(e.digest))
                put("ParentId", "")
                putJsonArray("RepoTags") { add(JsonPrimitive(name)) }
                putJsonArray("RepoDigests") { add(JsonPrimitive("${EngineCli.PODMAN.fullRepository(e.reference)}@${e.digest}")) }
                put("Created", 0)
                put("Size", e.sizeBytes)
                put("SharedSize", 0)
                put("VirtualSize", e.sizeBytes)
                putJsonObject("Labels") { }
                put("Containers", 0)
                put("Digest", e.digest)
                putJsonArray("Names") { add(JsonPrimitive(name)) }
            }
        },
    ).toString() + "\n"

    /** `GET /libpod/images/{name}/json`: what droidtop knows of a stored image, in Podman's shape. */
    fun image(e: OciImageStore.Entry, config: ImageRunConfig?, arch: String): String = buildJsonObject {
        val name = EngineCli.PODMAN.fullImage(e.reference)
        put("Id", imageId(e.digest))
        put("Digest", e.digest)
        putJsonArray("RepoTags") { add(JsonPrimitive(name)) }
        putJsonArray("RepoDigests") { add(JsonPrimitive("${EngineCli.PODMAN.fullRepository(e.reference)}@${e.digest}")) }
        put("Parent", "")
        putJsonObject("Config") {
            put("Env", strings(config?.env))
            put("Cmd", strings(config?.cmd))
            put("Entrypoint", strings(config?.entrypoint))
            put("WorkingDir", config?.workingDir ?: "")
            put("User", config?.user ?: "")
            putJsonObject("ExposedPorts") { config?.ports?.forEach { put(it.toString(), JsonObject(emptyMap())) } }
        }
        put("Architecture", arch)
        put("Os", "linux")
        put("Size", e.sizeBytes)
        put("VirtualSize", e.sizeBytes)
        putJsonObject("RootFS") { put("Type", "layers") }
        putJsonArray("NamesHistory") { add(JsonPrimitive(name)) }
    }.toString() + "\n"

    /** One pod with what droidtop knows of its containers that still exist. */
    data class PodFacts(val pod: Pod, val containers: List<EngineInspect.Facts>) {
        val status: String get() = ContainerPods.status(containers.size, containers.count { it.status == "running" })
    }

    /** `GET /libpod/pods/json`: [pods] that pass [filters] (name, id). */
    fun pods(pods: List<PodFacts>, filters: Map<String, List<String>>): String = JsonArray(
        pods.filter { podMatches(it.pod, filters) }.map { p ->
            buildJsonObject {
                put("Cgroup", "")
                putJsonArray("Containers") {
                    p.containers.forEach { c ->
                        add(
                            buildJsonObject {
                                put("Id", c.id)
                                put("Names", c.name)
                                put("Status", c.status)
                                put("RestartCount", 0)
                            },
                        )
                    }
                }
                put("Created", EngineInspect.time(p.pod.createdMs))
                put("Id", p.pod.id)
                put("InfraId", "")
                put("Name", p.pod.name)
                put("Namespace", "")
                putJsonArray("Networks") { }
                put("Status", p.status)
                putJsonObject("Labels") { }
            }
        },
    ).toString() + "\n"

    fun podMatches(pod: Pod, filters: Map<String, List<String>>): Boolean = filters.all { (key, wanted) ->
        when (key) {
            "name" -> wanted.any { pod.name.contains(it) }
            "id" -> wanted.any { pod.id.startsWith(it) }
            else -> true
        }
    }

    /** `GET /libpod/pods/{id}/json`: one pod; a pod here has no infra container and no namespaces of its own. */
    fun pod(p: PodFacts): String = buildJsonObject {
        put("Id", p.pod.id)
        put("Name", p.pod.name)
        put("Created", EngineInspect.time(p.pod.createdMs))
        put("State", p.status)
        put("Hostname", "")
        put("CreateCgroup", false)
        put("CreateInfra", false)
        put("NumContainers", p.containers.size)
        putJsonArray("Containers") {
            p.containers.forEach { c ->
                add(
                    buildJsonObject {
                        put("Id", c.id)
                        put("Name", c.name)
                        put("State", c.status)
                    },
                )
            }
        }
        put("LockNumber", 0)
    }.toString() + "\n"

    /** A network as Podman describes one (netavark's shape); [names] names its members by container id. */
    private fun network(n: Network, names: Map<String, String>): JsonObject = buildJsonObject {
        put("name", n.name)
        put("id", n.id)
        put("driver", when (n.name) { "host" -> "host"; "none" -> "null"; else -> "bridge" })
        put("created", EngineInspect.time(n.createdMs.takeIf { it > 0 }))
        put("ipv6_enabled", false)
        put("internal", false)
        put("dns_enabled", false)
        putJsonObject("labels") { n.labels.forEach { (k, v) -> put(k, v) } }
        putJsonObject("options") { }
        putJsonObject("ipam_options") { }
        putJsonObject("containers") { n.members.forEach { id -> putJsonObject(id) { put("name", names[id] ?: id) } } }
    }

    /** `GET /libpod/networks/json`: [all] that pass [filters] (name, id, label). */
    fun networks(all: List<Network>, names: Map<String, String>, filters: Map<String, List<String>>): String =
        JsonArray(all.filter { DockerApi.networkMatches(it, filters) }.map { network(it, names) }).toString() + "\n"

    /** `GET /libpod/networks/{id}/json`. */
    fun networkJson(n: Network, names: Map<String, String>): String = network(n, names).toString() + "\n"
}
