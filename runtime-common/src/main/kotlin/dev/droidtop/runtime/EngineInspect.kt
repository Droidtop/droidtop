package dev.droidtop.runtime

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `inspect` (docs/SPEC.md 3d, "inspect, create, restart and -p"; Droidtop/tracker#555 D13): a container's details as
 * JSON in Docker's shape, with Podman's few names added on the podman front. Only what droidtop knows is said: there
 * is no Pid, no network of the container's own and no host path. A folder mount names the folder the person mapped,
 * as `-v` takes it. Pure.
 */
object EngineInspect {
    /** What droidtop knows about one container, gathered by the request handler. */
    data class Facts(
        val id: String,
        val name: String,
        val image: String,
        val imageDigest: String?,
        val createdMs: Long?,
        /** Docker's State.Status: created, running, restarting or exited. */
        val status: String,
        val exitCode: Int?,
        /** Why the program could not start, when it could not. */
        val error: String?,
        /** The image's run config with `run`'s command and environment applied; null when unreadable. */
        val config: ImageRunConfig?,
        val restart: ContainerProgram.RestartPolicy,
        /** The folders the person mapped into it, by name. */
        val folders: List<String>,
        val podId: String?,
    )

    private val JSON = Json { prettyPrint = true }

    fun format(facts: List<Facts>, front: EngineCli.Front): String =
        JSON.encodeToString(JsonArray.serializer(), JsonArray(facts.map { container(it, front) })) + "\n"

    fun container(f: Facts, front: EngineCli.Front): JsonObject = buildJsonObject {
        val program = f.config?.program().orEmpty()
        put("Id", f.id)
        put("Created", f.createdMs?.let { Instant.ofEpochMilli(it).toString() } ?: "0001-01-01T00:00:00Z")
        put("Path", program.firstOrNull() ?: "")
        putJsonArray("Args") { program.drop(1).forEach { add(JsonPrimitive(it)) } }
        putJsonObject("State") {
            put("Status", f.status)
            put("Running", f.status == "running")
            put("Restarting", f.status == "restarting")
            put("ExitCode", f.exitCode ?: 0)
            put("Error", f.error ?: "")
        }
        put("Image", f.imageDigest ?: "")
        put("Name", if (front == EngineCli.Front.DOCKER) "/${f.name}" else f.name)
        if (front == EngineCli.Front.PODMAN) {
            put("ImageName", f.image)
            put("Pod", f.podId ?: "")
        }
        putJsonObject("HostConfig") {
            putJsonObject("RestartPolicy") {
                val words = f.restart.words()
                put("Name", words.substringBefore(':'))
                put("MaximumRetryCount", (f.restart as? ContainerProgram.RestartPolicy.OnFailure)?.maxRetries ?: 0)
            }
            // Every container shares the device's network and processes (docs/SPEC.md 3, "Ports").
            put("NetworkMode", "host")
            put("PidMode", "host")
        }
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
        putJsonObject("Config") {
            put("Image", f.image)
            put("Env", strings(f.config?.env))
            put("Cmd", strings(f.config?.let { program.drop(it.entrypoint.size) }))
            put("Entrypoint", strings(f.config?.entrypoint))
            put("WorkingDir", f.config?.workingDir ?: "")
            putJsonObject("ExposedPorts") { f.config?.ports?.forEach { put(it.toString(), JsonObject(emptyMap())) } }
        }
    }

    /** A JSON list of [values], or null when nothing is known, as Docker writes an absent list. */
    private fun strings(values: List<String>?): JsonElement =
        if (values == null) JsonNull else buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
}
