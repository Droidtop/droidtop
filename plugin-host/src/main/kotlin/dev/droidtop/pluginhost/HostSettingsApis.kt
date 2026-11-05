package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The declared settings' host ops (Droidtop/tracker#880, docs/plugin-api.md 1.10). The plugin reads the values the
 * person set when it runs, and pushes the text only it knows for a row. Its own data, so no permission.
 */
internal object HostSettingsApis {
    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun settingsOf(env: BrokerEnvironment): PluginSettings =
        env.settings() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop has no declared settings")

    val ops: List<HostOp> = listOf(
        // The values, and the settings changed since it last asked; asking is hearing them.
        HostOp("settings", "get") { env, record, _ ->
            val store = settingsOf(env)
            val values = JSONObject()
            store.values(record).forEach { (k, v) -> values.put(k, v ?: JSONObject.NULL) }
            JSONObject().put("values", values).put("changed", JSONArray(store.pending(record.manifest.id, clear = true)))
        },
        HostOp("settings", "set_text", target = { it.optString("id") }) { env, record, args ->
            val id = args.optString("id").takeIf { DeclaredSetting.ID.matches(it) } ?: invalid("id is one of the plugin's declared settings")
            val value = if (args.has("value") && !args.isNull("value")) args.optString("value") else null
            val sub = if (args.has("sub") && !args.isNull("sub")) args.optString("sub") else null
            settingsOf(env).setText(record, id, value, sub)?.let { invalid(it) }
            JSONObject().put("stored", true)
        },
    )
}
