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

    /**
     * The source needs the person signed in on its site. droidtop offers its own "Sign in" (the plugin's declared
     * `web.session` sign-in page, [PluginWebAccount]); an older reply's `action` is still offered when there is none.
     */
    SIGN_IN_REQUIRED,

    /** The site shows a browser check (a "checking your browser" page) first: droidtop offers "Open the site" in the plugin's session. */
    BROWSER_CHECK_REQUIRED,
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
    /**
     * What this droidtop can draw, for a plugin to test without a helper library (docs/plugin-api.md 1.6):
     * `viewFeatures` lists the view features it renders ("notice", "confirm", ...), so a plugin keeps its old form
     * for an older droidtop.
     */
    val host: JSONObject = hostInfo(),
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
        .put("host", host)

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
                host = json.optJSONObject("host") ?: JSONObject(),
            )
        }.getOrNull()

        /** The `host` object every call carries: the view features and, since contract 3, the facet kinds and targets it draws. */
        fun hostInfo(): JSONObject = JSONObject()
            .put("viewFeatures", org.json.JSONArray(PluginViewCall.VIEW_FEATURES))
            .put("facets", FacetGuards.hostInfo())
    }
}

/**
 * A reply: `{ok:true, data}` or `{ok:false, error:{code, message, title?, subtitle?, permission?, action?}}`. The
 * error's [title] and [subtitle] are what the person reads (droidtop draws them as a caution notice), [permission]
 * names the declared permission a PERMISSION_DENIED is about (its notice then opens that grant), and [action] is a
 * view action (a SIGN_IN_REQUIRED's sign-in), docs/plugin-api.md 1.3.
 */
class PluginReply private constructor(
    val ok: Boolean,
    val data: JSONObject,
    val code: PluginErrorCode?,
    val message: String?,
    val title: String? = null,
    val subtitle: String? = null,
    val permission: String? = null,
    val action: JSONObject? = null,
    /**
     * What the plugin saw, for the "Technical details" row behind the plain sentence (docs/plugin-api.md 1.3): never
     * on a row, markup stripped and bounded by droidtop. The message itself is one plain sentence for the person.
     */
    val detail: String? = null,
) {
    /**
     * This failure as one about [permission] (Droidtop/tracker#518): a plugin passed droidtop's refusal of a declared
     * permission on without naming it, and the page names it for the person.
     */
    fun aboutPermission(permission: String, title: String, subtitle: String?): PluginReply =
        PluginReply(ok, data, code, message, title, subtitle, permission, action, detail)

    fun toJson(): JSONObject = if (ok) {
        JSONObject().put("ok", true).put("data", data)
    } else {
        JSONObject().put("ok", false).put(
            "error",
            JSONObject().put("code", (code ?: PluginErrorCode.FAILED).name).put("message", message ?: "").apply {
                title?.let { put("title", it) }
                subtitle?.let { put("subtitle", it) }
                permission?.let { put("permission", it) }
                action?.let { put("action", it) }
                detail?.let { put("detail", it) }
            },
        )
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
        /** What a permission id looks like (`web.session`, `library.folders.write`). */
        private val PERMISSION_ID = Regex("[a-z][a-z0-9_]*(\\.[a-z0-9_]+){0,5}")

        fun ok(data: JSONObject = JSONObject()): PluginReply = PluginReply(true, data, null, null)

        /** An error with [message] as its sentence: cleaned ([PluginText]), since a runner may pass a plugin's own words here (an exception's text). */
        fun error(code: PluginErrorCode, message: String): PluginReply =
            PluginReply(false, JSONObject(), code, PluginText.clean(message, PluginText.DETAIL_MAX).orEmpty())

        /** An error the person reads: [title] and [subtitle] in plain words, the [permission] it is about, an [action] to offer. */
        fun error(code: PluginErrorCode, title: String, subtitle: String? = null, permission: String? = null, action: JSONObject? = null): PluginReply =
            PluginReply(false, JSONObject(), code, title, title, subtitle, permission, action)

        /** A failure the person reads as one plain [message], with what the code saw in [detail] for "Technical details". */
        fun failed(code: PluginErrorCode, message: String, detail: String?): PluginReply =
            PluginReply(false, JSONObject(), code, PluginText.clean(message, PluginText.DETAIL_MAX).orEmpty(), detail = PluginText.clean(detail, PluginText.DETAIL_MAX))

        /**
         * Reads a reply from a plugin. A reply that is not JSON of the right shape is FAILED, and an error code outside
         * the closed set is read as FAILED. An error's words are the plugin's, so they are cleaned here, where they
         * enter droidtop ([PluginText]), and the permission it names must look like a permission id.
         */
        fun parse(text: String?): PluginReply {
            if (text == null) return error(PluginErrorCode.FAILED, "plugin returned no reply")
            if (text.toByteArray(Charsets.UTF_8).size > PluginRunner.MAX_RESULT_BYTES) return error(PluginErrorCode.FAILED, "reply exceeds the size cap")
            return try {
                val json = JSONObject(text)
                if (json.optBoolean("ok", false)) {
                    ok(json.optJSONObject("data") ?: JSONObject())
                } else {
                    val err = json.optJSONObject("error")
                    fun text(key: String, max: Int) = err?.takeUnless { it.isNull(key) }?.optString(key)?.let { PluginText.clean(it, max) }
                    PluginReply(
                        false,
                        JSONObject(),
                        PluginErrorCode.fromId(err?.optString("code")) ?: PluginErrorCode.FAILED,
                        text("message", PluginText.MESSAGE_MAX).orEmpty(),
                        title = text("title", PluginText.TITLE_MAX),
                        subtitle = text("subtitle", PluginText.MESSAGE_MAX),
                        permission = err?.takeUnless { it.isNull("permission") }?.optString("permission")?.trim()?.takeIf { PERMISSION_ID.matches(it) },
                        action = err?.optJSONObject("action"),
                        detail = text("detail", PluginText.DETAIL_MAX),
                    )
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
