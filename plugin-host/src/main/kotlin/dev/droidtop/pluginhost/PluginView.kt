package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * A plugin's UI as data (docs/plugin-api.md 1.6, `docs/plugin-view.schema.json`):
 * the `data` of a `handle` reply, read here into a closed set of typed nodes that
 * droidtop renders with its own components. Pure, so the reading and its limits
 * are one tested mechanism; the rendering lives in `library-core` (`PluginViews`).
 *
 * Reading never fails on a plugin's mistake: an unknown node type, a node with no
 * id or title, an action with no op or an unknown kind, and anything over a limit
 * are dropped or cut, so a newer plugin still renders on an older droidtop and a
 * careless one cannot make a page that will not draw.
 */
data class PluginView(
    val title: String?,
    val subtitle: String?,
    val sections: List<ViewSection>,
) {
    /** Every input node's current value as the string droidtop sends back in `values`. */
    fun initialValues(): Map<String, String> = buildMap {
        sections.flatMap { it.nodes }.forEach { node ->
            when (node) {
                is ViewNode.Toggle -> put(node.id, node.value.toString())
                is ViewNode.Choice -> node.value?.let { put(node.id, it) }
                is ViewNode.Slider -> put(node.id, node.value.toString())
                is ViewNode.Text -> put(node.id, node.value)
                else -> Unit
            }
        }
    }

    companion object {
        const val VERSION = 1
        const val MAX_SECTIONS = 12
        const val MAX_NODES = 200
        const val MAX_OPTIONS = 100
        const val MAX_TITLE = 200
        const val MAX_SUBTITLE = 500
        const val MAX_ARGS_BYTES = 16 * 1024

        /** A view, or null when [json] is not one (no `sections`, or a `view` version this build does not draw). */
        fun parse(json: JSONObject?): PluginView? {
            if (json == null) return null
            val version = json.optInt("view", VERSION)
            if (version != VERSION) return null
            val sectionsJson = json.optJSONArray("sections") ?: return null
            var budget = MAX_NODES
            val sections = buildList {
                for (i in 0 until minOf(sectionsJson.length(), MAX_SECTIONS)) {
                    val s = sectionsJson.optJSONObject(i) ?: continue
                    val items = s.optJSONArray("items") ?: JSONArray()
                    val nodes = buildList {
                        for (j in 0 until items.length()) {
                            if (budget <= 0) break
                            val node = ViewNode.parse(items.optJSONObject(j)) ?: continue
                            add(node)
                            budget--
                        }
                    }
                    add(ViewSection(id = s.text("id") ?: "s$i", title = s.text("title")?.cut(MAX_TITLE), nodes = nodes))
                }
            }
            return PluginView(
                title = json.text("title")?.cut(MAX_TITLE),
                subtitle = json.text("subtitle")?.cut(MAX_SUBTITLE),
                sections = sections,
            )
        }
    }
}

data class ViewSection(val id: String, val title: String?, val nodes: List<ViewNode>)

/**
 * What a node does when used: [Kind.VIEW] opens the reply as a page, [Kind.CALL]
 * runs a quick op, [Kind.JOB] runs a tracked job. [op] goes back to the same
 * extension point that produced the view; [argsJson] is the plugin's own args,
 * compact JSON text so nodes compare by value.
 */
data class ViewAction(val kind: Kind, val op: String, val argsJson: String = "{}", val title: String? = null) {
    enum class Kind { VIEW, CALL, JOB }

    fun args(): JSONObject = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())

    companion object {
        fun parse(json: JSONObject?): ViewAction? {
            if (json == null) return null
            val kind = when (json.optString("kind")) {
                "view" -> Kind.VIEW
                "call" -> Kind.CALL
                "job" -> Kind.JOB
                else -> return null
            }
            val op = json.text("op") ?: return null
            val args = json.optJSONObject("args")?.toString() ?: "{}"
            // Args cannot be cut meaningfully, so an action over the limit is dropped and its node stays inert.
            if (args.toByteArray(Charsets.UTF_8).size > PluginView.MAX_ARGS_BYTES) return null
            return ViewAction(kind = kind, op = op, argsJson = args, title = json.text("title")?.cut(PluginView.MAX_TITLE))
        }
    }
}

/** The closed set of node types (docs/plugin-api.md 1.6). Every node has an id unique within its view and a title. */
sealed interface ViewNode {
    val id: String
    val title: String
    val subtitle: String?

    data class Info(override val id: String, override val title: String, override val subtitle: String?, val value: String?) : ViewNode

    data class Row(
        override val id: String,
        override val title: String,
        override val subtitle: String?,
        val columns: List<String>,
        val badges: List<String>,
        val value: String?,
        val action: ViewAction?,
    ) : ViewNode {
        /** The subtitle droidtop draws: the plugin's own subtitle, then its columns, joined like every other row's facts. */
        fun shownSubtitle(): String? = (listOfNotNull(subtitle) + columns).joinToString(" · ").ifBlank { null }

        /** The value column: the badges when there are any, else the plain value. */
        fun shownValue(): String? = badges.joinToString(" · ").ifBlank { null } ?: value
    }

    data class Button(
        override val id: String,
        override val title: String,
        override val subtitle: String?,
        val value: String?,
        val confirm: String?,
        val action: ViewAction,
    ) : ViewNode

    data class Toggle(override val id: String, override val title: String, override val subtitle: String?, val value: Boolean, val action: ViewAction?) : ViewNode

    data class Choice(
        override val id: String,
        override val title: String,
        override val subtitle: String?,
        val options: List<Pair<String, String>>,
        val value: String?,
        val action: ViewAction?,
    ) : ViewNode

    data class Slider(
        override val id: String,
        override val title: String,
        override val subtitle: String?,
        val min: Int,
        val max: Int,
        val value: Int,
        val action: ViewAction?,
    ) : ViewNode

    data class Text(override val id: String, override val title: String, override val subtitle: String?, val value: String, val action: ViewAction?) : ViewNode

    /** [percent] is 0..100, or -1 when the plugin does not know. */
    data class Progress(override val id: String, override val title: String, override val subtitle: String?, val percent: Int) : ViewNode

    companion object {
        fun parse(json: JSONObject?): ViewNode? {
            if (json == null) return null
            val id = json.text("id") ?: return null
            val title = json.text("title")?.cut(PluginView.MAX_TITLE) ?: return null
            val subtitle = json.text("subtitle")?.cut(PluginView.MAX_SUBTITLE)
            val action = ViewAction.parse(json.optJSONObject("action"))
            return when (json.optString("type")) {
                "info" -> Info(id, title, subtitle, json.text("value")?.cut(PluginView.MAX_TITLE))
                "row" -> Row(
                    id, title, subtitle,
                    columns = json.strings("columns").map { it.cut(PluginView.MAX_TITLE) },
                    badges = json.strings("badges").map { it.cut(PluginView.MAX_TITLE) },
                    value = json.text("value")?.cut(PluginView.MAX_TITLE),
                    action = action,
                )
                "button" -> Button(id, title, subtitle, json.text("value")?.cut(PluginView.MAX_TITLE), json.text("confirm")?.cut(PluginView.MAX_TITLE), action ?: return null)
                "toggle" -> Toggle(id, title, subtitle, parseBoolean(json.opt("value")), action)
                "choice" -> {
                    val options = json.optJSONArray("options") ?: JSONArray()
                    val parsed = buildList {
                        for (i in 0 until minOf(options.length(), PluginView.MAX_OPTIONS)) {
                            val o = options.optJSONObject(i) ?: continue
                            val value = if (o.isNull("value")) continue else o.optString("value")
                            add(value to (o.text("label")?.cut(PluginView.MAX_TITLE) ?: value))
                        }
                    }
                    if (parsed.isEmpty()) return null
                    Choice(id, title, subtitle, parsed, json.text("value") ?: parsed.first().first, action)
                }
                "slider" -> {
                    val min = json.optInt("min", 0)
                    val max = json.optInt("max", min).coerceAtLeast(min)
                    Slider(id, title, subtitle, min, max, json.optInt("value", min).coerceIn(min, max), action)
                }
                // A secret field never renders: there is no password input until the vault exists (G1).
                "text" -> if (json.optBoolean("secret", false)) null else Text(id, title, subtitle, if (json.isNull("value")) "" else json.optString("value"), action)
                "progress" -> Progress(id, title, subtitle, json.optInt("value", -1).let { if (it < 0) -1 else it.coerceAtMost(100) })
                else -> null
            }
        }

        private fun parseBoolean(value: Any?): Boolean = when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Number -> value.toInt() != 0
            else -> false
        }
    }
}

/**
 * What droidtop sends with every view action (docs/plugin-api.md 1.6): the action's
 * own args, then `values` (the page's inputs) and `context` (filled by droidtop
 * only). A plugin's args cannot override either, because they are written last.
 */
object PluginViewCall {
    fun args(actionArgs: JSONObject, values: Map<String, String>, context: JSONObject): JSONObject {
        val out = JSONObject()
        actionArgs.keys().forEach { out.put(it, actionArgs.get(it)) }
        out.put("values", JSONObject(values as Map<*, *>))
        out.put("context", context)
        return out
    }

    /** A `call` or `job` reply may carry a whole new view for the page that ran it; null when it does not. */
    fun replyView(data: JSONObject): PluginView? = PluginView.parse(data.optJSONObject("view"))

    /** The sentence a `call` reply asks droidtop to show, if any. */
    fun replyMessage(data: JSONObject): String? = data.text("message")?.cut(PluginView.MAX_SUBTITLE)
}

/**
 * One result a `library.sources` `search` returned in the contract 2 shape (docs/plugin-api.md
 * 1.6): `{id, title, subtitle?, columns?, badges?, platform?, ref?}`. [refJson] is handed back
 * unread in `detail` and `acquire`; droidtop never parses it (12a checklist point 5).
 */
data class SourceResultRow(
    val id: String,
    val title: String,
    val subtitle: String?,
    val columns: List<String>,
    val badges: List<String>,
    val platform: String?,
    val refJson: String,
)

object SourceResultProtocol {
    const val MAX_RESULTS = 200

    fun results(data: JSONObject): List<SourceResultRow> {
        val array = data.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                if (size >= MAX_RESULTS) break
                val obj = array.optJSONObject(i) ?: continue
                val title = obj.text("title")?.cut(PluginView.MAX_TITLE) ?: continue
                add(
                    SourceResultRow(
                        id = obj.text("id") ?: "r$i",
                        title = title,
                        subtitle = obj.text("subtitle")?.cut(PluginView.MAX_SUBTITLE),
                        columns = obj.strings("columns").map { it.cut(PluginView.MAX_TITLE) },
                        badges = obj.strings("badges").map { it.cut(PluginView.MAX_TITLE) },
                        platform = obj.text("platform"),
                        refJson = (obj.optJSONObject("ref") ?: JSONObject()).toString(),
                    ),
                )
            }
        }
    }
}

private fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.strings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList { for (i in 0 until array.length()) array.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { add(it) } }
}

private fun String.cut(max: Int): String = if (length <= max) this else take(max - 1) + "…"
