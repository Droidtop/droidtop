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
            // A create body is Podman's own (a SpecGenerator), read by [createRequest]; its name is in the body.
            p == "/containers/create" && method == "POST" -> DockerApi.Call.Create(null)
            segments.size == 3 && segments[0] == "containers" && segments[2] == "wait" && method == "POST" -> wait(segments[1], query)
            segments.size == 2 && segments[0] == "containers" && method == "DELETE" -> when {
                flag(query["depend"]) -> DockerApi.Call.NotBuilt("rm --depend")
                else -> DockerApi.Call.Remove(segments[1], force = flag(query["force"]), ignoreMissing = flag(query["ignore"]))
            }
            p == "/images/pull" && method == "POST" -> pull(query)
            segments.size == 3 && segments[0] == "exec" && segments[2] == "remove" && method == "POST" -> DockerApi.Call.ExecRemove(segments[1])
            p == "/pods/create" && method == "POST" -> DockerApi.Call.PodCreate
            segments.size == 3 && segments[0] == "pods" && method == "POST" && segments[2] in setOf("start", "stop", "restart", "kill") -> when {
                segments[2] == "kill" && !killable(query["signal"]) -> DockerApi.Call.NotBuilt("signal ${query["signal"]} (a container's program is ended, not signalled)")
                else -> DockerApi.Call.PodAction(segments[1], segments[2], query["signal"])
            }
            segments.size == 2 && segments[0] == "pods" && method == "DELETE" -> DockerApi.Call.PodRemove(segments[1], flag(query["force"]))
            p == "/networks/create" && method == "POST" -> DockerApi.Call.NetworkCreate(ignoreIfExists = flag(query["ignoreIfExists"]))
            segments.size == 2 && segments[0] == "networks" && method == "DELETE" -> when {
                flag(query["force"]) -> DockerApi.Call.NotBuilt("network rm --force (it takes the network's containers with it)")
                else -> DockerApi.Call.NetworkRemove(segments[1])
            }
            segments.size == 3 && segments[0] == "networks" && method == "POST" && segments[2] in setOf("connect", "disconnect") ->
                DockerApi.Call.NetworkConnect(segments[1], segments[2] == "connect")
            // Podman's client sends Go's zero time for no since or until, and -1 for every line.
            segments.size == 3 && segments[0] == "containers" && segments[2] == "logs" && read -> DockerApi.route(
                method,
                p,
                query.filterNot { (k, v) -> k in setOf("since", "until") && v == EngineInspect.ZERO_TIME } +
                    (query["tail"]?.takeIf { it == "-1" }?.let { mapOf("tail" to "all") } ?: emptyMap()),
            )
            // The actions whose paths, queries and answers are the same in both APIs are Docker's.
            (segments.size == 3 && segments[0] == "containers" && segments[2] in setOf("start", "stop", "restart", "kill", "attach", "exec")) ||
                (segments.size == 3 && segments[0] == "exec" && segments[2] in setOf("start", "json")) -> DockerApi.route(method, p, query)
            segments.firstOrNull() in setOf(
                "containers", "images", "pods", "networks", "volumes", "exec", "events", "system", "manifests", "secrets",
                "play", "generate", "kube", "build", "quadlets", "artifacts",
            ) -> DockerApi.Call.NotBuilt("$method /libpod$p")
            else -> DockerApi.Call.NotFound
        }
    }

    private fun flag(value: String?): Boolean = value == "1" || value == "true" || value == "True"

    /** A signal that ends a program, which is all droidtop does to one; none given is Podman's KILL. */
    private fun killable(signal: String?): Boolean =
        signal.isNullOrEmpty() || signal.removePrefix("SIG") in setOf("KILL", "TERM", "INT") || signal in setOf("9", "15", "2")

    /** libpod's wait: until the program is over (its conditions stopped and exited), as Docker's not-running. */
    private fun wait(id: String, query: Map<String, String>): DockerApi.Call {
        val condition = query["condition"].orEmpty()
        return if (condition.isEmpty() || condition in setOf("stopped", "exited")) DockerApi.Call.Wait(id, "not-running")
        else DockerApi.Call.NotBuilt("wait --condition $condition")
    }

    private fun pull(query: Map<String, String>): DockerApi.Call {
        val reference = query["reference"].orEmpty()
        val policy = query["policy"].orEmpty().ifEmpty { "always" }
        return when {
            reference.isEmpty() || !EngineCli.PODMAN.validReference(reference) -> DockerApi.Call.Refused(400, "not an image reference: $reference")
            policy !in setOf("always", "missing", "newer", "never") -> DockerApi.Call.Refused(400, "pull policy $policy is not always, missing, newer or never")
            flag(query["allTags"]) || flag(query["alltags"]) -> DockerApi.Call.NotBuilt("pull --all-tags")
            listOf("arch", "os", "variant").any { !query[it].isNullOrEmpty() } -> DockerApi.Call.NotBuilt("pull for another platform (--arch, --os, --variant)")
            else -> DockerApi.Call.Pull(reference, policy)
        }
    }

    /** Pod filters droidtop applies: name, id and label. Any other is said, never ignored. */
    fun unknownPodFilters(filters: Map<String, List<String>>): Set<String> = filters.keys - setOf("name", "id", "label")

    /**
     * 404 for [ref] in Podman's words. Podman's client tells a missing container, pod or exec from another failure by
     * the message holding its error's text ("no such container"), so `podman rm --ignore` passes over one.
     */
    fun missing(kind: DockerApi.Kind, ref: String): EngineHttp.Response = when (kind) {
        DockerApi.Kind.CONTAINER -> error(404, "no container with name or ID \"$ref\" found: no such container", "no such container")
        DockerApi.Kind.POD -> error(404, "no pod with name or ID $ref found: no such pod", "no such pod")
        DockerApi.Kind.IMAGE -> error(404, "$ref: image not known", "image not known")
        DockerApi.Kind.NETWORK -> error(404, "unable to find network with name or ID $ref: network not found", "network not found")
    }

    /** An error in Podman's shape: the message, its cause and the status. */
    fun error(status: Int, message: String, cause: String): EngineHttp.Response = EngineHttp.Response.json(
        status,
        JsonObject(mapOf("cause" to JsonPrimitive(cause), "message" to JsonPrimitive(message), "response" to JsonPrimitive(status))).toString() + "\n",
    )

    fun notBuilt(what: String): EngineHttp.Response =
        DockerApi.error(501, "$what is not built in droidtop's engine yet (docs/SPEC.md 3d, \"The libpod API\")")

    /**
     * A create body (`POST /libpod/containers/create`, Podman's SpecGenerator) read into the one request model, or the
     * answer it gets when it asks for what droidtop does not do. As with Docker's create, nothing that changes what a
     * container is is dropped: it is taken, answered from the D10 feature table, or answered as not built. Fields
     * Podman's client always sends with what droidtop does anyway (its id mappings, umask, stop timeout, ...) are
     * left alone. Pure.
     */
    fun createRequest(body: String, features: EngineFeatures?, networks: List<Network>): Pair<EngineRequest.Run?, EngineHttp.Response?> {
        fun refuse(status: Int, message: String) = null to DockerApi.error(status, message)
        fun notYet(what: String) = null to notBuilt(what)
        fun feature(option: String) = refuse(501, EngineOptions.refusal("podman create", option, features))
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body.ifBlank { "{}" }) as JsonObject }.getOrNull()
            ?: return refuse(400, "the body is not a JSON object")
        fun string(obj: JsonObject?, key: String): String? = (obj?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
        fun bool(key: String): Boolean = (o[key] as? JsonPrimitive)?.content == "true"
        fun list(key: String): List<JsonElement> = (o[key] as? JsonArray).orEmpty()
        fun strings(key: String): List<String> = list(key).mapNotNull { (it as? JsonPrimitive)?.content }
        fun map(key: String): Map<String, String> = (o[key] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }.orEmpty()
        fun given(key: String): Boolean = when (val v = o[key]) {
            null, JsonNull -> false
            is JsonArray -> v.isNotEmpty()
            is JsonObject -> v.isNotEmpty()
            is JsonPrimitive -> v.content.isNotEmpty() && v.content != "false" && v.content != "0"
        }

        val image = string(o, "image") ?: return refuse(400, "a container needs an image")
        if (!EngineCli.PODMAN.validReference(image)) return refuse(400, "not an image reference: $image")
        if (bool("privileged")) return refuse(400, "droidtop's containers never run privileged")
        if (strings("entrypoint").isNotEmpty()) return notYet("entrypoint (podman run --entrypoint)")
        string(o, "work_dir")?.let { return notYet("work_dir (podman run -w)") }
        string(o, "user")?.takeIf { it.substringBefore(':') !in setOf("root", "0") }?.let { return notYet("user (podman run -u)") }
        if (bool("terminal") || bool("stdin")) return notYet("a container with a terminal or open input (podman run -t, -i)")
        string(o, "hostname")?.let { return feature("--hostname") }
        if (bool("read_only_filesystem")) return feature("--read-only")
        (o["resource_limits"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { limits ->
            return when {
                "memory" in limits -> feature("--memory")
                "cpu" in limits -> feature("--cpus")
                "pids" in limits -> feature("--pids-limit")
                else -> notYet("resource_limits ${limits.keys.joinToString(", ")}")
            }
        }
        listOf(
            "mounts" to "mounts (bind mounts by path and tmpfs; -v takes a folder you mapped in Containers)",
            "image_volumes" to "image_volumes", "overlay_volumes" to "overlay_volumes", "devices" to "devices",
            "cap_add" to "cap_add", "secrets" to "secrets", "secret_env" to "secret_env", "dns_server" to "dns_server",
            "dns_search" to "dns_search", "hostadd" to "hostadd (--add-host)", "sysctl" to "sysctl", "healthconfig" to "healthconfig",
            "volumes_from" to "volumes_from", "dependencyContainers" to "requires",
        ).forEach { (key, what) -> if (given(key)) return notYet(what) }

        // Volumes: the name of a folder the person mapped, where -v puts it (docs/SPEC.md 3d "Home and folders").
        val volumes = list("volumes").map { v ->
            val volume = v as? JsonObject
            val options = (volume?.get("Options") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val spec = listOfNotNull(string(volume, "Name"), string(volume, "Dest")).joinToString(":") +
                options.singleOrNull()?.takeIf { it == "ro" || it == "rw" }?.let { ":$it" }.orEmpty()
            val folder = if (options.size <= 1 && options.all { it == "ro" || it == "rw" }) EngineCli.PODMAN.volume(spec) else null
            folder ?: return refuse(
                400,
                "-v takes the name of a folder you mapped in Containers (it appears at ${ContainerLayout.EXTRA_MOUNTS_DIR}/<name>); $spec is not one droidtop can mount",
            )
        }

        val joins = mutableListOf<EngineRequest.NetworkJoin>()
        val nsmode = string(o["netns"] as? JsonObject, "nsmode").orEmpty()
        when {
            nsmode == "none" -> return feature("--network")
            nsmode.startsWith("ns:") || nsmode == "path" || nsmode == "container" -> return notYet("--network $nsmode")
        }
        (o["Networks"] as? JsonObject)?.forEach { (ref, options) ->
            val aliases = ((options as? JsonObject)?.get("aliases") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            DockerApi.join(joins, networks, ref, aliases, features, "podman create", setOf("default", "host", "bridge", "podman"))?.let { return null to it }
        }

        val publish = mutableListOf<ExposedPort>()
        list("portmappings").forEach { m ->
            val mapping = m as? JsonObject
            val container = string(mapping, "container_port") ?: return refuse(400, "a port mapping needs a container_port")
            if ((string(mapping, "range")?.toIntOrNull() ?: 1) > 1) return notYet("a range of ports")
            val host = string(mapping, "host_port")?.takeIf { it != "0" }
            val address = string(mapping, "host_ip")
            (string(mapping, "protocol") ?: ExposedPort.TCP).split(',').forEach { protocol ->
                val spec = listOfNotNull(address, host).joinToString(":").let { if (it.isEmpty()) container else "$it:$container" } + "/$protocol"
                val (published, refused) = EngineCli.PODMAN.publish(spec, features, "create")
                refused?.let { return refuse(501, it.text.trim()) }
                publish += published!!
            }
        }

        val restart = string(o, "restart_policy")?.let { policy ->
            val tries = string(o, "restart_tries")?.toIntOrNull() ?: 0
            ContainerProgram.RestartPolicy.parse(if (policy == "on-failure" && tries > 0) "on-failure:$tries" else policy)
                ?: return refuse(400, "restart_policy takes no, on-failure, always or unless-stopped, not $policy")
        } ?: ContainerProgram.RestartPolicy.No
        val autoRemove = bool("remove")
        if (autoRemove && restart != ContainerProgram.RestartPolicy.No) return refuse(409, "the --rm option conflicts with --restart")
        return EngineRequest.Run(
            image = image,
            name = string(o, "name"),
            args = strings("command").takeIf { o["command"] is JsonArray && it.isNotEmpty() },
            env = map("env").map { (k, v) -> "$k=$v" },
            volumes = volumes,
            restart = restart,
            pod = string(o, "pod"),
            publish = publish,
            start = false,
            labels = map("labels"),
            networks = joins,
            autoRemove = autoRemove,
        ) to null
    }

    /**
     * A pod-create body (`POST /libpod/pods/create`, Podman's PodSpecGenerator): its name and labels. A pod here is a
     * named group whose containers already share the device's network (docs/SPEC.md 3d, "Pods"), so the namespaces it
     * shares are what it has anyway; a port is taken on its own number, and what a pod would need namespaces or limits
     * of its own for is answered from the feature table or as not built. Pure.
     */
    fun podRequest(body: String, features: EngineFeatures?): Triple<String?, Map<String, String>, EngineHttp.Response?> {
        fun answer(response: EngineHttp.Response) = Triple(null, emptyMap<String, String>(), response)
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body.ifBlank { "{}" }) as JsonObject }.getOrNull()
            ?: return answer(DockerApi.error(400, "the body is not a JSON object"))
        fun string(obj: JsonObject?, key: String): String? = (obj?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
        fun feature(option: String) = answer(DockerApi.error(501, EngineOptions.refusal("podman pod create", option, features)))
        string(o, "hostname")?.let { return feature("--hostname") }
        if (string(o["netns"] as? JsonObject, "nsmode") == "none") return feature("--network")
        // Podman's client sends {"cpu":{}} with no limit set.
        (o["resource_limits"] as? JsonObject)?.filterValues { (it as? JsonObject)?.isNotEmpty() ?: (it !is JsonNull) }?.keys?.firstOrNull()?.let { key ->
            return feature(if (key == "memory") "--memory" else if (key == "pids") "--pids-limit" else "--cpus")
        }
        ((o["Networks"] as? JsonObject)?.takeIf { it.isNotEmpty() })?.let { return answer(notBuilt("a pod on a network of its own (each container joins one with --network)")) }
        listOf("dns_server", "hostadd", "devices", "infra_image", "sysctl").forEach { key ->
            if (((o[key] as? JsonArray)?.isNotEmpty() ?: (o[key] as? JsonObject)?.isNotEmpty() ?: (string(o, key) != null))) return answer(notBuilt(key))
        }
        (o["portmappings"] as? JsonArray).orEmpty().forEach { m ->
            val mapping = m as? JsonObject
            val container = string(mapping, "container_port") ?: return answer(DockerApi.error(400, "a port mapping needs a container_port"))
            val host = string(mapping, "host_port")?.takeIf { it != "0" }
            val spec = listOfNotNull(string(mapping, "host_ip"), host).joinToString(":").let { if (it.isEmpty()) container else "$it:$container" }
            EngineCli.PODMAN.publish(spec, features, "pod create").second?.let { return answer(DockerApi.error(501, it.text.trim())) }
        }
        val labels = (o["labels"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }.orEmpty()
        return Triple(string(o, "name"), labels, null)
    }

    /** `POST /libpod/pods/create`'s answer. */
    fun podCreated(id: String): String = JsonObject(mapOf("Id" to JsonPrimitive(id))).toString() + "\n"

    /** A pod start, stop, restart or kill's answer: no errors. */
    fun podReport(id: String): String = buildJsonObject {
        putJsonArray("Errs") { }
        put("Id", id)
    }.toString() + "\n"

    /** `DELETE /libpod/pods/{id}`'s answer: the pod and the containers that went with it. */
    fun podRemoved(id: String, containerIds: List<String>): String = buildJsonObject {
        putJsonObject("RemovedCtrs") { containerIds.forEach { put(it, JsonNull) } }
        put("Id", id)
    }.toString() + "\n"

    /**
     * A network-create body (`POST /libpod/networks/create`, netavark's shape) read; or the answer when it asks for
     * what droidtop cannot give: the same rules as Docker's (`DockerApi.networkRequest`), in Podman's field names. Pure.
     */
    fun networkRequest(body: String, existing: List<Network>, features: EngineFeatures?): Pair<Network?, EngineHttp.Response?> {
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body.ifBlank { "{}" }) as JsonObject }.getOrNull()
            ?: return null to DockerApi.error(400, "the body is not a JSON object")
        fun string(key: String): String? = (o[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
        fun bool(key: String): Boolean = (o[key] as? JsonPrimitive)?.content == "true"
        val name = string("name") ?: return null to DockerApi.error(400, "a network needs a name")
        ContainerNetworks.problemWith(existing, name)?.let { return null to DockerApi.error(409, it) }
        string("driver")?.takeIf { it != "bridge" }?.let { return null to notBuilt("the $it network driver") }
        if (bool("internal")) return null to DockerApi.error(501, EngineOptions.refusal("podman network create", "--network", features) + " (an internal network)")
        if (bool("ipv6_enabled")) return null to notBuilt("IPv6 networks")
        (o["subnets"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { return null to notBuilt("subnets of a network's own (--subnet)") }
        (o["options"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { return null to notBuilt("network options (--opt)") }
        string("network_interface")?.let { return null to notBuilt("--interface-name") }
        val labels = (o["labels"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }.orEmpty()
        return Network(ContainerNetworks.newId(), name, System.currentTimeMillis(), labels) to null
    }

    /** The name a network-create body asks for, or null. Pure. */
    fun networkName(body: String): String? = runCatching {
        ((kotlinx.serialization.json.Json.parseToJsonElement(body) as JsonObject)["name"] as? JsonPrimitive)?.content
    }.getOrNull()

    /** `DELETE /libpod/networks/{id}`'s answer: one report for the network removed. */
    fun networkRemoved(name: String): String = JsonArray(listOf(JsonObject(mapOf("Name" to JsonPrimitive(name))))).toString() + "\n"

    /** `POST /libpod/images/pull`'s answer: a line of progress, then the image's id, as Podman's client reads them. */
    fun pulled(reference: String, digest: String): String =
        JsonObject(mapOf("stream" to JsonPrimitive("Pulled $reference\n"))).toString() + "\n" + present(digest)

    /** The pull answer for an image already stored, which a `missing` or `never` policy takes as it is. */
    fun present(digest: String): String = buildJsonObject {
        putJsonArray("images") { add(JsonPrimitive(imageId(digest))) }
        put("id", imageId(digest))
    }.toString() + "\n"

    /** A pull that failed: Podman's service says so in the stream, after its 200. */
    fun pullFailed(message: String): String = JsonObject(mapOf("error" to JsonPrimitive(message))).toString() + "\n"

    /** `DELETE /libpod/containers/{id}`'s answer: one report per container removed. */
    fun removed(ids: List<String>): String = JsonArray(ids.map { JsonObject(mapOf("Id" to JsonPrimitive(it))) }).toString() + "\n"

    /** `POST /libpod/containers/{id}/wait`'s answer: the exit code alone. */
    fun waited(code: Int): String = "$code\n"

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
                putJsonObject("Labels") { p.pod.labels.forEach { (k, v) -> put(k, v) } }
            }
        },
    ).toString() + "\n"

    fun podMatches(pod: Pod, filters: Map<String, List<String>>): Boolean = filters.all { (key, wanted) ->
        when (key) {
            "name" -> wanted.any { pod.name.contains(it) }
            "id" -> wanted.any { pod.id.startsWith(it) }
            "label" -> wanted.any { w ->
                val k = w.substringBefore('=')
                if (w.contains('=')) pod.labels[k] == w.substringAfter('=') else pod.labels.containsKey(k)
            }
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
        putJsonObject("Labels") { p.pod.labels.forEach { (k, v) -> put(k, v) } }
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
