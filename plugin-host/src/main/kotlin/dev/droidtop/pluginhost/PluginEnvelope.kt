package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * The closed set of error codes every call and reply uses (docs/plugin-api.md
 * 1.3). A plugin's own ordinary failure ("no network", "nothing found") is
 * an error reply with [FAILED], not a crash; that is the distinction
 * [PluginCrashPolicy] already keeps.
 */
enum class PluginErrorCode {
    INVALID_ARGS,
    UNSUPPORTED,
    NOT_FOUND,
    PERMISSION_DENIED,
    RATE_LIMITED,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    PROVIDER_CRASHED,
    CANCELLED,
    FAILED,
    ;

    companion object {
        fun fromId(id: String?): PluginErrorCode? = entries.firstOrNull { it.name == id }
    }
}

/**
 * Host to plugin, the v2 call envelope (docs/plugin-api.md 1.3):
 * `{contract, callId, deadlineMs, point, version, op, caller, surface, args}`.
 * [point] is an extension point (`library.sources`), an event, or
 * `api:<id>` for a call a provider serves for another plugin. [caller] is
 * `{kind:"host"}`, or for a brokered call the calling plugin's id, origin,
 * trust tier, only the grants that apply to this API, and a `via` chain.
 */
class PluginCall(
    val callId: String,
    val deadlineMs: Long,
    val point: String,
    val version: Int,
    val op: String,
    val caller: JSONObject = JSONObject().put("kind", "host"),
    val surface: JSONObject = JSONObject(),
    val args: JSONObject = JSONObject(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("contract", PLUGIN_CONTRACT_VERSION)
        .put("callId", callId)
        .put("deadlineMs", deadlineMs)
        .put("point", point)
        .put("version", version)
        .put("op", op)
        .put("caller", caller)
        .put("surface", surface)
        .put("args", args)

    /** The arguments as the flat string map a contract 1 capability call takes; nested values keep their JSON text. */
    fun argsAsStrings(): Map<String, String> = buildMap { args.keys().forEach { put(it, args.optString(it)) } }

    companion object {
        fun fromJson(text: String): PluginCall? = runCatching {
            val json = JSONObject(text)
            PluginCall(
                callId = json.optString("callId"),
                deadlineMs = json.optLong("deadlineMs", PluginRunner.CALL_TIMEOUT_MS),
                point = json.getString("point"),
                version = json.optInt("version", 1),
                op = json.optString("op"),
                caller = json.optJSONObject("caller") ?: JSONObject().put("kind", "host"),
                surface = json.optJSONObject("surface") ?: JSONObject(),
                args = json.optJSONObject("args") ?: JSONObject(),
            )
        }.getOrNull()
    }
}

/** A reply: `{ok:true, data}` or `{ok:false, error:{code, message}}`. */
class PluginReply private constructor(
    val ok: Boolean,
    val data: JSONObject,
    val code: PluginErrorCode?,
    val message: String?,
) {
    fun toJson(): JSONObject = if (ok) {
        JSONObject().put("ok", true).put("data", data)
    } else {
        JSONObject().put("ok", false).put("error", JSONObject().put("code", (code ?: PluginErrorCode.FAILED).name).put("message", message ?: ""))
    }

    /** The compact text that crosses the binder, or a FAILED reply when it would exceed [PluginRunner.MAX_RESULT_BYTES]. */
    fun encode(): String {
        val text = toJson().toString()
        return if (text.toByteArray(Charsets.UTF_8).size > PluginRunner.MAX_RESULT_BYTES) {
            PluginReply.error(PluginErrorCode.FAILED, "reply exceeds the size cap").toJson().toString()
        } else {
            text
        }
    }

    companion object {
        fun ok(data: JSONObject = JSONObject()): PluginReply = PluginReply(true, data, null, null)

        fun error(code: PluginErrorCode, message: String): PluginReply = PluginReply(false, JSONObject(), code, message)

        /** Reads a reply from a plugin. A reply that is not JSON of the right shape is FAILED, and an error code outside the closed set is read as FAILED. */
        fun parse(text: String?): PluginReply {
            if (text == null) return error(PluginErrorCode.FAILED, "plugin returned no reply")
            if (text.toByteArray(Charsets.UTF_8).size > PluginRunner.MAX_RESULT_BYTES) return error(PluginErrorCode.FAILED, "reply exceeds the size cap")
            return try {
                val json = JSONObject(text)
                if (json.optBoolean("ok", false)) {
                    ok(json.optJSONObject("data") ?: JSONObject())
                } else {
                    val err = json.optJSONObject("error")
                    error(PluginErrorCode.fromId(err?.optString("code")) ?: PluginErrorCode.FAILED, err?.optString("message").orEmpty())
                }
            } catch (e: Exception) {
                error(PluginErrorCode.FAILED, "malformed reply from plugin")
            }
        }
    }
}

/**
 * How a plugin that predates the v2 envelope answers it: the point maps to
 * the contract 1 capability it replaced ([LegacyManifest.CAPABILITY_POINTS]),
 * the op travels as an `op` argument, and the values of the old result
 * become the reply's data. A plugin with no such capability answers
 * UNSUPPORTED.
 */
object LegacyHandle {
    fun translate(plugin: DroidtopPlugin, call: PluginCall): PluginReply {
        val capability = LegacyManifest.capabilityForPoint(call.point)
            ?: return PluginReply.error(PluginErrorCode.UNSUPPORTED, "no handler for ${call.point}")
        val args = LinkedHashMap<String, String>()
        args["op"] = call.op
        args.putAll(call.argsAsStrings())
        val result = plugin.invoke(capability, PluginArgs(args))
        return if (result.ok) {
            PluginReply.ok(JSONObject().also { data -> result.values.forEach { (k, v) -> data.put(k, v) } })
        } else {
            PluginReply.error(PluginErrorCode.FAILED, result.error ?: "the plugin reported a failure")
        }
    }

    /**
     * Calls [DroidtopPlugin.handle], or translates when the plugin was
     * compiled before it existed: such a class has no `handle` to call and
     * the JVM throws AbstractMethodError, which is not a crash of the plugin.
     */
    fun dispatch(plugin: DroidtopPlugin, call: PluginCall): PluginReply = try {
        plugin.handle(call)
    } catch (e: AbstractMethodError) {
        translate(plugin, call)
    }
}
