package dev.droidtop.pluginhost

import dev.droidtop.net.peer.PairedDevices
import org.json.JSONArray
import org.json.JSONObject

/**
 * D21 (docs/plugin-api.md 3, `devices`, Droidtop/tracker#556, #553): the computers and handhelds the person paired
 * with droidtop, for a plugin that works with its own counterparts on them (the Syncthing plugin accepts exactly the
 * Syncthing devices its counterparts announce, so its own pairing is never shown). Under `devices.paired`. A plugin
 * sees, for each device, only what the same plugin id announced there; never an address, a key or another plugin's
 * value. The event `devices.changed` says a device was paired or forgotten, or an announced value changed.
 */
internal object HostDevicesApis {
    const val PERMISSION = "devices.paired"

    val ops: List<HostOp> = listOf(
        HostOp("devices", "list_paired", permission = PERMISSION) { env, record, _ ->
            JSONObject().put("devices", toJson(env.pairedDevices(record.manifest.id)))
        },
        HostOp("devices", "announce", permission = PERMISSION, alwaysAudit = true) { env, record, args ->
            val value = when {
                !args.has("value") || args.isNull("value") -> null
                else -> args.optJSONObject("value") ?: throw BrokerException(PluginErrorCode.INVALID_ARGS, "value is an object, or null to withdraw it")
            }
            env.announceToDevices(record.manifest.id, value)
            JSONObject()
        },
    )

    /** The `devices` list as a plugin reads it. Pure. */
    fun toJson(devices: List<PairedDevices.Device>): JSONArray = JSONArray().apply {
        devices.forEach { d ->
            put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("kind", d.kind)
                    .put("pairedAt", d.pairedAtMs)
                    .put("online", d.online)
                    .put("announced", d.announced ?: JSONObject.NULL),
            )
        }
    }
}
