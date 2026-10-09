package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The context sync ops (docs/plugin-api.md 3 F8): a plugin declares a context
 * with its fields and rules, reads and changes its records, starts a sync with
 * the paired computers and settles the fields both sides changed.
 */
internal object HostContextApis {
    private const val PAGE = 200
    private val DIRECTIONS = setOf("both", "to_device", "to_computer")
    private val RULES = setOf("device", "computer", "ask")
    private val KEY = Regex("^[A-Za-z0-9._:-]{1,128}$")

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun contextsOf(env: BrokerEnvironment): PluginContexts =
        env.contexts() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop has no context sync")

    private fun contextId(args: JSONObject): String {
        val id = args.optString("context")
        if (id !in PluginContexts.KNOWN) invalid("context is one of ${PluginContexts.KNOWN.keys.joinToString()}")
        return id
    }

    private fun key(args: JSONObject): String = args.optString("key").takeIf { KEY.matches(it) } ?: invalid("key is 1 to 128 of letters, digits, '.', '_', ':' and '-'")

    /** The declaration as the agent core reads it, checked. */
    private fun declaration(id: String, args: JSONObject): JSONObject {
        val fields = args.optJSONArray("fields") ?: invalid("fields is required")
        val out = JSONArray()
        for (i in 0 until fields.length()) {
            val f = fields.optJSONObject(i) ?: invalid("each field is an object")
            val name = f.optString("name").takeIf { KEY.matches(it) } ?: invalid("a field needs a name")
            val direction = f.optString("direction", "both").takeIf { it in DIRECTIONS } ?: invalid("direction is both, to_device or to_computer")
            val rule = f.optString("rule", "ask").takeIf { it in RULES } ?: invalid("rule is device, computer or ask")
            out.put(JSONObject().put("name", name).put("direction", direction).put("rule", rule))
        }
        val presence = args.optString("presence", "both").takeIf { it in DIRECTIONS } ?: invalid("presence is both, to_device or to_computer")
        return JSONObject().put("id", id).put("fields", out).put("presence", presence)
    }

    val ops: List<HostOp> = listOf(
        HostOp("context", "open", permission = "context.sync", target = { it.optString("context") }) { env, record, args ->
            val id = contextId(args)
            val state = contextsOf(env).open(record.manifest.id, id, declaration(id, args))
            JSONObject().put("records", state.records.length()).put("conflicts", state.conflicts)
        },
        // Records a page at a time: a large context does not fit one reply.
        HostOp("context", "get", permission = "context.sync", target = { it.optString("context") }) { env, record, args ->
            val id = contextId(args)
            val state = contextsOf(env).load(record.manifest.id, id) ?: invalid("open the $id context first")
            val keys = state.records.keys().asSequence().sorted().toList()
            val after = args.optString("after")
            val limit = args.optInt("limit", PAGE).coerceIn(1, PAGE)
            val page = keys.filter { after.isEmpty() || it > after }.take(limit)
            val records = JSONObject()
            page.forEach { records.put(it, state.records.get(it)) }
            JSONObject()
                .put("records", records)
                .put("next", if (page.size == limit && keys.last() != page.last()) page.last() else JSONObject.NULL)
                .put("conflicts", state.conflicts)
        },
        HostOp("context", "put", permission = "context.sync", target = { "${it.optString("context")} ${it.optString("key")}" }) { env, record, args ->
            val fields = args.optJSONObject("fields") ?: invalid("fields is required")
            contextsOf(env).put(record.manifest.id, contextId(args), key(args), fields)
            JSONObject().put("stored", true)
        },
        HostOp("context", "remove", permission = "context.sync", target = { "${it.optString("context")} ${it.optString("key")}" }) { env, record, args ->
            JSONObject().put("removed", contextsOf(env).remove(record.manifest.id, contextId(args), key(args)))
        },
        // Reaches the person's own computers: always in the activity log.
        HostOp("context", "sync", permission = "context.sync", alwaysAudit = true, target = { it.optString("context") }) { env, record, args ->
            contextsOf(env).sync(record.manifest.id, contextId(args))
        },
        HostOp("context", "resolve", permission = "context.sync", target = { "${it.optString("context")} ${it.optString("key")}" }) { env, record, args ->
            val keep = args.optString("keep").takeIf { it == "device" || it == "computer" } ?: invalid("keep is device or computer")
            val field = args.optString("field").takeIf { KEY.matches(it) } ?: invalid("field is required")
            JSONObject().put("settled", contextsOf(env).resolve(record.manifest.id, contextId(args), key(args), field, keep))
        },
    )
}
