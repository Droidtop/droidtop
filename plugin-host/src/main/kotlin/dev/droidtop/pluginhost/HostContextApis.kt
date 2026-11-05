package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The context sync ops (docs/plugin-api.md 3 F8): a plugin declares a context
 * with its fields and rules, publishes a whole copy of it and reads the current
 * copy back (protocol 2, syncable data, Droidtop/tracker#794), or changes its
 * records one at a time (protocol 1), starts a sync with the paired computers
 * and settles the fields both sides changed.
 */
internal object HostContextApis {
    private const val PAGE = 200

    /** A copy crosses the broker whole, so it is held to what one reply carries. */
    private const val MAX_RECORDS = 5000
    private val DIRECTIONS = setOf("both", "to_device", "to_computer")
    private val RULES = setOf("device", "computer", "ask")
    private val KEY = Regex("^[A-Za-z0-9._:-]{1,128}$")

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun contextsOf(env: BrokerEnvironment): PluginContexts =
        env.contexts() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop has no context sync")

    /** The point a plugin declares each context's adapter in: the program droidtop-agent needs on the computer. */
    const val ADAPTER_POINT = "computers.context_adapter"

    /** The adapter [manifest] declares for [context], or null. */
    fun adapterFor(manifest: PluginManifest, context: String): ProvidedPoint? =
        manifest.v2.provides.firstOrNull { it.point == ADAPTER_POINT && it.id == context && ExtensionPoints.supports(it.point, it.version) }

    /**
     * The offer droidtop sends the computer for [context]: the plugin, and the
     * program for each system with its SHA-256, as the signed manifest states
     * them. The computer fetches it only after the person approves it there.
     */
    fun adapterOffer(manifest: PluginManifest, context: String): JSONObject? {
        val point = adapterFor(manifest, context) ?: return null
        val programs = runCatching { JSONObject(point.extra).optJSONObject("programs") }.getOrNull() ?: JSONObject()
        return JSONObject().put("plugin", manifest.id).put("label", point.label ?: manifest.label).put("programs", programs)
    }

    /** A context the plugin may use: one its manifest declares an adapter for, or one of its data kinds (H3). */
    private fun contextId(manifest: PluginManifest, args: JSONObject): String {
        val id = args.optString("context")
        if (adapterFor(manifest, id) == null && dataKind(manifest, id) == null) {
            invalid("context is one the plugin's manifest declares in $ADAPTER_POINT or in dataKinds")
        }
        return id
    }

    /** [manifest]'s declared data kind [id] (docs/plugin-api.md 3 H3), or null. */
    fun dataKind(manifest: PluginManifest, id: String): DataKind? = manifest.v2.dataKinds.firstOrNull { it.kind == id }

    /** The permission a context op needs: `data.kinds` for a data kind with no computer adapter, `context.sync` otherwise. */
    private val permissionOf: (List<DeclaredPermission>, JSONObject, BrokerEnvironment) -> String = { declared, args, _ ->
        val id = args.optString("context")
        if (declared.any { it.id == DATA_KINDS } && declared.none { it.id == "context.sync" } && id.isNotBlank()) DATA_KINDS else "context.sync"
    }

    const val DATA_KINDS = "data.kinds"

    /** The files a published copy carries (H3): `[{name, size, sha256}]`, each written with `context.stage` first. */
    private fun files(args: JSONObject): List<PluginContexts.CopyFile> {
        val array = args.optJSONArray("files") ?: return emptyList()
        return (0 until array.length()).map { i ->
            array.optJSONObject(i)?.let(PluginContexts.CopyFile::fromJson)
                ?: invalid("each file is {name, size, sha256}: ${PluginContexts.FILE_NAME_RULE}, its size in bytes, its SHA-256 in hex")
        }
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
        HostOp("context", "open", permissionFor = permissionOf, target = { it.optString("context") }) { env, record, args ->
            val id = contextId(record.manifest, args)
            val state = contextsOf(env).open(record.manifest.id, id, declaration(id, args))
            JSONObject().put("records", state.records.length()).put("conflicts", state.conflicts)
        },
        // Records a page at a time: a large context does not fit one reply.
        HostOp("context", "get", permissionFor = permissionOf, target = { it.optString("context") }) { env, record, args ->
            val id = contextId(record.manifest, args)
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
        // Protocol 2: the whole copy, made current in one step, then synced. Records as `context.get` gives them.
        HostOp("context", "publish", permissionFor = permissionOf, alwaysAudit = true, target = { it.optString("context") }) { env, record, args ->
            val id = contextId(record.manifest, args)
            val records = args.optJSONObject("records") ?: invalid("records is required: {key: {field: value}}")
            if (records.length() > MAX_RECORDS) invalid("a copy holds at most $MAX_RECORDS records")
            records.keys().forEach { k -> if (!KEY.matches(k) || records.optJSONObject(k) == null) invalid("each record is {field: value} under a key of 1 to 128 of letters, digits, '.', '_', ':' and '-'") }
            val contexts = contextsOf(env)
            val copy = contexts.publish(record.manifest.id, id, records, files(args))
            // A data kind with no computer adapter is not synced with computers: it moves only when the person asks.
            if (adapterFor(record.manifest, id) == null) {
                JSONObject().put("published", copy.generation).put("files", copy.files.size)
            } else {
                contexts.sync(record.manifest.id, id, adapterOffer(record.manifest, id)).put("published", copy.generation)
            }
        },
        // The current copy, for the plugin to bring in when that is safe; null before the first publish.
        HostOp("context", "copy", permissionFor = permissionOf, target = { it.optString("context") }) { env, record, args ->
            val id = contextId(record.manifest, args)
            val copy = contextsOf(env).copy(record.manifest.id, id)
            if (copy == null) {
                JSONObject().put("generation", JSONObject.NULL).put("records", JSONObject()).put("files", JSONArray())
            } else {
                JSONObject().put("generation", copy.generation).put("records", copy.records).put("files", JSONArray(copy.files.map { it.toJson() }))
            }
        },
        // H3: a write descriptor for one file of the next copy, in droidtop's staging folder; the plugin never sees a path.
        HostOp(
            "context", "stage", permissionFor = permissionOf,
            target = { "${it.optString("context")} ${it.optString("name")}" },
            open = { env, record, args ->
                val file = contextsOf(env).stagingFile(record.manifest.id, contextId(record.manifest, args), args.optString("name"))
                android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE,
                )
            },
        ),
        // H3: a read descriptor for one file of the current copy, its own or one droidtop moved to it from another plugin.
        HostOp(
            "context", "open_file", permissionFor = permissionOf,
            target = { "${it.optString("context")} ${it.optString("name")}" },
            open = { env, record, args ->
                val file = contextsOf(env).copyFileOf(record.manifest.id, contextId(record.manifest, args), args.optString("name"))
                    ?: throw BrokerException(PluginErrorCode.NOT_FOUND, "the current copy has no file by that name")
                android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            },
        ),
        HostOp("context", "put", permission = "context.sync", target = { "${it.optString("context")} ${it.optString("key")}" }) { env, record, args ->
            val fields = args.optJSONObject("fields") ?: invalid("fields is required")
            contextsOf(env).put(record.manifest.id, contextId(record.manifest, args), key(args), fields)
            JSONObject().put("stored", true)
        },
        HostOp("context", "remove", permission = "context.sync", target = { "${it.optString("context")} ${it.optString("key")}" }) { env, record, args ->
            JSONObject().put("removed", contextsOf(env).remove(record.manifest.id, contextId(record.manifest, args), key(args)))
        },
        // Reaches the person's own computers: always in the activity log.
        HostOp("context", "sync", permission = "context.sync", alwaysAudit = true, target = { it.optString("context") }) { env, record, args ->
            val id = contextId(record.manifest, args)
            contextsOf(env).sync(record.manifest.id, id, adapterOffer(record.manifest, id))
        },
        HostOp("context", "resolve", permission = "context.sync", target = { "${it.optString("context")} ${it.optString("key")}" }) { env, record, args ->
            val keep = args.optString("keep").takeIf { it == "device" || it == "computer" } ?: invalid("keep is device or computer")
            val field = args.optString("field").takeIf { KEY.matches(it) } ?: invalid("field is required")
            JSONObject().put("settled", contextsOf(env).resolve(record.manifest.id, contextId(record.manifest, args), key(args), field, keep))
        },
    )
}
