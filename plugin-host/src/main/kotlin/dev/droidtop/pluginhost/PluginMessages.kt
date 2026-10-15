package dev.droidtop.pluginhost

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * What droidtop had to change to draw a plugin's view (docs/SPEC.md 7k, "Notices"; docs/plugin-api.md 1.6): a cut,
 * a dropped or moved notice, an unknown tone, a reserved title, a permission it never declared. Said once per plugin,
 * view and message: in logcat under [TAG] (a developer reads it over adb), and kept, the last [KEEP] per plugin, in
 * the app's files for the plugin's Info and the Plugin messages page in developer options. Callers are off the main
 * thread (the view is read on IO).
 */
object PluginMessages {
    const val TAG = "PluginMessages"
    const val KEEP = 50

    class Entry(val atMs: Long, val view: String, val text: String)

    private val said = ConcurrentHashMap.newKeySet<String>()

    fun record(context: Context, pluginId: String, view: String, messages: List<String>) {
        if (messages.isEmpty()) return
        val fresh = messages.filter { said.add("$pluginId\u0000$view\u0000$it") }
        if (fresh.isEmpty()) return
        fresh.forEach { Log.i(TAG, "$pluginId [$view]: $it") }
        synchronized(this) {
            val now = System.currentTimeMillis()
            val kept = (read(context, pluginId) + fresh.map { Entry(now, view, it) }).takeLast(KEEP)
            val array = JSONArray()
            kept.forEach { array.put(JSONObject().put("at", it.atMs).put("view", it.view).put("text", it.text)) }
            val file = fileFor(context, pluginId)
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }
    }

    /** The kept messages of [pluginId], oldest first. */
    fun read(context: Context, pluginId: String): List<Entry> {
        val file = fileFor(context, pluginId)
        if (!file.isFile) return emptyList()
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Entry(it.optLong("at"), it.optString("view"), it.optString("text")) }
        }
    }

    /** Every plugin with kept messages. */
    fun plugins(context: Context): List<String> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty().map { it.name.removeSuffix(".json") }.sorted()

    /** All of [pluginId]'s kept messages as plain text, for Copy all. */
    fun asText(context: Context, pluginId: String): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
        return read(context, pluginId).joinToString("\n") { "${format.format(java.util.Date(it.atMs))}  ${it.view}  ${it.text}" }
    }

    private fun dir(context: Context) = File(context.filesDir, "plugin-messages")

    private fun fileFor(context: Context, pluginId: String) = File(dir(context), "$pluginId.json")
}
