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
     * port the person forwarded, its global IPv6 addresses, and the address
     * its router gives it as STUN found it.
     */
    val endpoints: List<String> = emptyList(),
    val pairedAtMs: Long,
    val lastSyncMs: Long = 0L,
    /** What the last sync with it did, as one line. */
    val lastLine: String? = null,
    /** Its global discovery ID (Syncthing's device ID form), as it said in its last hello. */
    val disco: String? = null,
    /** Which way the last session reached it: [Computers.PATH_LAN], [Computers.PATH_WIREGUARD] or [Computers.PATH_RENDEZVOUS]. */
    val lastPath: String? = null,
    val lastPathMs: Long = 0L,
)

/**
 * The computers this device is paired with (docs/SPEC.md 7o "Computers"), and
 * the one way to reach one: [call] fills in this device's key, the computer's
 * id, its known addresses and the rendezvous settings, and remembers the
 * address that answered, the endpoints and discovery ID it stated, and which
 * way the session went. Each computer gets a folder of its own under
 * `files/agent/<id>/` for the save baselines, the conflict archive and the
 * library state.
 *
 * Nothing here listens or polls: droidtop reaches a computer around a game's
 * launch and exit, when the person starts a sync, and while the pairing screen
 * is open.
 */
object Computers {
    const val PATH_LAN = "lan"
    const val PATH_WIREGUARD = "wireguard"
    const val PATH_RENDEZVOUS = "rendezvous"

    /** Syncthing's global discovery servers, in the agent core's notation. */
    const val DEFAULT_DISCOVERY = "default"

    private const val PREFS = "droidtop_agent_rendezvous"
    private const val KEY_ON = "on"
    private const val KEY_SERVER = "server"

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

    /** Whether droidtop finds computers away from home through global discovery (on unless the person turned it off). */
    fun rendezvousOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    fun setRendezvousOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }

    /** The discovery server: [DEFAULT_DISCOVERY] for Syncthing's, or an https address (droidtop's own, Droidtop/tracker#364). */
    fun discoveryServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_DISCOVERY

    /** Sets the discovery server; anything but an https address goes back to Syncthing's. */
    fun setDiscoveryServer(context: Context, server: String) {
        val value = server.trim().takeIf { it.startsWith("https://") } ?: DEFAULT_DISCOVERY
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SERVER, value).apply()
    }

    /**
     * Runs [op] for [computer]. Blocks on the network: never on the main thread.
     *
     * The core tries the computer's LAN addresses, then the LAN broadcast,
     * then its WireGuard endpoints, then a rendezvous through global
     * discovery (droidtop-agent docs/DESIGN.md section 10), so a computer away
     * from this network is still reached when a forwarded port, a global IPv6
     * address or a punched hole lets the tunnel through. A reply from a live
     * session carries the endpoints and discovery ID the computer states now,
     * which replace the ones kept, and which way it went; an address reached
     * through the tunnel is not a LAN address and is not kept.
     */
    fun call(context: Context, computer: Computer, op: String, args: JSONObject = JSONObject()): JSONObject {
        val seed = DeviceIdentity.seed(context) ?: return JSONObject().put("error", "this device's key could not be opened")
        val known = list(context).firstOrNull { it.id == computer.id } ?: computer
        args.put("seed", seed)
            .put("peer", computer.id)
            .put("addresses", JSONArray(known.addresses + known.endpoints))
            .put("name", deviceName(context))
        if (rendezvousOn(context) && known.disco != null) {
            args.put("disco", known.disco).put(
                "rendezvous",
                JSONObject()
                    .put("servers", JSONArray(listOf(discoveryServer(context))))
                    .put("stun", JSONArray(listOf("default")))
                    .put("state", File(folder(context), "rendezvous.json").absolutePath),
            )
        }
        val reply = AgentNative.call(op, args)
        if (!reply.has("computer")) return reply
        val address = reply.optString("address").takeIf { it.isNotBlank() }
        val endpoints = reply.optJSONArray("endpoints")
            ?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("wg:") }.take(MAX_ENDPOINTS) }
        val disco = reply.optString("disco").takeIf { it.isNotBlank() }
        val path = reply.optString("path").takeIf { it.isNotBlank() }
        update(context, computer.id) { c ->
            c.copy(
                addresses = address?.let { (listOf(it) + c.addresses).distinct().take(MAX_ADDRESSES) } ?: c.addresses,
                endpoints = endpoints ?: c.endpoints,
                disco = disco ?: c.disco,
                lastPath = path ?: c.lastPath,
                lastPathMs = if (path != null) System.currentTimeMillis() else c.lastPathMs,
            )
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
        .put("disco", c.disco ?: JSONObject.NULL)
        .put("lastPath", c.lastPath ?: JSONObject.NULL)
        .put("lastPathMs", c.lastPathMs)

    private fun fromJson(o: JSONObject): Computer? {
        val id = o.optString("id").takeIf { ID.matches(it) } ?: return null
        fun strings(name: String) = o.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }
        fun text(name: String) = o.optString(name).takeIf { o.has(name) && !o.isNull(name) && it.isNotBlank() }
        return Computer(
            id = id,
            name = o.optString("name").ifBlank { "Computer" },
            addresses = strings("addresses"),
            endpoints = strings("endpoints"),
            pairedAtMs = o.optLong("pairedAtMs"),
            lastSyncMs = o.optLong("lastSyncMs"),
            lastLine = text("lastLine"),
            disco = text("disco"),
            lastPath = text("lastPath"),
            lastPathMs = o.optLong("lastPathMs"),
        )
    }

    private const val MAX_ADDRESSES = 4

    /** A forwarded port, the STUN-found address and a few IPv6 addresses; more is a computer with many interfaces. */
    private const val MAX_ENDPOINTS = 8
}
