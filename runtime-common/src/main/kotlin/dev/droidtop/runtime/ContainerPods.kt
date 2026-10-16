package dev.droidtop.runtime

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A pod: Podman's group of containers (docs/SPEC.md 3d, "Pods"; Droidtop/tracker#555 D12). A pod's containers share
 * one network, and droidtop's containers already share the device's, so here a pod is a named group the podman front
 * starts, stops and removes together; its containers' ports are the device's, as every container's are. [members] are
 * container ids.
 */
@Serializable
data class Pod(val id: String, val name: String, val createdMs: Long, val members: List<String> = emptyList())

/** The pods droidtop keeps, in one file beside the containers' program logs and settings. Pure but for [read] and [save]. */
object ContainerPods {
    private const val FILE = "pods.json"
    private val JSON = Json { ignoreUnknownKeys = true }
    private val SERIALIZER = ListSerializer(Pod.serializer())

    /** What a pod's name may be: what a container's may, so either can appear where a name is typed. */
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")

    fun file(filesDir: File): File = File(File(filesDir, ContainerProgram.LOG_DIR_NAME), FILE)

    fun read(filesDir: File): List<Pod> =
        runCatching { JSON.decodeFromString(SERIALIZER, file(filesDir).readText()) }.getOrDefault(emptyList())

    fun save(filesDir: File, pods: List<Pod>) {
        val f = file(filesDir).apply { parentFile?.mkdirs() }
        f.writeText(JSON.encodeToString(SERIALIZER, pods))
    }

    fun validName(name: String): Boolean = NAME.matches(name)

    /** The pod named or numbered [nameOrId]: its name, its id, or a unique start of its id, as Podman takes them. */
    fun find(pods: List<Pod>, nameOrId: String): Pod? =
        pods.firstOrNull { it.name == nameOrId } ?: pods.firstOrNull { it.id == nameOrId }
            ?: pods.filter { nameOrId.length >= 3 && it.id.startsWith(nameOrId) }.singleOrNull()

    /** Why [name] cannot be a new pod's name, or null when it can. */
    fun problemWith(pods: List<Pod>, name: String): String? = when {
        !validName(name) -> "$name is not a pod name (letters, digits, _, . and -, starting with a letter or digit)"
        pods.any { it.name == name } -> "a pod is already called $name"
        else -> null
    }

    /** [pods] with [pod] added. */
    fun with(pods: List<Pod>, pod: Pod): List<Pod> = pods + pod

    /** [pods] with [containerId] in the pod [podId]. */
    fun withMember(pods: List<Pod>, podId: String, containerId: String): List<Pod> =
        pods.map { if (it.id == podId && containerId !in it.members) it.copy(members = it.members + containerId) else it }

    /** [pods] with [containerId] in none of them: the container was removed. */
    fun withoutMember(pods: List<Pod>, containerId: String): List<Pod> =
        pods.map { if (containerId in it.members) it.copy(members = it.members - containerId) else it }

    /** [pods] without the pod [podId]. */
    fun without(pods: List<Pod>, podId: String): List<Pod> = pods.filterNot { it.id == podId }

    /** A new pod id: 64 hex digits, as Podman's are; the first 12 are what `pod ps` shows. */
    fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "")

    /** A name for a pod made without one: `pod` and the first digits of its id. */
    fun defaultName(id: String): String = "pod_" + id.take(8)
}
