package dev.droidtop.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The Docker Engine API droidtop serves on `docker.sock` (docs/SPEC.md 3d, "The Docker Engine API";
 * Droidtop/tracker#555 D14), so `docker` clients that talk to a daemon (the real docker CLI with DOCKER_HOST,
 * `docker compose`, lazydocker, an IDE) reach droidtop's one engine. A subset of API v1.43, read from the same facts
 * the command fronts answer from; a route droidtop has not built answers 501 with the reason, never a guess. Pure:
 * the request handler gathers the facts and does what a call asks.
 */
object DockerApi {
    const val API_VERSION = "1.43"
    const val MIN_API_VERSION = "1.24"

    /**
     * The engine version reported to clients: Docker 24.0, the release whose API is [API_VERSION]. Clients compare it
     * to decide what to send; the Platform name says the engine is droidtop's.
     */
    const val ENGINE_VERSION = "24.0.0"

    /** The socket's file name in droidtop's app storage, which every container sees at [ContainerLayout.APP_STORAGE_DIR]. */
    const val SOCKET_NAME = "docker.sock"

    /** DOCKER_HOST as the desktop's environment gives it. */
    const val DOCKER_HOST = "unix://${ContainerLayout.APP_STORAGE_DIR}/$SOCKET_NAME"

    /** What one request asks for. */
    sealed interface Call {
        data object Ping : Call
        data object Version : Call
        data object Info : Call
        data class Containers(val all: Boolean) : Call
        data class Container(val id: String) : Call
        data object Images : Call

        /** A route Docker has and droidtop has not built: answered 501 with [what]. */
        data class NotBuilt(val what: String) : Call

        data object NotFound : Call
    }

    private val VERSION_PREFIX = Regex("^/v[0-9]+\\.[0-9]+(?=/)")

    /** The call [method] and [path] (with [query]) ask for. Pure. */
    fun route(method: String, path: String, query: Map<String, String>): Call {
        val p = path.replaceFirst(VERSION_PREFIX, "").trimEnd('/').ifEmpty { "/" }
        val segments = p.removePrefix("/").split('/')
        val read = method == "GET" || method == "HEAD"
        return when {
            p == "/_ping" && read -> Call.Ping
            p == "/version" && read -> Call.Version
            p == "/info" && read -> Call.Info
            p == "/containers/json" && read -> Call.Containers(all = query["all"].let { it == "1" || it == "true" })
            segments.size == 3 && segments[0] == "containers" && segments[2] == "json" && read -> Call.Container(segments[1])
            p == "/images/json" && read -> Call.Images
            segments.firstOrNull() in setOf("containers", "images", "exec", "volumes", "networks", "build", "events", "system", "plugins", "swarm", "nodes", "services", "tasks", "secrets", "configs", "distribution", "session", "auth", "commit") ->
                Call.NotBuilt("$method $p")
            else -> Call.NotFound
        }
    }

    /** The headers every answer carries, as Docker's daemon sends them. */
    val headers: Map<String, String> = mapOf(
        "Api-Version" to API_VERSION,
        "Docker-Experimental" to "false",
        "Ostype" to "linux",
        "Server" to "droidtop/$ENGINE_VERSION",
    )

    /** An error in Docker's shape, `{"message": ...}`. */
    fun error(status: Int, message: String): EngineHttp.Response =
        EngineHttp.Response.json(status, JsonObject(mapOf("message" to JsonPrimitive(message))).toString() + "\n")

    fun notBuilt(what: String): EngineHttp.Response =
        error(501, "$what is not built in droidtop's engine yet (docs/SPEC.md 3d, \"The Docker Engine API\")")

    /** Docker's name for a CPU architecture: `aarch64` reads as `arm64`, `x86_64` as `amd64`. */
    fun arch(osArch: String): String = when (osArch) {
        "aarch64", "arm64" -> "arm64"
        "x86_64", "amd64" -> "amd64"
        else -> osArch
    }

    fun version(arch: String, kernel: String): String = buildJsonObject {
        putJsonObject("Platform") { put("Name", "droidtop's container engine") }
        putJsonArray("Components") {
            add(
                buildJsonObject {
                    put("Name", "Engine")
                    put("Version", ENGINE_VERSION)
                    putJsonObject("Details") {
                        put("ApiVersion", API_VERSION)
                        put("MinAPIVersion", MIN_API_VERSION)
                        put("Os", "linux")
                        put("Arch", arch)
                        put("KernelVersion", kernel)
                    }
                },
            )
        }
        put("Version", ENGINE_VERSION)
        put("ApiVersion", API_VERSION)
        put("MinAPIVersion", MIN_API_VERSION)
        put("Os", "linux")
        put("Arch", arch)
        put("KernelVersion", kernel)
    }.toString() + "\n"

    /** What `info` reports, gathered by the handler. */
    data class InfoFacts(
        val containers: Int,
        val running: Int,
        val images: Int,
        val arch: String,
        val kernel: String,
        val cpus: Int,
        val memoryBytes: Long,
        val features: EngineFeatures?,
    )

    fun info(f: InfoFacts): String = buildJsonObject {
        put("ID", "droidtop")
        put("Containers", f.containers)
        put("ContainersRunning", f.running)
        put("ContainersPaused", 0)
        put("ContainersStopped", f.containers - f.running)
        put("Images", f.images)
        put("Driver", "droidtop")
        put("Name", "droidtop")
        put("ServerVersion", ENGINE_VERSION)
        put("OperatingSystem", "droidtop's container engine (Android)")
        put("OSType", "linux")
        put("Architecture", f.arch)
        put("KernelVersion", f.kernel)
        put("NCPU", f.cpus)
        put("MemTotal", f.memoryBytes)
        val cgroups = f.features?.usable(EngineFeature.CGROUPS) == true
        put("MemoryLimit", cgroups)
        put("CpuCfsQuota", cgroups)
        put("CgroupVersion", if (f.features?.state(EngineFeature.CGROUPS)?.kernel == true) "2" else "")
        putJsonArray("Warnings") { f.features?.let { EngineOptions.describe(it) }?.forEach { add(JsonPrimitive(it.trim())) } }
    }.toString() + "\n"

    /** One row of `/containers/json`: what `ps` knows, with its STATUS words. */
    data class Summary(val facts: EngineInspect.Facts, val statusText: String)

    /** `/containers/json`: running ones, or all with [all]. */
    fun containers(rows: List<Summary>, all: Boolean): String = JsonArray(
        rows.filter { all || it.facts.status == "running" || it.facts.status == "restarting" }.map { summary(it) },
    ).toString() + "\n"

    fun summary(row: Summary): JsonObject {
        val f = row.facts
        return buildJsonObject {
            put("Id", f.id)
            putJsonArray("Names") { add(JsonPrimitive("/${f.name}")) }
            put("Image", f.image)
            put("ImageID", f.imageDigest ?: "")
            put("Command", f.config?.program()?.joinToString(" ") ?: "")
            put("Created", (f.createdMs ?: 0L) / 1000)
            // Host networking: Docker lists no published ports for a container on the host's network.
            put("Ports", JsonArray(emptyList()))
            put("Labels", JsonObject(emptyMap()))
            put("State", f.status)
            put("Status", row.statusText)
            putJsonObject("HostConfig") { put("NetworkMode", "host") }
            putJsonObject("NetworkSettings") { put("Networks", JsonObject(emptyMap())) }
            putJsonArray("Mounts") {
                f.folders.forEach { folder ->
                    add(
                        buildJsonObject {
                            put("Type", "bind")
                            put("Name", folder)
                            put("Destination", "${ContainerLayout.EXTRA_MOUNTS_DIR}/$folder")
                            put("RW", true)
                        },
                    )
                }
            }
        }
    }

    /** `/containers/{id}/json`: [EngineInspect]'s one object, as the docker front prints it. */
    fun container(facts: EngineInspect.Facts): String =
        Json.encodeToString(JsonObject.serializer(), EngineInspect.container(facts, EngineCli.Front.DOCKER)) + "\n"

    /** `/images/json`: the image store's entries. */
    fun images(entries: List<OciImageStore.Entry>): String = JsonArray(
        entries.map { e ->
            buildJsonObject {
                put("Id", e.digest)
                put("ParentId", "")
                putJsonArray("RepoTags") { add(JsonPrimitive(EngineCli.PODMAN.fullImage(e.reference).removePrefix("docker.io/library/").removePrefix("docker.io/"))) }
                put("RepoDigests", buildJsonArray { })
                put("Created", 0)
                put("Size", e.sizeBytes)
                put("SharedSize", -1)
                put("Labels", JsonObject(emptyMap()))
                put("Containers", -1)
            }
        },
    ).toString() + "\n"
}
