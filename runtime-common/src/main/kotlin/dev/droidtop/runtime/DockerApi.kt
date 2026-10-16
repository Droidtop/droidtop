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
        data class Containers(val all: Boolean, val filters: Map<String, List<String>> = emptyMap()) : Call
        data class Container(val id: String) : Call
        data object Images : Call
        data class Create(val name: String?) : Call
        data class Start(val id: String) : Call
        data class Stop(val id: String) : Call
        data class Restart(val id: String) : Call
        data class Kill(val id: String, val signal: String?) : Call
        data class Remove(val id: String, val force: Boolean) : Call
        data class Pull(val reference: String) : Call
        data class Logs(val id: String, val tail: Int, val stdout: Boolean, val stderr: Boolean) : Call
        data class Wait(val id: String, val condition: String) : Call

        data class Networks(val filters: Map<String, List<String>>) : Call
        data class NetworkGet(val id: String) : Call
        data object NetworkCreate : Call
        data class NetworkRemove(val id: String) : Call
        data class NetworkConnect(val id: String, val connect: Boolean) : Call
        data class ImageGet(val name: String) : Call
        data class ExecCreate(val containerId: String) : Call
        data class Attach(val id: String, val logs: Boolean, val stream: Boolean) : Call
        data class ExecStart(val execId: String) : Call
        data class ExecGet(val execId: String) : Call

        /** A request Docker would take that droidtop refuses as asked: answered [status] with [message]. */
        data class Refused(val status: Int, val message: String) : Call

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
            p == "/containers/json" && read -> filters(query["filters"])?.let { Call.Containers(all = flag(query["all"]), filters = it) }
                ?: Call.Refused(400, "filters is not the JSON object Docker sends")
            segments.size == 3 && segments[0] == "containers" && segments[2] == "json" && read -> Call.Container(segments[1])
            p == "/images/json" && read -> Call.Images
            p == "/containers/create" && method == "POST" -> Call.Create(query["name"]?.takeIf { it.isNotEmpty() })
            segments.size == 3 && segments[0] == "containers" && segments[2] == "exec" && method == "POST" -> Call.ExecCreate(segments[1])
            segments.size == 3 && segments[0] == "containers" && segments[2] == "attach" && method == "POST" -> when {
                flag(query["stdin"]) -> Call.NotBuilt("attach with input (docker run -i; droidtop's own docker command streams it)")
                else -> Call.Attach(segments[1], logs = flag(query["logs"]), stream = flag(query["stream"]))
            }
            segments.size == 3 && segments[0] == "exec" && segments[2] == "start" && method == "POST" -> Call.ExecStart(segments[1])
            segments.size == 3 && segments[0] == "exec" && segments[2] == "json" && read -> Call.ExecGet(segments[1])
            segments.size == 3 && segments[0] == "containers" && method == "POST" && segments[2] in setOf("start", "stop", "restart", "kill", "wait") ->
                containerAction(segments[1], segments[2], query)
            segments.size == 2 && segments[0] == "containers" && method == "DELETE" ->
                if (query["link"].let { it != null && flag(it) }) Call.NotBuilt("DELETE /containers/{id}?link") else Call.Remove(segments[1], flag(query["force"]))
            segments.size == 3 && segments[0] == "containers" && segments[2] == "logs" && read -> logs(segments[1], query)
            p == "/images/create" && method == "POST" -> pull(query)
            p.startsWith("/images/") && p.endsWith("/json") && p.length > "/images//json".length && read ->
                Call.ImageGet(p.removePrefix("/images/").removeSuffix("/json"))
            p == "/networks" && read -> filters(query["filters"])?.let { Call.Networks(it) } ?: Call.Refused(400, "filters is not the JSON object Docker sends")
            p == "/networks/create" && method == "POST" -> Call.NetworkCreate
            p == "/networks/prune" -> Call.NotBuilt("POST /networks/prune")
            segments.size == 2 && segments[0] == "networks" && read -> Call.NetworkGet(segments[1])
            segments.size == 2 && segments[0] == "networks" && method == "DELETE" -> Call.NetworkRemove(segments[1])
            segments.size == 3 && segments[0] == "networks" && method == "POST" && segments[2] in setOf("connect", "disconnect") ->
                Call.NetworkConnect(segments[1], segments[2] == "connect")
            segments.firstOrNull() in setOf("containers", "images", "exec", "volumes", "networks", "build", "events", "system", "plugins", "swarm", "nodes", "services", "tasks", "secrets", "configs", "distribution", "session", "auth", "commit") ->
                Call.NotBuilt("$method $p")
            else -> Call.NotFound
        }
    }

    private fun flag(value: String?): Boolean = value == "1" || value == "true" || value == "True"

    /** `filters` as Docker sends it, `{"label":["a=b"],"name":["web"]}` (an older client's `{"label":{"a=b":true}}` too). Pure. */
    fun filters(text: String?): Map<String, List<String>>? {
        if (text.isNullOrBlank()) return emptyMap()
        val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return null
        return o.mapValues { (_, v) ->
            when (v) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonObject -> v.keys.toList()
                else -> return null
            }
        }
    }

    /** Whether [row] passes [filters]: label, name, id and status, each any-of within and all-of across, as Docker's are. */
    fun matches(row: Summary, filters: Map<String, List<String>>): Boolean = filters.all { (key, wanted) ->
        val f = row.facts
        when (key) {
            "label" -> wanted.any { w ->
                val k = w.substringBefore('=')
                if (w.contains('=')) f.labels[k] == w.substringAfter('=') else f.labels.containsKey(k)
            }
            "name" -> wanted.any { f.name.contains(it.removePrefix("/")) }
            "id" -> wanted.any { f.id.startsWith(it) }
            "status" -> wanted.any { it == f.status }
            else -> true
        }
    }

    /** Filters droidtop does not apply: said so, never ignored. */
    fun unknownFilters(filters: Map<String, List<String>>): Set<String> = filters.keys - setOf("label", "name", "id", "status")

    private fun containerAction(id: String, action: String, query: Map<String, String>): Call = when (action) {
        "start" -> Call.Start(id)
        "stop" -> Call.Stop(id)
        "restart" -> Call.Restart(id)
        "kill" -> query["signal"].let { signal ->
            if (signal.isNullOrEmpty() || signal.removePrefix("SIG") in setOf("KILL", "TERM", "INT") || signal in setOf("9", "15", "2")) Call.Kill(id, signal)
            else Call.NotBuilt("signal $signal (a container's program is ended, not signalled)")
        }
        else -> query["condition"].orEmpty().ifEmpty { "not-running" }.let { condition ->
            if (condition in setOf("not-running", "next-exit", "removed")) Call.Wait(id, condition) else Call.NotBuilt("wait?condition=$condition")
        }
    }

    private fun logs(id: String, query: Map<String, String>): Call = when {
        flag(query["follow"]) -> Call.NotBuilt("logs?follow")
        flag(query["timestamps"]) -> Call.NotBuilt("logs?timestamps")
        (query["since"] ?: "0") != "0" || (query["until"] ?: "0") != "0" -> Call.NotBuilt("logs?since and until")
        !flag(query["stdout"]) && !flag(query["stderr"]) -> Call.Refused(400, "You must choose at least one stream")
        else -> {
            val tail = query["tail"].let { t -> if (t == null || t == "all") EngineCli.MAX_TAIL else t.toIntOrNull() }
            if (tail == null || tail < 0) Call.Refused(400, "tail is not a number of lines") else Call.Logs(id, tail.coerceAtMost(EngineCli.MAX_TAIL), flag(query["stdout"]), flag(query["stderr"]))
        }
    }

    private fun pull(query: Map<String, String>): Call {
        if (!query["fromSrc"].isNullOrEmpty()) return Call.NotBuilt("images/create?fromSrc (importing)")
        val from = query["fromImage"].orEmpty()
        val tag = query["tag"].orEmpty()
        val lastPart = from.substringAfterLast('/')
        val reference = if (tag.isNotEmpty() && !lastPart.contains(':') && !from.contains('@')) "$from:$tag" else from
        return if (reference.isEmpty() || !EngineCli.DOCKER.validReference(reference)) Call.Refused(400, "not an image reference: $reference") else Call.Pull(reference)
    }

    /**
     * A create request's body (`POST /containers/create`) read into the one request model, or the answer it gets when
     * it asks for what droidtop does not do. Every field droidtop does not apply is refused or answered as not built,
     * never dropped: a client is never told a container has what it does not. Pure.
     */
    fun createRequest(
        body: String,
        name: String?,
        features: EngineFeatures?,
        networks: List<Network> = emptyList(),
    ): Pair<EngineRequest.Run?, EngineHttp.Response?> {
        fun refuse(status: Int, message: String) = null to error(status, message)
        fun notYet(what: String) = null to notBuilt(what)
        val o = runCatching { Json.parseToJsonElement(body.ifBlank { "{}" }) as JsonObject }.getOrNull() ?: return refuse(400, "the body is not a JSON object")
        fun strings(el: kotlinx.serialization.json.JsonElement?): List<String>? = when (el) {
            null, kotlinx.serialization.json.JsonNull -> null
            is JsonArray -> el.map { (it as? JsonPrimitive)?.content ?: "" }
            is JsonPrimitive -> listOf(el.content)
            else -> null
        }
        fun string(obj: JsonObject?, key: String): String? = (obj?.get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
        fun number(obj: JsonObject?, key: String): Long = (obj?.get(key) as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        fun bool(obj: JsonObject?, key: String): Boolean = (obj?.get(key) as? JsonPrimitive)?.content == "true"

        val image = string(o, "Image") ?: return refuse(400, "a container needs an Image")
        if (!EngineCli.DOCKER.validReference(image)) return refuse(400, "not an image reference: $image")
        strings(o["Entrypoint"])?.takeIf { it.isNotEmpty() }?.let { return notYet("Entrypoint (docker run --entrypoint)") }
        string(o, "WorkingDir")?.let { return notYet("WorkingDir (docker run -w)") }
        string(o, "User")?.takeIf { it.substringBefore(':') !in setOf("root", "0") }?.let { return notYet("User (docker run -u)") }
        string(o, "Hostname")?.let { return refuse(501, EngineOptions.refusal("docker create", "--hostname", features)) }
        (o["Volumes"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { return notYet("Volumes (volumes of droidtop's own)") }
        val env = strings(o["Env"]).orEmpty()
        val cmd = strings(o["Cmd"])
        val labels = (o["Labels"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }.orEmpty()

        val host = o["HostConfig"] as? JsonObject
        if (bool(host, "Privileged")) return refuse(400, "droidtop's containers never run privileged")
        if (bool(o, "Tty") || bool(o, "OpenStdin")) return notYet("a container with a terminal or open input (docker run -t, -i)")
        val autoRemove = bool(host, "AutoRemove")
        listOf("CapAdd", "Devices", "SecurityOpt", "Links", "VolumesFrom").forEach { key ->
            strings(host?.get(key))?.takeIf { it.isNotEmpty() }?.let { return notYet(key) }
        }
        (host?.get("Mounts") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
            return notYet("Mounts (volumes and bind mounts by path; Binds takes a folder you mapped in Containers)")
        }
        val limits = mapOf(
            "Memory" to "--memory", "MemorySwap" to "--memory-swap", "MemoryReservation" to "--memory-reservation",
            "NanoCpus" to "--cpus", "CpuQuota" to "--cpus", "CpuShares" to "--cpu-shares", "PidsLimit" to "--pids-limit",
        )
        limits.forEach { (key, option) -> if (number(host, key) > 0) return refuse(501, EngineOptions.refusal("docker create", option, features)) }
        string(host, "CpusetCpus")?.let { return refuse(501, EngineOptions.refusal("docker create", "--cpuset-cpus", features)) }
        // Networks: bridge, host and default are what every container already has; a network of droidtop's own is
        // joined by name; none needs network namespaces (docs/SPEC.md 3d, "Networks").
        val joins = mutableListOf<EngineRequest.NetworkJoin>()
        fun join(ref: String, aliases: List<String>): EngineHttp.Response? {
            if (ref in setOf("default", "host", "bridge")) return null
            if (ref == "none") return error(501, EngineOptions.refusal("docker create", "--network", features))
            val network = ContainerNetworks.find(networks, ref) ?: return error(404, "network $ref not found")
            val at = joins.indexOfFirst { it.networkId == network.id }
            if (at < 0) joins += EngineRequest.NetworkJoin(network.id, aliases) else joins[at] = joins[at].copy(aliases = (joins[at].aliases + aliases).distinct())
            return null
        }
        string(host, "NetworkMode")?.let { mode -> join(mode, emptyList())?.let { return null to it } }
        ((o["NetworkingConfig"] as? JsonObject)?.get("EndpointsConfig") as? JsonObject)?.forEach { (ref, endpoint) ->
            join(ref, strings((endpoint as? JsonObject)?.get("Aliases")).orEmpty())?.let { return null to it }
        }

        val volumes = strings(host?.get("Binds")).orEmpty().map { spec ->
            EngineCli.DOCKER.volume(spec) ?: return refuse(
                400,
                "Binds takes the name of a folder you mapped in Containers (it appears at ${ContainerLayout.EXTRA_MOUNTS_DIR}/<name>); $spec is not one droidtop can mount",
            )
        }
        val restart = (host?.get("RestartPolicy") as? JsonObject)?.let { policy ->
            val policyName = string(policy, "Name") ?: "no"
            val retries = number(policy, "MaximumRetryCount")
            ContainerProgram.RestartPolicy.parse(if (policyName == "on-failure" && retries > 0) "on-failure:$retries" else policyName)
                ?: return refuse(400, "RestartPolicy takes no, on-failure, always or unless-stopped, not $policyName")
        } ?: ContainerProgram.RestartPolicy.No
        val publish = mutableListOf<ExposedPort>()
        (host?.get("PortBindings") as? JsonObject)?.forEach { (port, bindings) ->
            val list = (bindings as? JsonArray).orEmpty().ifEmpty { listOf(JsonObject(emptyMap())) }
            list.forEach { binding ->
                val b = binding as? JsonObject
                val spec = listOfNotNull(string(b, "HostIp"), string(b, "HostPort") ?: "").joinToString(":") + ":" + port
                val (published, refused) = EngineCli.DOCKER.publish(spec.removePrefix(":"), features, "create")
                refused?.let { return refuse(501, it.text.trim()) }
                publish += published!!
            }
        }
        return EngineRequest.Run(
            image = image,
            name = name,
            args = cmd,
            env = env,
            volumes = volumes,
            restart = restart,
            publish = publish,
            start = false,
            labels = labels,
            networks = joins,
            autoRemove = autoRemove,
        ).let { run ->
            if (autoRemove && run.restart != ContainerProgram.RestartPolicy.No) return refuse(409, "AutoRemove and a restart policy cannot be used together")
            run
        } to null
    }

    /** What a network-create body asks for, read; or the answer when it asks for isolation droidtop cannot give. Pure. */
    fun networkRequest(body: String, existing: List<Network>, features: EngineFeatures?): Pair<Network?, EngineHttp.Response?> {
        val o = runCatching { Json.parseToJsonElement(body.ifBlank { "{}" }) as JsonObject }.getOrNull()
            ?: return null to error(400, "the body is not a JSON object")
        fun string(key: String): String? = (o[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
        fun bool(key: String): Boolean = (o[key] as? JsonPrimitive)?.content == "true"
        val name = string("Name") ?: return null to error(400, "a network needs a Name")
        ContainerNetworks.problemWith(existing, name)?.let { return null to error(409, it) }
        string("Driver")?.takeIf { it != "bridge" }?.let { return null to notBuilt("the $it network driver") }
        if (bool("Internal")) return null to error(501, EngineOptions.refusal("docker network create", "--network", features) + " (an internal network)")
        if (bool("EnableIPv6") || bool("Ingress")) return null to notBuilt("IPv6 and ingress networks")
        ((o["IPAM"] as? JsonObject)?.get("Config") as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { return null to notBuilt("subnets of a network's own (IPAM Config)") }
        (o["Options"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { return null to notBuilt("network Options") }
        val labels = (o["Labels"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" }.orEmpty()
        return Network(ContainerNetworks.newId(), name, System.currentTimeMillis(), labels) to null
    }

    /** A network as Docker describes one; [names] names its members by container id. */
    fun network(n: Network, names: Map<String, String>): JsonObject = buildJsonObject {
        put("Name", n.name)
        put("Id", n.id)
        put("Created", if (n.createdMs > 0) java.time.Instant.ofEpochMilli(n.createdMs).toString() else "0001-01-01T00:00:00Z")
        put("Scope", "local")
        put("Driver", when (n.name) { "host" -> "host"; "none" -> "null"; else -> "bridge" })
        put("EnableIPv6", false)
        putJsonObject("IPAM") {
            put("Driver", "default")
            putJsonObject("Options") { }
            putJsonArray("Config") { }
        }
        put("Internal", false)
        put("Attachable", false)
        put("Ingress", false)
        putJsonObject("Containers") {
            n.members.forEach { id ->
                putJsonObject(id) {
                    put("Name", names[id] ?: id)
                    put("EndpointID", "")
                    put("MacAddress", "")
                    put("IPv4Address", "")
                    put("IPv6Address", "")
                }
            }
        }
        putJsonObject("Options") { }
        putJsonObject("Labels") { n.labels.forEach { (k, v) -> put(k, v) } }
    }

    /** `GET /networks/{id}`: one network as text. */
    fun networkJson(n: Network, names: Map<String, String>): String = network(n, names).toString() + "\n"

    /** The container a network connect or disconnect body names (`{"Container": ...}`), or null. Pure. */
    fun connectTarget(body: String): String? =
        runCatching { (Json.parseToJsonElement(body) as JsonObject)["Container"] as? JsonPrimitive }.getOrNull()?.content?.takeIf { it.isNotEmpty() }

    /** `POST /networks/create`'s answer. */
    fun networkCreated(id: String): String = buildJsonObject {
        put("Id", id)
        put("Warning", "")
    }.toString() + "\n"

    /** `GET /networks`: [all] that pass [filters] (name, id, label). */
    fun networks(all: List<Network>, names: Map<String, String>, filters: Map<String, List<String>>): String = JsonArray(
        all.filter { n ->
            filters.all { (key, wanted) ->
                when (key) {
                    "name" -> wanted.any { n.name.contains(it) }
                    "id" -> wanted.any { n.id.startsWith(it) }
                    "label" -> wanted.any { w ->
                        val k = w.substringBefore('=')
                        if (w.contains('=')) n.labels[k] == w.substringAfter('=') else n.labels.containsKey(k)
                    }
                    else -> true
                }
            }
        }.map { network(it, names) },
    ).toString() + "\n"

    fun unknownNetworkFilters(filters: Map<String, List<String>>): Set<String> = filters.keys - setOf("name", "id", "label")

    /** The stored image [ref] names: its reference in any of Docker's spellings, its digest, or a start of it. Pure. */
    fun findImage(entries: List<OciImageStore.Entry>, ref: String): OciImageStore.Entry? {
        val wanted = EngineCli.PODMAN.fullImage(ref)
        return entries.firstOrNull { EngineCli.PODMAN.fullImage(it.reference) == wanted }
            ?: entries.firstOrNull { it.digest == ref }
            ?: entries.filter { ref.removePrefix("sha256:").length >= 3 && it.digest.removePrefix("sha256:").startsWith(ref.removePrefix("sha256:")) }.singleOrNull()
    }

    /** `GET /images/{name}/json`: what droidtop knows of a stored image, in Docker's shape. */
    fun image(e: OciImageStore.Entry, config: ImageRunConfig?, arch: String): String = buildJsonObject {
        put("Id", e.digest)
        putJsonArray("RepoTags") { add(JsonPrimitive(EngineCli.PODMAN.fullImage(e.reference).removePrefix("docker.io/library/").removePrefix("docker.io/"))) }
        putJsonArray("RepoDigests") { }
        put("Parent", "")
        put("Created", "0001-01-01T00:00:00Z")
        put("Os", "linux")
        put("Architecture", arch)
        put("Size", e.sizeBytes)
        putJsonObject("Config") {
            putJsonArray("Env") { config?.env?.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("Cmd") { config?.cmd?.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("Entrypoint") { config?.entrypoint?.forEach { add(JsonPrimitive(it)) } }
            put("WorkingDir", config?.workingDir ?: "")
            put("User", config?.user ?: "")
            putJsonObject("ExposedPorts") { config?.ports?.forEach { put(it.toString(), JsonObject(emptyMap())) } }
        }
        putJsonObject("RootFS") { put("Type", "layers") }
    }.toString() + "\n"

    /** A command an exec runs: what `POST /containers/{id}/exec` asked for, after droidtop checked it. */
    data class ExecSpec(val command: List<String>, val env: Map<String, String>, val workdir: String?)

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * An exec-create body read into what droidtop runs, or the answer when it asks for what droidtop does not do. The
     * command runs on pipes and its output comes back when it ends, so input and a terminal (`docker exec -i`, `-t`)
     * are not built; droidtop's own docker command's exec streams them. Pure.
     */
    fun execRequest(body: String): Pair<ExecSpec?, EngineHttp.Response?> {
        val o = runCatching { Json.parseToJsonElement(body.ifBlank { "{}" }) as JsonObject }.getOrNull()
            ?: return null to error(400, "the body is not a JSON object")
        fun bool(key: String) = (o[key] as? JsonPrimitive)?.content == "true"
        fun string(key: String) = (o[key] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }
        val command = (o["Cmd"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (command.isEmpty()) return null to error(400, "an exec needs a Cmd")
        if (bool("Privileged")) return null to error(400, "droidtop's containers never run privileged")
        if (bool("AttachStdin") || bool("Tty")) {
            return null to notBuilt("exec with input or a terminal over the Engine API (droidtop's own docker command, at the end of the PATH, streams them)")
        }
        string("User")?.takeIf { it.substringBefore(':') !in setOf("root", "0") }?.let { return null to notBuilt("exec as User $it") }
        val env = linkedMapOf<String, String>()
        (o["Env"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.forEach { kv ->
            val name = kv.substringBefore('=')
            if (!kv.contains('=') || !ENV_NAME.matches(name)) return null to error(400, "not NAME=VALUE: $kv")
            env[name] = kv.substringAfter('=')
        }
        return ExecSpec(command, env, string("WorkingDir")) to null
    }

    /** `POST /containers/{id}/exec`'s answer. */
    fun execCreated(id: String): String = buildJsonObject { put("Id", id) }.toString() + "\n"

    /** Whether an exec-start body asks to run without waiting (`{"Detach": true}`). Pure. */
    fun detached(body: String): Boolean =
        runCatching { ((Json.parseToJsonElement(body) as JsonObject)["Detach"] as? JsonPrimitive)?.content == "true" }.getOrDefault(false)

    /** A command's output as Docker's multiplexed frames: stdout on stream 1, stderr on stream 2. Pure. */
    fun outputFrames(stdout: String, stderr: String): ByteArray =
        frame(1, stdout.toByteArray(Charsets.UTF_8)) + frame(2, stderr.toByteArray(Charsets.UTF_8))

    /** One multiplexed frame of [payload] bytes on [stream] (1 stdout, 2 stderr), empty for no bytes. Pure. */
    fun frame(stream: Int, payload: ByteArray): ByteArray {
        if (payload.isEmpty()) return ByteArray(0)
        val n = payload.size
        return byteArrayOf(stream.toByte(), 0, 0, 0, (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + payload
    }

    /** `GET /exec/{id}/json`: whether it runs, and its exit code once it has ended. */
    fun execInspect(id: String, containerId: String, spec: ExecSpec, running: Boolean, exitCode: Int?): String = buildJsonObject {
        put("ID", id)
        put("Running", running)
        put("ExitCode", exitCode ?: 0)
        putJsonObject("ProcessConfig") {
            put("entrypoint", spec.command.first())
            putJsonArray("arguments") { spec.command.drop(1).forEach { add(JsonPrimitive(it)) } }
            put("tty", false)
            put("privileged", false)
            put("user", "")
        }
        put("OpenStdin", false)
        put("OpenStdout", true)
        put("OpenStderr", true)
        put("ContainerID", containerId)
        put("Pid", 0)
    }.toString() + "\n"

    /** `/images/create`'s answer: the progress lines Docker's clients read, all at once after the pull. */
    fun pulled(reference: String, digest: String): String = listOf(
        "Pulling from $reference",
        "Digest: $digest",
        "Status: Downloaded newer image for $reference",
    ).joinToString("") { JsonObject(mapOf("status" to JsonPrimitive(it))).toString() + "\n" }

    /** `/containers/{id}/create`'s answer: its id and the notes. */
    fun created(id: String, warnings: List<String>): String = buildJsonObject {
        put("Id", id)
        putJsonArray("Warnings") { warnings.forEach { add(JsonPrimitive(it)) } }
    }.toString() + "\n"

    /** `/containers/{id}/wait`'s answer. */
    fun waited(code: Int): String = buildJsonObject {
        put("StatusCode", code)
        put("Error", kotlinx.serialization.json.JsonNull)
    }.toString() + "\n"

    /**
     * Log lines in Docker's multiplexed stream (a container without a terminal): each frame an 8-byte header (stream 1
     * for stdout, three zero bytes, the payload's length big-endian) and the payload. A program's log holds stdout and
     * stderr together, so it is sent as stdout. Pure.
     */
    fun logFrames(lines: List<String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        lines.forEach { line ->
            val payload = (line + "\n").toByteArray(Charsets.UTF_8)
            val n = payload.size
            out.write(byteArrayOf(1, 0, 0, 0, (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
            out.write(payload)
        }
        return out.toByteArray()
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
    fun containers(rows: List<Summary>, all: Boolean, filters: Map<String, List<String>> = emptyMap()): String = JsonArray(
        rows.filter { (all || it.facts.status == "running" || it.facts.status == "restarting") && matches(it, filters) }.map { summary(it) },
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
            putJsonObject("Labels") { f.labels.forEach { (k, v) -> put(k, v) } }
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
