package dev.droidtop.net.peer

import android.content.Context
import android.os.FileObserver
import dev.droidtop.net.AtomicWrite
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What plugins see of the devices paired with this one (docs/plugin-api.md D21, Droidtop/tracker#556, #553): every
 * paired computer and handheld ([Computers.devices]), and the small value each plugin announces to them through
 * droidtop-agent's `announce` (droidtop-agent docs/DESIGN.md section 5). A plugin only ever reads what the same plugin
 * id announced on another device, never another plugin's value, an address or a key.
 *
 * - This device's own values, by plugin id, are kept in `files/agent/announce-own.json`. Announcing sends all of them
 *   to every paired device, which answers with its own; those are kept in `announced.json` by device.
 * - A paired handheld cannot be reached unless it serves. While at least one handheld is paired and at least one plugin
 *   has announced, droidtop serves the paired handhelds (`serve_start`), and the agent writes what each announces to
 *   `served.json`; a write there is a change, watched with a [FileObserver], never polled. With no handheld or no
 *   value, nothing listens.
 *
 * Everything here reads or writes small files or reaches the network: never on the main thread.
 */
object PairedDevices {
    /** A device seen (a session with it, or its announcement) within this long is online. */
    const val ONLINE_MS = 10 * 60_000L

    /** The most a plugin announces, as JSON text: the agent's own limit. */
    const val MAX_VALUE_BYTES = 4096

    /** One paired device as a plugin sees it: [announced] is that plugin's own counterpart's value there, or null. */
    data class Device(val id: String, val name: String, val kind: String, val pairedAtMs: Long, val online: Boolean, val announced: JSONObject?)

    /** Set by :app at start: tells the plugins that asked (`devices.changed`). Called on a background thread. */
    @Volatile
    var onChanged: ((Context) -> Unit)? = null

    private val PLUGIN_ID = Regex("^[A-Za-z0-9._-]{1,128}$")

    private fun own(context: Context) = File(Computers.folder(context), "announce-own.json")
    private fun announced(context: Context) = File(Computers.folder(context), "announced.json")
    private fun served(context: Context) = File(Computers.folder(context), "served.json")

    @Volatile
    private var watcher: FileObserver? = null

    /** The devices paired with this one, as [pluginId] sees them. */
    fun forPlugin(context: Context, pluginId: String, now: Long = System.currentTimeMillis()): List<Device> {
        val pushed = read(announced(context))
        val serving = read(served(context))
        return Computers.devices(context).map { device ->
            val fromPush = pushed.optJSONObject(device.id)
            val fromServe = serving.optJSONObject(device.id)
            // The newer of what it answered when this device announced, and what it announced to this device.
            val newest = listOfNotNull(fromPush, fromServe).maxByOrNull { it.optLong("ms") }
            val seen = maxOf(device.lastPathMs, fromPush?.optLong("ms") ?: 0L, fromServe?.optLong("ms") ?: 0L)
            Device(
                id = device.id,
                name = device.name,
                kind = device.kind,
                pairedAtMs = device.pairedAtMs,
                online = seen > 0 && now - seen < ONLINE_MS,
                announced = newest?.optJSONObject("values")?.optJSONObject(pluginId),
            )
        }
    }

    /** Why [value] cannot be announced for [pluginId], or null. */
    fun problem(pluginId: String, value: JSONObject?): String? = when {
        !PLUGIN_ID.matches(pluginId) -> "this plugin's id cannot be announced"
        value != null && value.toString().toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES -> "the value is over $MAX_VALUE_BYTES bytes"
        else -> null
    }

    /**
     * Keeps [value] as what [pluginId] announces (null withdraws it), serves the paired handhelds with it, and sends
     * every value of this device to every paired device. Blocks on the network: the broker runs it off the call.
     * Returns how many devices answered.
     */
    fun announce(context: Context, pluginId: String, value: JSONObject?): Int {
        problem(pluginId, value)?.let { throw IllegalArgumentException(it) }
        synchronized(this) {
            val values = read(own(context))
            if (value == null) values.remove(pluginId) else values.put(pluginId, value)
            write(own(context), values)
        }
        serve(context)
        return push(context)
    }

    /** Withdraws everything [pluginId] announced (the plugin was removed). */
    fun forget(context: Context, pluginId: String) {
        val had = synchronized(this) {
            val values = read(own(context))
            (values.remove(pluginId) != null).also { if (it) write(own(context), values) }
        }
        if (had) {
            serve(context)
            push(context)
        }
    }

    /**
     * A device was paired or forgotten: serve the handhelds as they are now, send this device's values to the newly
     * paired, and tell the plugins.
     */
    fun changed(context: Context) {
        synchronized(this) {
            val ids = Computers.devices(context).map { it.id }.toSet()
            for (file in listOf(announced(context), served(context))) {
                val all = read(file)
                val gone = all.keys().asSequence().filter { it !in ids }.toList()
                if (gone.isNotEmpty()) {
                    gone.forEach { all.remove(it) }
                    write(file, all)
                }
            }
        }
        serve(context)
        if (read(own(context)).length() > 0) push(context)
        onChanged?.invoke(context)
    }

    /** Sends this device's values to every paired device and keeps each one's answer; tells the plugins when one changed. */
    private fun push(context: Context): Int {
        val values = read(own(context))
        var answered = 0
        var differs = false
        for (device in Computers.devices(context)) {
            val reply = Computers.call(context, device, "announce", JSONObject().put("values", values))
            if (AgentNative.failure(reply) != null) continue
            answered++
            val theirs = reply.optJSONObject("announced") ?: JSONObject()
            synchronized(this) {
                val all = read(announced(context))
                val before = all.optJSONObject(device.id)?.optJSONObject("values")?.toString()
                if (before != theirs.toString()) differs = true
                all.put(device.id, JSONObject().put("values", theirs).put("ms", System.currentTimeMillis()))
                write(announced(context), all)
            }
        }
        if (differs) onChanged?.invoke(context)
        return answered
    }

    /** Serves the paired handhelds while there is something to give them, and stops when there is not. */
    @Synchronized
    fun serve(context: Context) {
        val handhelds = Computers.devices(context).filter { it.handheld }.map { it.id }
        val values = read(own(context))
        if (handhelds.isEmpty() || values.length() == 0 || !AgentNative.available) {
            watcher?.stopWatching()
            watcher = null
            AgentNative.call("serve_stop")
            return
        }
        val seed = DeviceIdentity.seed(context) ?: return
        val state = served(context).apply { parentFile?.mkdirs() }
        AgentNative.call(
            "serve_start",
            JSONObject()
                .put("seed", seed)
                .put("name", Computers.deviceName(context))
                .put("peers", JSONArray(handhelds))
                .put("values", values)
                .put("state", state.absolutePath),
        )
        if (watcher == null) {
            val appContext = context.applicationContext
            // The agent writes the state through a temporary file and a rename: the rename is the change.
            // The path constructor: the File one is API 29, and droidtop runs from 26.
            @Suppress("DEPRECATION")
            val made = object : FileObserver(state.parentFile!!.absolutePath, MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == state.name) onChanged?.invoke(appContext)
                }
            }
            made.startWatching()
            watcher = made
        }
    }

    private fun read(file: File): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun write(file: File, json: JSONObject) {
        AtomicWrite.text(file, json.toString())
    }
}
