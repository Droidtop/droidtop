package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The contract-2 manifest fields (docs/plugin-api.md 1.2): what a plugin
 * provides, what host permissions it asks for, which events it
 * subscribes to, and the plugin-provided APIs it exports or requires.
 *
 * Every entry keeps the ids exactly as written, known or not: whether an
 * id is supported is the registries' question ([PluginPermissions],
 * [ExtensionPoints]), asked at install and on the approval screen, never
 * decided by dropping the entry here. Fields an entry carries beyond the
 * typed ones (a tile's surfaces, a permission's domain list) stay as
 * compact JSON text in `extra`, so a data class compares by value and a
 * round trip loses nothing.
 */
data class V2Declarations(
    val provides: List<ProvidedPoint> = emptyList(),
    val permissions: List<DeclaredPermission> = emptyList(),
    val subscribes: List<EventSubscription> = emptyList(),
    val exports: List<ExportedApi> = emptyList(),
    val requires: List<RequiredApi> = emptyList(),
) {
    /** Writes the five arrays into [json], the same keys [fromJson] reads, so the manifest and [PluginRecord] share one mechanism. */
    fun putInto(json: JSONObject) {
        json.put("provides", JSONArray(provides.map { it.toJson() }))
        json.put("permissions", JSONArray(permissions.map { it.toJson() }))
        json.put("subscribes", JSONArray(subscribes.map { it.toJson() }))
        json.put("exports", JSONArray(exports.map { it.toJson() }))
        json.put("requires", JSONArray(requires.map { it.toJson() }))
    }

    companion object {
        val EMPTY = V2Declarations()

        fun fromJson(json: JSONObject): V2Declarations = V2Declarations(
            provides = objects(json, "provides").mapNotNull { ProvidedPoint.fromJson(it) },
            permissions = objects(json, "permissions").mapNotNull { DeclaredPermission.fromJson(it) },
            subscribes = objects(json, "subscribes").mapNotNull { EventSubscription.fromJson(it) },
            exports = objects(json, "exports").mapNotNull { ExportedApi.fromJson(it) },
            requires = objects(json, "requires").mapNotNull { RequiredApi.fromJson(it) },
        )

        private fun objects(json: JSONObject, key: String): List<JSONObject> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return buildList { for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(it) } }
        }
    }
}

/** A field that may be JSON null: org.json's `optString` returns the text "null" for it (see [PluginManifest]'s own copy of this note). */
internal fun JSONObject.optNullable(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/** Every key of this object not in [known], as compact JSON with the keys sorted so equal content gives equal text. */
private fun JSONObject.rest(known: Set<String>): String {
    val out = JSONObject()
    keys().asSequence().filter { it !in known }.sorted().forEach { out.put(it, get(it)) }
    return out.toString()
}

/** Merges the JSON text kept in `extra` back into [into]; a typed key already there wins. */
private fun mergeExtra(into: JSONObject, extra: String) {
    val json = runCatching { JSONObject(extra) }.getOrNull() ?: return
    json.keys().forEach { if (!into.has(it)) into.put(it, json.get(it)) }
}

/**
 * One extension point the plugin implements. [version] is the major the
 * plugin speaks; [id] tells apart two entries for one point (two status
 * tiles). Static fields (`surfaces`, `target`, ...) live in [extra].
 */
data class ProvidedPoint(
    val point: String,
    val version: Int = 1,
    val id: String? = null,
    val label: String? = null,
    val extra: String = "{}",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("point", point)
        put("version", version)
        id?.let { put("id", it) }
        label?.let { put("label", it) }
        mergeExtra(this, extra)
    }

    /** The `surfaces` a plugin declared for this entry (`gaming.quick_menu`, ...), empty when it declared none. */
    fun surfaces(): List<String> {
        val array = runCatching { JSONObject(extra).optJSONArray("surfaces") }.getOrNull() ?: return emptyList()
        return buildList { for (i in 0 until array.length()) add(array.optString(i)) }
    }

    companion object {
        private val KNOWN = setOf("point", "version", "id", "label")

        fun fromJson(json: JSONObject): ProvidedPoint? {
            val point = json.optString("point").takeIf { it.isNotBlank() } ?: return null
            return ProvidedPoint(
                point = point,
                version = json.optInt("version", 1),
                id = json.optNullable("id"),
                label = json.optNullable("label"),
                extra = json.rest(KNOWN),
            )
        }
    }
}

/**
 * One host permission the plugin may use. `required` means the plugin
 * cannot do its main job without it; a parameterised permission
 * (`net.domains`, `apps.bind`) keeps its parameters in [extra]. The ids
 * `provide:<point>` are consent items for providing a high-risk point.
 */
data class DeclaredPermission(
    val id: String,
    val reason: String? = null,
    val required: Boolean = false,
    val extra: String = "{}",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        reason?.let { put("reason", it) }
        if (required) put("required", true)
        mergeExtra(this, extra)
    }

    companion object {
        private val KNOWN = setOf("id", "reason", "required")

        fun fromJson(json: JSONObject): DeclaredPermission? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            return DeclaredPermission(
                id = id,
                reason = json.optNullable("reason"),
                required = json.optBoolean("required", false),
                extra = json.rest(KNOWN),
            )
        }
    }
}

/** One event the plugin wants, with the event's version. */
data class EventSubscription(val event: String, val version: Int = 1) {
    fun toJson(): JSONObject = JSONObject().put("event", event).put("version", version)

    companion object {
        fun fromJson(json: JSONObject): EventSubscription? {
            val event = json.optString("event").takeIf { it.isNotBlank() } ?: return null
            return EventSubscription(event, json.optInt("version", 1))
        }
    }
}

/** One op of an exported API: exactly one permission, and whether it is a job. */
data class ExportedOp(val op: String, val permission: String, val job: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("op", op).put("permission", permission).put("job", job)

    companion object {
        fun fromJson(json: JSONObject): ExportedOp? {
            val op = json.optString("op").takeIf { it.isNotBlank() } ?: return null
            return ExportedOp(op, json.optString("permission"), json.optBoolean("job", false))
        }
    }
}

/** A permission a provider declares inside its own namespace (docs/plugin-api.md 2.2). */
data class ProvidedPermission(val id: String, val risk: String, val label: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("risk", risk).put("label", label)

    companion object {
        fun fromJson(json: JSONObject): ProvidedPermission? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            return ProvidedPermission(id, json.optString("risk").ifBlank { "high" }, json.optString("label").ifBlank { id })
        }
    }
}

/** An API this plugin offers other plugins. [version] is `major.minor`; [attributes] is compact JSON (`{"level":"adb"}`). */
data class ExportedApi(
    val api: String,
    val version: String = "1.0",
    val attributes: String = "{}",
    val ops: List<ExportedOp> = emptyList(),
    val permissions: List<ProvidedPermission> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("api", api)
        put("version", version)
        put("attributes", runCatching { JSONObject(attributes) }.getOrDefault(JSONObject()))
        put("ops", JSONArray(ops.map { it.toJson() }))
        put("permissions", JSONArray(permissions.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): ExportedApi? {
            val api = json.optString("api").takeIf { it.isNotBlank() } ?: return null
            val ops = json.optJSONArray("ops")
            val perms = json.optJSONArray("permissions")
            return ExportedApi(
                api = api,
                version = json.optString("version").ifBlank { "1.0" },
                attributes = (json.optJSONObject("attributes") ?: JSONObject()).rest(emptySet()),
                ops = buildList { if (ops != null) for (i in 0 until ops.length()) ops.optJSONObject(i)?.let { ExportedOp.fromJson(it) }?.let { add(it) } },
                permissions = buildList { if (perms != null) for (i in 0 until perms.length()) perms.optJSONObject(i)?.let { ProvidedPermission.fromJson(it) }?.let { add(it) } },
            )
        }
    }
}

/** An API this plugin uses from another plugin. Calls also need the matching permission in the caller's own `permissions`. */
data class RequiredApi(
    val api: String,
    val version: String = "1.0",
    val optional: Boolean = false,
    val minLevel: String? = null,
    val reason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("api", api)
        put("version", version)
        put("optional", optional)
        minLevel?.let { put("minLevel", it) }
        reason?.let { put("reason", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): RequiredApi? {
            val api = json.optString("api").takeIf { it.isNotBlank() } ?: return null
            return RequiredApi(
                api = api,
                version = json.optString("version").ifBlank { "1.0" },
                optional = json.optBoolean("optional", false),
                minLevel = json.optNullable("minLevel"),
                reason = json.optNullable("reason"),
            )
        }
    }
}
