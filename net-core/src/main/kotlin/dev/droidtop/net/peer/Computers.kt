package dev.droidtop.net.peer

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A computer paired with this device through droidtop-agent. */
data class Computer(
    /** Its id: the public key droidtop pinned at pairing, in hex. */
    val id: String,
    val name: String,
    /** Where it answered last, most recent first (`192.168.1.20:47610`). */
    val addresses: List<String>,
    /**
     * Where its WireGuard answers from outside the LAN, as it said the last
     * time the two met (`wg:203.0.113.7:47611`, `wg:[2001:db8::7]:47611`): a
     * port the person forwarded and its global IPv6 addresses.
     */
    val endpoints: List<String> = emptyList(),
    val pairedAtMs: Long,
    val lastSyncMs: Long = 0L,
    /** What the last sync with it did, as one line. */
    val lastLine: String? = null,
)

/**
 * The computers this device is paired with (docs/SPEC.md 7o "Computers"), and
 * the one way to reach one: [call] fills in this device's key, the computer's
 * id and its known addresses, and remembers the address that answered. Each
 * computer gets a folder of its own under `files/agent/<id>/` for the save
 * baselines, the conflict archive and the library state.
 *
 * Nothing here listens or polls: droidtop reaches a computer around a game's
 * launch and exit, when the person starts a sync, and while the pairing screen
 * is open.
 */
object Computers {
    @Volatile
    private var cache: List<Computer>? = null

    private val ID = Regex("^[0-9a-f]{64}$")

    fun folder(context: Context): File = File(context.filesDir, "agent")

    private fun file(context: Context): File = File(folder(context), "computers.json")

    /** The paired computers. Reads a small file the first time: never on the main thread. */
    @Synchronized
    fun list(context: Context): List<Computer> {
        cache?.let { return it }
        val read = runCatching {
            val array = JSONArray(file(context).readText())
            (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
        }.getOrDefault(emptyList())
        cache = read
        return read
    }

    /**
     * Whether a computer is paired, from memory only, so the main thread may ask:
     * false until [list] has run in this process. A game's launch reads the list
     * before its exit asks this.
     */
    fun anyKnown(): Boolean = cache?.isNotEmpty() == true

    @Synchronized
    fun put(context: Context, computer: Computer) {
        write(context, list(context).filter { it.id != computer.id } + computer)
    }

    @Synchronized
    fun update(context: Context, id: String, change: (Computer) -> Computer) {
        val current = list(context)
        if (current.none { it.id == id }) return
        write(context, current.map { if (it.id == id) change(it) else it })
    }

    /** Forgets a computer and everything kept for it on this device. */
    @Synchronized
    fun remove(context: Context, id: String) {
        write(context, list(context).filter { it.id != id })
        if (ID.matches(id)) File(folder(context), id).deleteRecursively()
    }

    /** This device's folder for one computer. */
    fun stateDir(context: Context, computer: Computer): File = File(folder(context), computer.id)

    /** The name the computer shows for this device. */
    fun deviceName(context: Context): String =
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /**
     * Runs [op] for [computer]. Blocks on the network: never on the main thread.
     *
     * The core tries the computer's LAN addresses, then the LAN broadcast,
     * then its WireGuard endpoints (droidtop-agent docs/DESIGN.md section 10),
     * so a computer away from this network is still reached when its
     * forwarded port or a global IPv6 address answers. A reply from a live
     * session carries the endpoints the computer states now, which replace
     * the ones kept; an address reached through the tunnel is not a LAN
     * address and is not kept.
     */
    fun call(context: Context, computer: Computer, op: String, args: JSONObject = JSONObject()): JSONObject {
        val seed = DeviceIdentity.seed(context) ?: return JSONObject().put("error", "this device's key could not be opened")
        val known = list(context).firstOrNull { it.id == computer.id } ?: computer
        args.put("seed", seed)
            .put("peer", computer.id)
            .put("addresses", JSONArray(known.addresses + known.endpoints))
            .put("name", deviceName(context))
        val reply = AgentNative.call(op, args)
        val address = reply.optString("address").takeIf { it.isNotBlank() }
        val endpoints = reply.optJSONArray("endpoints")
            ?.takeIf { reply.has("computer") }
            ?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("wg:") }.take(MAX_ENDPOINTS) }
        if (address != null || (endpoints != null && endpoints != known.endpoints)) {
            update(context, computer.id) { c ->
                c.copy(
                    addresses = address?.let { (listOf(it) + c.addresses).distinct().take(MAX_ADDRESSES) } ?: c.addresses,
                    endpoints = endpoints ?: c.endpoints,
                )
            }
        }
        return reply
    }

    /** Records what a sync with [id] did. */
    fun noteSync(context: Context, id: String, line: String) {
        update(context, id) { it.copy(lastSyncMs = System.currentTimeMillis(), lastLine = line) }
    }

    private fun write(context: Context, computers: List<Computer>) {
        val dir = folder(context).apply { mkdirs() }
        val array = JSONArray().apply { computers.forEach { put(toJson(it)) } }
        val tmp = File(dir, "computers.json.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file(context))) {
            tmp.delete()
            error("the list of computers could not be saved")
        }
        cache = computers
    }

    private fun toJson(c: Computer): JSONObject = JSONObject()
        .put("id", c.id)
        .put("name", c.name)
        .put("addresses", JSONArray(c.addresses))
        .put("endpoints", JSONArray(c.endpoints))
        .put("pairedAtMs", c.pairedAtMs)
        .put("lastSyncMs", c.lastSyncMs)
        .put("lastLine", c.lastLine ?: JSONObject.NULL)

    private fun fromJson(o: JSONObject): Computer? {
        val id = o.optString("id").takeIf { ID.matches(it) } ?: return null
        fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }
        return Computer(
            id = id,
            name = o.optString("name").ifBlank { "Computer" },
            addresses = strings("addresses"),
            endpoints = strings("endpoints"),
            pairedAtMs = o.optLong("pairedAtMs"),
            lastSyncMs = o.optLong("lastSyncMs"),
            lastLine = o.optString("lastLine").takeIf { o.has("lastLine") && !o.isNull("lastLine") },
        )
    }

    private const val MAX_ADDRESSES = 4

    /** A forwarded port and a few IPv6 addresses; more is a computer with many interfaces. */
    private const val MAX_ENDPOINTS = 8
}
