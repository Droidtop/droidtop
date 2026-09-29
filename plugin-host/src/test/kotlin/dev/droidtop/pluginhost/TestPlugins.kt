package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/** Builders for the plugin tests: a manifest from a few JSON fields, and the record around it. */
object TestPlugins {
    fun obj(vararg kv: Pair<String, Any>): JSONObject = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    fun arr(vararg o: JSONObject): JSONArray = JSONArray(o.toList())

    fun strings(vararg s: String): JSONArray = JSONArray(s.toList())

    fun manifest(id: String = "acme.tool", origin: String = "acme", contract: Int = 2, label: String = id, build: (JSONObject) -> Unit = {}): PluginManifest {
        val json = JSONObject().apply {
            put("id", id); put("origin", origin); put("label", label); put("kind", "native_bundle")
            put("capabilities", JSONArray(if (contract == 1) listOf("status_tile") else emptyList<String>()))
            put("contractVersion", contract)
            put("abis", JSONArray(listOf("arm64-v8a", "x86_64"))); put("entryClass", "acme.Tool")
            put("payload", JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", "a".repeat(64)))))
            if (contract >= 2) put("provides", arr(obj("point" to "ui.status_tile")))
            build(this)
        }
        return PluginManifest.fromJson(json)!!
    }

    fun record(
        m: PluginManifest,
        trust: PluginTrustState = PluginTrustState.APPROVED,
        enabled: Boolean = true,
        rootApproved: Boolean = false,
        disabledReason: String? = null,
    ) = PluginRecord(m, "b".repeat(64), "", trust, enabled = enabled, rootApproved = rootApproved, disabledReason = disabledReason)

    /** A provider of [api] ops, each on [permission]. */
    fun provider(
        id: String,
        api: String = "priv.shell",
        version: String = "1.0",
        level: String? = "adb",
        permission: String = "priv.shell.adb",
        ops: List<Pair<String, Boolean>> = listOf("exec" to false),
        origin: String = "acme",
    ): PluginRecord = record(
        manifest(id = id, origin = origin) {
            it.put(
                "exports",
                arr(
                    obj(
                        "api" to api, "version" to version,
                        "attributes" to (if (level != null) obj("level" to level) else obj()),
                        "ops" to JSONArray(ops.map { (op, job) -> obj("op" to op, "permission" to permission, "job" to job) }),
                    ),
                ),
            )
        },
    )

    /** A plugin that optionally requires [api] and declares [permission]. */
    fun caller(
        id: String = "acme.caller",
        api: String = "priv.shell",
        version: String = "1.0",
        minLevel: String? = null,
        permission: String = "priv.shell.adb",
        optional: Boolean = true,
        extraPermissions: List<JSONObject> = emptyList(),
    ): PluginRecord = record(
        manifest(id = id) {
            it.put("requires", arr(obj("api" to api, "version" to version, "optional" to optional).also { r -> if (minLevel != null) r.put("minLevel", minLevel) }))
            it.put("permissions", JSONArray(listOf(obj("id" to permission)) + extraPermissions))
        },
    )
}
