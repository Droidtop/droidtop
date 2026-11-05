package dev.droidtop.net.kdeconnect

import android.content.Context
import android.os.Build
import dev.droidtop.net.peer.AgentNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * droidtop as a KDE Connect device (docs/SPEC.md "KDE Connect", Droidtop/tracker#645): the official KDE Connect apps
 * on a phone or PC, and Pythia, find it, pair with it and talk to it. The device itself is lib-kdeconnect's, inside
 * droidtop's native library; this is its switch and its event loop, through `AgentNative.call("kdeconnect_<op>")`.
 *
 * It runs while the person has it on (Settings > Accounts and sources > KDE Connect), from droidtop's start, and is
 * the one part of the native library that runs between calls. Every call here is on Dispatchers.IO.
 */
object KdeConnect {
    private const val PREFS = "kdeconnect"
    private const val KEY_ON = "on"
    private const val KEY_NAME = "name"
    private const val KEY_ADDRESSES = "addresses"

    /** How long one `event` call waits in the library before the loop asks again. */
    private const val EVENT_WAIT_MS = 30_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var loop: Job? = null

    private val _running = MutableStateFlow(false)

    /** Whether the device is on now. */
    val running: StateFlow<Boolean> = _running

    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Emits after anything the device list shows changed: the Settings screen re-reads then. */
    val changes: SharedFlow<Long> = _changes

    private val _events = MutableSharedFlow<KdcEvent>(extraBufferCapacity = 32)

    /** What arrived, for the notices (pair requests, pings) and the screens. */
    val events: SharedFlow<KdcEvent> = _events

    /** Why the last start failed, in words for the screen, or null. */
    @Volatile
    var lastError: String? = null
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /** The name other devices show; the device's own model name until the person changes it. */
    fun name(context: Context): String = prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()

    fun defaultName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.distinct()
        .joinToString(" ").ifBlank { "droidtop" }

    /** Devices added by address (one IP each), announced to besides the network's broadcast. */
    fun addresses(context: Context): List<String> = parseAddresses(prefs(context).getString(KEY_ADDRESSES, "") ?: "")

    /** Splits what the person typed into addresses: commas, spaces or new lines between them. */
    fun parseAddresses(text: String): List<String> = text.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Starts the device when the person left it on. Called once at droidtop's start. */
    fun resume(context: Context) {
        val app = context.applicationContext
        scope.launch { if (isOn(app)) start(app) }
    }

    /** Switches it on or off and remembers the choice. */
    suspend fun setOn(context: Context, on: Boolean) = withContext(Dispatchers.IO) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
        if (on) start(context.applicationContext) else stop()
    }

    suspend fun setName(context: Context, name: String) = withContext(Dispatchers.IO) {
        prefs(context).edit().putString(KEY_NAME, name.trim()).apply()
        // The name is in the identity the others keep: restart so they see the new one.
        if (_running.value) {
            stop()
            start(context.applicationContext)
        }
    }

    suspend fun setAddresses(context: Context, text: String) = withContext(Dispatchers.IO) {
        val list = parseAddresses(text)
        prefs(context).edit().putString(KEY_ADDRESSES, list.joinToString(", ")).apply()
        if (_running.value) {
            call("addresses", JSONObject().put("addresses", JSONArray(list)))
            call("announce")
        }
    }

    private fun call(op: String, args: JSONObject = JSONObject()): JSONObject = AgentNative.call("kdeconnect_$op", args)

    private suspend fun start(context: Context) = lock.withLock {
        if (_running.value) return@withLock
        val dir = File(context.filesDir, "kdeconnect")
        // "tablet": a handheld with a screen of its own. The official apps choose what to offer by capability, not by type.
        val reply = call(
            "start",
            JSONObject().put("state_dir", dir.absolutePath).put("name", name(context)).put("type", "tablet"),
        )
        lastError = AgentNative.failure(reply)
        if (lastError != null) {
            _changes.tryEmit(System.nanoTime())
            return@withLock
        }
        call("addresses", JSONObject().put("addresses", JSONArray(addresses(context))))
        _running.value = true
        _changes.tryEmit(System.nanoTime())
        loop = scope.launch {
            while (isActive && _running.value) {
                val e = call("event", JSONObject().put("timeout_ms", EVENT_WAIT_MS))
                if (AgentNative.failure(e) != null) break
                val event = KdcEvent.parse(e) ?: continue
                _events.tryEmit(event)
                _changes.tryEmit(System.nanoTime())
            }
        }
    }

    private suspend fun stop() = lock.withLock {
        if (!_running.value) return@withLock
        _running.value = false
        call("stop")
        loop?.cancel()
        loop = null
        _changes.tryEmit(System.nanoTime())
    }

    /** Every device seen this run and every paired one. Empty while it is off. */
    suspend fun devices(): List<KdcDevice> = withContext(Dispatchers.IO) {
        if (!_running.value) return@withContext emptyList()
        KdcDevice.parseList(call("devices"))
    }

    suspend fun refresh(): String? = op("announce", null)
    suspend fun pair(id: String): String? = op("pair", id)
    suspend fun accept(id: String): String? = op("accept", id)
    suspend fun refuse(id: String): String? = op("refuse", id)
    suspend fun unpair(id: String): String? = op("unpair", id)

    suspend fun ping(id: String): String? = withContext(Dispatchers.IO) {
        AgentNative.failure(call("ping", JSONObject().put("id", id).put("message", "Ping from droidtop")))
    }

    /** Runs one operation on a device; the failure in words, or null. */
    private suspend fun op(op: String, id: String?): String? = withContext(Dispatchers.IO) {
        val args = JSONObject()
        if (id != null) args.put("id", id)
        AgentNative.failure(call(op, args)).also { _changes.tryEmit(System.nanoTime()) }
    }
}

/** A KDE Connect device as lib-kdeconnect's `devices` reports it. */
data class KdcDevice(
    val id: String,
    val name: String,
    val type: String,
    val reachable: Boolean,
    val paired: Boolean,
    /** `none`, `requested` (droidtop asked) or `requested_by_peer`. */
    val pairing: String,
    val verificationKey: String?,
    val address: String?,
) {
    companion object {
        fun parse(o: JSONObject) = KdcDevice(
            id = o.optString("id"),
            name = o.optString("name"),
            type = o.optString("type"),
            reachable = o.optBoolean("reachable"),
            paired = o.optBoolean("paired"),
            pairing = o.optString("pairing", "none"),
            verificationKey = o.optString("verification_key").takeIf { it.isNotEmpty() && !o.isNull("verification_key") },
            address = o.optString("address").takeIf { it.isNotEmpty() && !o.isNull("address") },
        )

        fun parseList(reply: JSONObject): List<KdcDevice> {
            val a = reply.optJSONArray("devices") ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parse) }
        }
    }
}

/** What happened on the KDE Connect side, as far as droidtop shows it. */
sealed class KdcEvent {
    data class PairRequest(val id: String, val name: String, val key: String) : KdcEvent()
    data class Paired(val id: String) : KdcEvent()
    data class PairFailed(val id: String, val reason: String) : KdcEvent()
    data class Ping(val id: String, val message: String?) : KdcEvent()
    /** Anything else (reachable, unreachable, unpaired): only the device list changes. */
    data class Other(val kind: String) : KdcEvent()

    companion object {
        /** One `event` reply; null for `none`. */
        fun parse(o: JSONObject): KdcEvent? = when (val kind = o.optString("event")) {
            "none", "" -> null
            "pair_request" -> PairRequest(o.optString("id"), o.optString("name"), o.optString("key"))
            "paired" -> Paired(o.optString("id"))
            "pair_failed" -> PairFailed(o.optString("id"), o.optString("reason"))
            "plugin" -> {
                val p = o.optJSONObject("plugin")
                when (p?.optString("plugin")) {
                    "ping" -> Ping(o.optString("id"), p.optString("message").takeIf { it.isNotEmpty() && !p.isNull("message") })
                    else -> Other("plugin")
                }
            }
            else -> Other(kind)
        }
    }
}
