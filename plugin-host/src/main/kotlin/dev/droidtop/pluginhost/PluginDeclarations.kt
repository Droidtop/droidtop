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
    /** The data kinds it can hand to, and take from, an equivalent plugin ([DataKind], docs/plugin-api.md 3 H3). */
    val dataKinds: List<DataKind> = emptyList(),
    /** Its settings, declared ahead and drawn by droidtop with no call ([DeclaredSetting], Droidtop/tracker#880). */
    val settings: List<DeclaredSetting> = emptyList(),
) {
    /** Writes the five arrays into [json], the same keys [fromJson] reads, so the manifest and [PluginRecord] share one mechanism. */
    fun putInto(json: JSONObject) {
        json.put("provides", JSONArray(provides.map { it.toJson() }))
        json.put("permissions", JSONArray(permissions.map { it.toJson() }))
        json.put("subscribes", JSONArray(subscribes.map { it.toJson() }))
        json.put("exports", JSONArray(exports.map { it.toJson() }))
        json.put("requires", JSONArray(requires.map { it.toJson() }))
        if (dataKinds.isNotEmpty()) json.put("dataKinds", JSONArray(dataKinds.map { it.toJson() }))
        if (settings.isNotEmpty()) json.put("settings", JSONArray(settings.map { it.toJson() }))
    }

    companion object {
        val EMPTY = V2Declarations()

        fun fromJson(json: JSONObject): V2Declarations = V2Declarations(
            provides = objects(json, "provides").mapNotNull { ProvidedPoint.fromJson(it) },
            // One entry per permission: an Android name and its host id are the same permission, and the first one written counts.
            permissions = objects(json, "permissions").mapNotNull { DeclaredPermission.fromJson(it) }.distinctBy { it.id },
            subscribes = objects(json, "subscribes").mapNotNull { EventSubscription.fromJson(it) },
            exports = objects(json, "exports").mapNotNull { ExportedApi.fromJson(it) },
            requires = objects(json, "requires").mapNotNull { RequiredApi.fromJson(it) },
            dataKinds = objects(json, "dataKinds").mapNotNull { DataKind.fromJson(it) }.distinctBy { it.kind },
            settings = objects(json, "settings").mapNotNull { DeclaredSetting.fromJson(it) }.distinctBy { it.id }.take(DeclaredSetting.MAX_ROWS),
        )

        private fun objects(json: JSONObject, key: String): List<JSONObject> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return buildList { for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(it) } }
        }
    }
}

/**
 * A named, versioned kind of data a plugin can export and import (docs/plugin-api.md 3 H3, Droidtop/tracker#793):
 * `syncthing.state` version 1 is a Syncthing node's identity, configuration and index. Plugins that declare the same
 * [kind] and [version] are equivalent for it, whether native or an app's. A data kind is a context of the plugin
 * (F8) whose copy carries files beside its records; droidtop moves a copy whole from one plugin to another.
 */
data class DataKind(val kind: String, val version: Int, val export: Boolean, val import: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("version", version).put("export", export).put("import", import)

    /** Whether [other] can take this kind from a plugin declaring this one. */
    fun feeds(other: DataKind): Boolean = export && other.import && kind == other.kind && version == other.version

    companion object {
        /** A kind's name: lowercase segments joined by dots, the shape context ids already take. */
        val NAME = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

        fun fromJson(json: JSONObject): DataKind? {
            val kind = json.optString("kind").takeIf { NAME.matches(it) } ?: return null
            val version = json.optInt("version", 0).takeIf { it >= 1 } ?: return null
            return DataKind(kind, version, json.optBoolean("export", false), json.optBoolean("import", false))
        }
    }
}

/**
 * One row of a plugin's settings, declared in its signed manifest and drawn by droidtop (Droidtop/tracker#880). The
 * owner: "The plugin should have exposed a Settings facet, or exposed things in advance. It should not have to be
 * asked or loaded for any of it". Opening the page never starts or calls the plugin.
 *
 * - [Kind.INPUT]: a value the person sets, kept by droidtop ([PluginSettings]). [input] is `toggle`, `choice` (one of
 *   [choices]) or `text`, with [default].
 * - [Kind.ACTION]: pressed, it runs the plugin's job [op] on `ui.settings`. That is the person's action, not the page's.
 * - [Kind.INFO]: a line. Its value can be text the plugin pushed while it ran (`settings.set_text`).
 *
 * [whenCondition] (`when`) shows the row only while a state droidtop already knows holds: `web.session.signed_in`, `permission:<id>`
 * granted, `computer.paired`, each optionally prefixed with `not `.
 */
data class DeclaredSetting(
    val id: String,
    val kind: Kind,
    val label: String,
    val sub: String? = null,
    val input: String? = null,
    val choices: List<Pair<String, String>> = emptyList(),
    val default: Any? = null,
    val op: String? = null,
    val whenCondition: String? = null,
) {
    enum class Kind(val id: String) { INPUT("input"), ACTION("action"), INFO("info") }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.id).put("label", label).apply {
        sub?.let { put("sub", it) }
        input?.let { put("input", it) }
        if (choices.isNotEmpty()) put("choices", JSONArray(choices.map { (v, l) -> JSONObject().put("value", v).put("label", l) }))
        default?.let { put("default", it) }
        op?.let { put("op", it) }
        whenCondition?.let { put("when", it) }
    }

    companion object {
        const val MAX_ROWS = 40
        val ID = Regex("^[a-z][a-z0-9_.]{0,63}$")
        val INPUTS = setOf("toggle", "choice", "text")
        val CONDITION = Regex("^(not )?(web\\.session\\.signed_in|computer\\.paired|permission:[a-z][a-z0-9_.]{0,63})$")

        fun fromJson(json: JSONObject): DeclaredSetting? {
            val id = json.optString("id").takeIf { ID.matches(it) } ?: return null
            val kind = Kind.entries.firstOrNull { it.id == json.optString("kind") } ?: return null
            val label = json.optString("label").trim().takeIf { it.isNotEmpty() && it.length <= 80 } ?: return null
            val sub = json.optNullable("sub")?.take(200)
            val condition = json.optNullable("when")?.takeIf { CONDITION.matches(it) }
            if (json.has("when") && condition == null) return null
            return when (kind) {
                Kind.INPUT -> {
                    val input = json.optString("input").takeIf { it in INPUTS } ?: return null
                    val choices = json.optJSONArray("choices")?.let { a ->
                        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> c.optString("value").takeIf { it.isNotBlank() }?.let { it to c.optString("label").ifBlank { it } } } }
                    }.orEmpty()
                    if (input == "choice" && choices.isEmpty()) return null
                    val default: Any? = when (input) {
                        "toggle" -> json.optBoolean("default", false)
                        "choice" -> json.optString("default").takeIf { d -> choices.any { it.first == d } } ?: choices.first().first
                        else -> json.optString("default", "").take(MAX_TEXT)
                    }
                    DeclaredSetting(id, kind, label, sub, input, choices, default, whenCondition = condition)
                }
                Kind.ACTION -> {
                    val op = json.optString("op").takeIf { HostOpNames.check(it) == null } ?: return null
                    DeclaredSetting(id, kind, label, sub, op = op, whenCondition = condition)
                }
                Kind.INFO -> DeclaredSetting(id, kind, label, sub, whenCondition = condition)
            }
        }

        /** A text setting's longest value. */
        const val MAX_TEXT = 1000
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

    /**
     * How droidtop names this entry to the person (Droidtop/tracker#521 item 3): the point's own name, then the entry's
     * `label` when it has one ("Quick Menu tile: Save state"), cut at [LABEL_MAX]. The point's name always leads, so a
     * plugin's title can say which of its entries this is but never pose as a different point.
     */
    fun titled(pointLabel: String): String {
        val own = label?.trim()?.takeIf { it.isNotEmpty() && !it.equals(pointLabel, ignoreCase = true) } ?: return pointLabel
        val cut = if (own.length > LABEL_MAX) own.take(LABEL_MAX - 1).trimEnd() + "\u2026" else own
        return "$pointLabel: $cut"
    }

    /** The `surfaces` a plugin declared for this entry (`gaming.quick_menu`, ...), empty when it declared none. */
    fun surfaces(): List<String> {
        val array = runCatching { JSONObject(extra).optJSONArray("surfaces") }.getOrNull() ?: return emptyList()
        return buildList { for (i in 0 until array.length()) add(array.optString(i)) }
    }

    companion object {
        private val KNOWN = setOf("point", "version", "id", "label")

        /** The longest entry title droidtop shows after the point's name ([titled]); longer is cut with an ellipsis. */
        const val LABEL_MAX = 40

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
            val written = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            // An Android permission name is read as the host permission of the operation that uses it (docs/plugin-api.md 4.1),
            // keeping the name it was written under; one no operation uses stays as written and is "not supported".
            val host = AndroidPermissions.hostIdFor(written)?.takeIf { written.startsWith(AndroidPermissions.PREFIX) }
            val extra = if (host == null) json.rest(KNOWN) else JSONObject(json.rest(KNOWN)).put(AndroidPermissions.DECLARED_AS, written).let { o ->
                JSONObject().also { sorted -> o.keys().asSequence().sorted().forEach { sorted.put(it, o.get(it)) } }.toString()
            }
            return DeclaredPermission(
                id = host ?: written,
                reason = json.optNullable("reason"),
                required = json.optBoolean("required", false),
                extra = extra,
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
    /** The `level` attribute (`adb`, `root`), or empty. */
    val level: String get() = runCatching { JSONObject(attributes).optString("level") }.getOrDefault("")

    /**
     * The key the provider's own "offer this to other plugins" grant is kept under (docs/plugin-api.md 2.7): the API
     * name, plus `@root` for a root-level export, so a provider offering one API at two levels has a grant for each and
     * the person can allow the system-level one without the root one. Every other export keeps its plain API name.
     */
    val grantKey: String get() = if (level == "root") "$api@root" else api

    /**
     * The plain line the provider's approval list shows for this export: what other plugins may then do, from the
     * registry label of the permission its ops are gated by ("Other plugins may: Full control of the device").
     */
    fun offerLabel(): String =
        ops.firstNotNullOfOrNull { PluginPermissions.labelFor(it.permission) }?.let { "Other plugins may: $it" }
            ?: "Offer $api to other plugins"

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
