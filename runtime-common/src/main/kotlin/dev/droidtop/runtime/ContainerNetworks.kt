package dev.droidtop.runtime

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A network of droidtop's engine (docs/SPEC.md 3d, "Networks"; Droidtop/tracker#555 D14): a named group of
 * containers. Every container already shares the device's network, so a network here joins containers by name only;
 * it isolates nothing, which [ContainerNetworks] and the API say. [members] are container ids, [aliases] the names a
 * member was given on it (recorded; they do not resolve yet).
 */
@Serializable
data class Network(
    val id: String,
    val name: String,
    val createdMs: Long,
    val labels: Map<String, String> = emptyMap(),
    val members: List<String> = emptyList(),
    val aliases: Map<String, List<String>> = emptyMap(),
)

/** The networks droidtop keeps, beside the pods. Pure but for [read] and [save]. */
object ContainerNetworks {
    private const val FILE = "networks.json"
    private val JSON = Json { ignoreUnknownKeys = true }
    private val SERIALIZER = ListSerializer(Network.serializer())
    private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")

    /** Docker's own networks, always there: bridge and host are what every container already has; none needs isolation. */
    val PREDEFINED: List<String> = listOf("bridge", "host", "none")

    /** A predefined network's id: the same on every device and every boot, as a client may keep it. */
    fun predefinedId(name: String): String {
        val a = UUID.nameUUIDFromBytes("droidtop-network:$name".toByteArray()).toString().replace("-", "")
        val b = UUID.nameUUIDFromBytes("droidtop-network:$name:2".toByteArray()).toString().replace("-", "")
        return a + b
    }

    fun file(filesDir: File): File = File(File(filesDir, ContainerProgram.LOG_DIR_NAME), FILE)

    fun read(filesDir: File): List<Network> =
        runCatching { JSON.decodeFromString(SERIALIZER, file(filesDir).readText()) }.getOrDefault(emptyList())

    fun save(filesDir: File, networks: List<Network>) {
        val f = file(filesDir).apply { parentFile?.mkdirs() }
        f.writeText(JSON.encodeToString(SERIALIZER, networks))
    }

    /** The predefined networks as records, with no members: what a container is on is its own NetworkMode. */
    fun predefined(): List<Network> = PREDEFINED.map { Network(predefinedId(it), it, 0L) }

    /** The network [ref] names: its name, its id, or a unique start of its id (three characters or more). */
    fun find(networks: List<Network>, ref: String): Network? =
        networks.firstOrNull { it.name == ref } ?: networks.firstOrNull { it.id == ref }
            ?: networks.filter { ref.length >= 3 && it.id.startsWith(ref) }.singleOrNull()

    /** Why [name] cannot be a new network's name, or null when it can. */
    fun problemWith(networks: List<Network>, name: String): String? = when {
        !NAME.matches(name) -> "$name is not a network name (letters, digits, _, . and -, starting with a letter or digit)"
        name in PREDEFINED || networks.any { it.name == name } -> "network with name $name already exists"
        else -> null
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    fun with(networks: List<Network>, network: Network): List<Network> = networks + network

    fun without(networks: List<Network>, id: String): List<Network> = networks.filterNot { it.id == id }

    /** [networks] with [containerId] on [networkId], under [aliases]. */
    fun withMember(networks: List<Network>, networkId: String, containerId: String, aliases: List<String> = emptyList()): List<Network> =
        networks.map {
            if (it.id != networkId) {
                it
            } else {
                it.copy(
                    members = if (containerId in it.members) it.members else it.members + containerId,
                    aliases = if (aliases.isEmpty()) it.aliases else it.aliases + (containerId to aliases),
                )
            }
        }

    /** [networks] with [containerId] off [networkId], or off every network when [networkId] is null (it was removed). */
    fun withoutMember(networks: List<Network>, containerId: String, networkId: String? = null): List<Network> =
        networks.map {
            if ((networkId == null || it.id == networkId) && containerId in it.members) {
                it.copy(members = it.members - containerId, aliases = it.aliases - containerId)
            } else {
                it
            }
        }
}
