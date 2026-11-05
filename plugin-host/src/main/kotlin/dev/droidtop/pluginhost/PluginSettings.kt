package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * A plugin's declared settings, droidtop's side (Droidtop/tracker#880, docs/plugin-api.md 1.10): the values the person
 * set, the text the plugin pushed while it ran, and which settings changed since the plugin last heard. Kept in
 * `plugins/settings/<id>.json`, outside the plugin's own folder. Drawing the page reads only this and the manifest,
 * never the plugin.
 */
class PluginSettings(private val dir: File) {
    constructor(context: Context) : this(File(PluginStore.root(context.applicationContext), "settings"))

    private val store = PerPluginJsonStore(dir)

    /** Text a plugin pushed for a row ([setText]); either part may be absent. */
    data class Text(val value: String?, val sub: String?)

    @Synchronized
    private fun read(pluginId: String): JSONObject = store.readOrSetAside(pluginId) ?: JSONObject()

    @Synchronized
    private fun write(pluginId: String, json: JSONObject) {
        dir.mkdirs()
        store.write(pluginId, json)
    }

    /** Every input's value: the person's where set, else the declared default. */
    @Synchronized
    fun values(record: PluginRecord): Map<String, Any?> {
        val stored = read(record.manifest.id).optJSONObject("values") ?: JSONObject()
        return record.manifest.v2.settings.filter { it.kind == DeclaredSetting.Kind.INPUT }.associate { s ->
            s.id to (if (stored.has(s.id)) stored.get(s.id) else s.default)
        }
    }

    /**
     * Sets input [id] to [value], checked against its declaration, and marks it changed for the plugin's next run.
     * Returns why it was refused, or null.
     */
    @Synchronized
    fun set(record: PluginRecord, id: String, value: Any): String? {
        val decl = record.manifest.v2.settings.firstOrNull { it.id == id && it.kind == DeclaredSetting.Kind.INPUT }
            ?: return "no such setting"
        val checked: Any = when (decl.input) {
            "toggle" -> value as? Boolean ?: return "a toggle is on or off"
            "choice" -> (value as? String)?.takeIf { v -> decl.choices.any { it.first == v } } ?: return "not one of its choices"
            else -> (value as? String)?.takeIf { it.length <= DeclaredSetting.MAX_TEXT } ?: return "at most ${DeclaredSetting.MAX_TEXT} characters"
        }
        val json = read(record.manifest.id)
        val values = json.optJSONObject("values") ?: JSONObject()
        values.put(id, checked)
        val pending = json.optJSONArray("pending")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().toMutableSet()
        pending += id
        write(record.manifest.id, json.put("values", values).put("pending", JSONArray(pending.sorted())))
        return null
    }

    /** The settings changed since the plugin last heard; [clear] forgets them, once they were told. */
    @Synchronized
    fun pending(pluginId: String, clear: Boolean = false): List<String> {
        val json = read(pluginId)
        val list = json.optJSONArray("pending")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        if (clear && list.isNotEmpty()) write(pluginId, json.put("pending", JSONArray()))
        return list
    }

    /** What the plugin last pushed for row [id] (`settings.set_text`), or null. */
    @Synchronized
    fun text(pluginId: String, id: String): Text? {
        val o = read(pluginId).optJSONObject("texts")?.optJSONObject(id) ?: return null
        return Text(o.optNullable("value"), o.optNullable("sub"))
    }

    /** Keeps the text the plugin pushed for its declared row [id], replacing the last; null parts are cleared. */
    @Synchronized
    fun setText(record: PluginRecord, id: String, value: String?, sub: String?): String? {
        if (record.manifest.v2.settings.none { it.id == id }) return "no such setting"
        val json = read(record.manifest.id)
        val texts = json.optJSONObject("texts") ?: JSONObject()
        texts.put(id, JSONObject().apply { value?.let { put("value", it.take(MAX_PUSHED)) }; sub?.let { put("sub", it.take(MAX_PUSHED)) } })
        write(record.manifest.id, json.put("texts", texts))
        return null
    }

    /** Removes [pluginId]'s settings (uninstall). */
    @Synchronized
    fun forget(pluginId: String) = store.delete(pluginId)

    companion object {
        /** A pushed text's longest part: one row's value or line. */
        const val MAX_PUSHED = 120
    }
}
