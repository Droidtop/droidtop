package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * The plugin view limits, read from `docs/view-limits/plugin-view-limits.json` (packaged as a resource of this
 * module): the one source the host, plugin-common-py's `gg.lint_view` and the Kotlin `PluginViewLint` all read, so
 * the numbers cannot drift apart (docs/plugin-api.md 1.6, docs/SPEC.md 7k "Notices").
 */
object PluginViewLimits {
    const val RESOURCE = "plugin-view-limits.json"

    private val json: JSONObject by lazy {
        val text = PluginViewLimits::class.java.classLoader?.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("$RESOURCE is not packaged with :plugin-host")
        JSONObject(text)
    }

    fun length(name: String): Int = json.getJSONObject("lengths").getInt(name)

    fun count(name: String): Int = json.getJSONObject("counts").getInt(name)

    fun copyWarning(name: String): Int = json.getJSONObject("copyWarnings").getInt(name)

    val tones: Set<String> by lazy { strings("tones").toSet() }

    val placements: Set<String> by lazy { strings("placements").toSet() }

    /** Host notice titles a plugin notice may not reuse (exact, ignoring case). */
    val reservedTitles: Set<String> by lazy { strings("reservedTitles").map { it.lowercase() }.toSet() }

    private fun strings(key: String): List<String> {
        val array = json.getJSONArray(key)
        return (0 until array.length()).map { array.getString(it) }
    }
}
