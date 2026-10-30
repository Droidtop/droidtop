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
    /**
     * The plugin's one sentence for a view with nothing in it ("Saves you make appear here"), shown in droidtop's own
     * "Nothing here yet" notice (Droidtop/tracker#521 item 6); null for droidtop's words alone.
     */
    val empty: String? = null,
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

        // The limits are the limits file's (docs/view-limits/plugin-view-limits.json), never restated here.
        val MAX_SECTIONS: Int get() = PluginViewLimits.count("sections")
        val MAX_NODES: Int get() = PluginViewLimits.count("nodes")
        val MAX_OPTIONS: Int get() = PluginViewLimits.count("options")
        val MAX_TITLE: Int get() = PluginViewLimits.length("title")
        val MAX_SUBTITLE: Int get() = PluginViewLimits.length("subtitle")
        val MAX_ARGS_BYTES: Int get() = PluginViewLimits.length("argsBytes")

        /**
         * A view, or null when [json] is not one (no `sections`, or a `view` version this build does not draw).
         * What the host had to change to draw it (a cut, a dropped or moved notice, an unknown tone, a reserved
         * title) is added to [messages] in plain words, for the plugin's message log (docs/SPEC.md 7k, "Notices").
         */
        fun parse(json: JSONObject?, messages: MutableList<String>? = null): PluginView? {
            if (json == null) return null
            // Contract 3: a reply of facets (docs/plugin-api.md 1.10) is drawn by the same renderer.
            if (!json.has("sections")) FacetViews.fromReply(json, messages)?.let { return it }
            val version = json.optInt("view", VERSION)
            if (version != VERSION) return null
            val sectionsJson = json.optJSONArray("sections") ?: return null
            var budget = MAX_NODES
            var noticesLeft = PluginViewLimits.count("noticesPerView")
            var bannersLeft = PluginViewLimits.count("bannersPerPage")
            val sections = buildList {
                for (i in 0 until minOf(sectionsJson.length(), MAX_SECTIONS)) {
                    val s = sectionsJson.optJSONObject(i) ?: continue
                    val items = s.optJSONArray("items") ?: JSONArray()
                    val needs = HashMap<String, String>()
                    val parsed = buildList {
                        for (j in 0 until items.length()) {
                            if (budget <= 0) break
                            val raw = items.optJSONObject(j)
                            val node = ViewNode.parse(raw, messages) ?: continue
                            raw?.text("needs")?.let { needs[node.id] = it }
                            add(node)
                            budget--
                        }
                    }
                    // Notices go first in their section, at most two there and only of different tones, three in a view,
                    // and one banner a page; the rest is dropped or made a section notice, and said in the message log.
                    val notices = mutableListOf<ViewNode.Notice>()
                    parsed.forEachIndexed { index, node ->
                        if (node !is ViewNode.Notice) return@forEachIndexed
                        if (index != notices.size) messages?.add("notice \"${node.id}\" was moved to the top of its section")
                        when {
                            notices.size >= PluginViewLimits.count("noticesPerSection") ->
                                messages?.add("notice \"${node.id}\" was dropped: at most ${PluginViewLimits.count("noticesPerSection")} notices in a section")
                            notices.any { it.tone == node.tone } ->
                                messages?.add("notice \"${node.id}\" was dropped: a section's two notices must differ in tone")
                            noticesLeft <= 0 ->
                                messages?.add("notice \"${node.id}\" was dropped: at most ${PluginViewLimits.count("noticesPerView")} notices in a view")
                            node.banner && bannersLeft <= 0 -> {
                                messages?.add("notice \"${node.id}\" was drawn in its section: one banner a page")
                                notices += node.copy(banner = false)
                                noticesLeft--
                            }
                            else -> {
                                if (node.banner) bannersLeft--
                                notices += node
                                noticesLeft--
                            }
                        }
                    }
                    val nodes = notices + parsed.filterNot { it is ViewNode.Notice }
                    add(ViewSection(id = s.text("id") ?: "s$i", title = s.text("title")?.cut(MAX_TITLE), nodes = nodes, needs = needs))
                }
            }
            return PluginView(
                title = json.text("title")?.cut(MAX_TITLE),
                subtitle = json.text("subtitle")?.cut(MAX_SUBTITLE),
                sections = sections,
                empty = json.text("empty")?.cut(PluginViewLimits.length("noticeSubtitle")),
            )
        }
    }
}

/**
 * One section of a view. [needs] maps a node's id to the permission it declared with `needs` (docs/plugin-api.md 1.6,
 * Droidtop/tracker#521 item 5): while the person has that permission turned off, droidtop draws the node as an inert
 * row saying so instead of the node, and the rest of the page is untouched.
 */
data class ViewSection(val id: String, val title: String?, val nodes: List<ViewNode>, val needs: Map<String, String> = emptyMap())

/**
 * What a node does when used: [Kind.VIEW] opens the reply as a page, [Kind.CALL]
 * runs a quick op, [Kind.JOB] runs a tracked job. [op] goes back to the same
 * extension point that produced the view; [argsJson] is the plugin's own args,
 * compact JSON text so nodes compare by value.
 */
data class ViewAction(val kind: Kind, val op: String, val argsJson: String = "{}", val title: String? = null) {
    /**
     * [GRANT] comes only from a facet's grant action: [op] is the permission droidtop asks the person for. [SECRET] only
     * from a `secret` facet: a call whose reply's `value` droidtop shows on its own sheet ([PluginSecrets]).
     */
    enum class Kind { VIEW, CALL, JOB, GRANT, SECRET }

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

    /**
     * [state] is a status facet's state (`busy`, `warning`, `error`, ...), drawn as the host's status glyph beside the
     * title (Droidtop/tracker#644); null for a plain fact.
     */
    data class Info(override val id: String, override val title: String, override val subtitle: String?, val value: String?, val state: String? = null) : ViewNode

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

    /**
     * A plugin's notice (docs/SPEC.md 7k, "Notices"; docs/plugin-api.md 1.6): [tone] is `info`, `caution` or `danger`
     * (danger since 2026-10-15, Droidtop/tracker#521 item 1), [banner] puts it under the page header, [details] is what Info shows, and [action] has the shape
     * of a row's. Drawn by droidtop as its own notice card with "from <plugin>".
     */
    data class Notice(
        override val id: String,
        override val title: String,
        override val subtitle: String?,
        val details: String?,
        val tone: String,
        val banner: Boolean,
        val action: ViewAction?,
        /** Set by the host only (an error reply's declared permission): the notice opens the plugin's Permissions. */
        val opensPermissions: Boolean = false,
        /**
         * Set by the host only (a SIGN_IN_REQUIRED or BROWSER_CHECK_REQUIRED reply): droidtop's own Sign in or Open the
         * site in the plugin's session ([PluginWebAccount]), when the plugin declares a `web.session` site.
         */
        val opensAccount: PluginWebAccount.Need? = null,
    ) : ViewNode

    companion object {
        fun parse(json: JSONObject?, messages: MutableList<String>? = null): ViewNode? {
            if (json == null) return null
            val id = json.text("id") ?: return null
            if (json.optString("type") == "notice") return parseNotice(id, json, messages)
            val title = json.text("title")?.cut(PluginView.MAX_TITLE) ?: return null
            val subtitle = json.text("subtitle")?.cut(PluginView.MAX_SUBTITLE)
            val action = ViewAction.parse(json.optJSONObject("action"))
            return when (json.optString("type")) {
                "info" -> Info(id, title, subtitle, ViewValue.format(json.opt("value"))?.cut(PluginView.MAX_TITLE))
                "row" -> Row(
                    id, title, subtitle,
                    columns = json.strings("columns").map { it.cut(PluginView.MAX_TITLE) },
                    badges = json.strings("badges").map { it.cut(PluginView.MAX_TITLE) },
                    value = ViewValue.format(json.opt("value"))?.cut(PluginView.MAX_TITLE),
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

        private fun parseNotice(id: String, json: JSONObject, messages: MutableList<String>?): ViewNode? {
            fun cutLogged(key: String, limit: Int): String? {
                val text = json.text(key) ?: return null
                if (text.length > limit) messages?.add("notice \"$id\": $key was cut at $limit characters")
                return text.cut(limit)
            }
            val title = cutLogged("title", PluginViewLimits.length("noticeTitle")) ?: return null
            val subtitle = cutLogged("subtitle", PluginViewLimits.length("noticeSubtitle"))
            val details = cutLogged("details", PluginViewLimits.length("noticeDetails"))
            if (title.lowercase() in PluginViewLimits.reservedTitles) {
                messages?.add("notice \"$id\" uses droidtop's own title \"$title\" and was drawn as a plain row")
                return Info(id, title, subtitle, null)
            }
            val rawTone = json.optString("tone", "info")
            val tone = if (rawTone in PluginViewLimits.tones) {
                rawTone
            } else {
                messages?.add("notice \"$id\": tone \"$rawTone\" is not info, caution or danger and was drawn as info")
                "info"
            }
            val placement = json.optString("placement", "section")
            if (placement !in PluginViewLimits.placements) messages?.add("notice \"$id\": placement \"$placement\" is not section or banner and was drawn in its section")
            val action = ViewAction.parse(json.optJSONObject("action"))?.let { a ->
                val label = a.title ?: return@let a
                val limit = PluginViewLimits.length("actionLabel")
                if (label.length > limit) messages?.add("notice \"$id\": its action's label was cut at $limit characters")
                a.copy(title = label.cut(limit))
            }
            return Notice(id, title, subtitle, details, tone, placement == "banner", action)
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
        // A facet's input sends its own committed value as `value`, typed (docs/plugin-api.md 1.10, "Interaction").
        (out.remove(FacetViews.MARK) as? JSONObject)?.let { mark ->
            values[mark.optString("id")]?.let { out.put("value", typed(it, mark.optString("type"))) }
        }
        // A form's submit sends its own fields, typed, by facet id (docs/plugin-api.md 1.10, `group`); a view's action, every input.
        val form = (out.remove(FacetViews.FORM) as? JSONObject)?.optJSONObject("fields")
        out.put(
            "values",
            if (form == null) JSONObject(values as Map<*, *>)
            else JSONObject().also { o -> form.keys().forEach { id -> values[id]?.let { o.put(id, typed(it, form.optString(id))) } } },
        )
        out.put("context", context)
        return out
    }

    /** The labels of a form's fields that must have a value and are empty; empty for any other action. */
    fun missing(actionArgs: JSONObject, values: Map<String, String>): List<String> {
        val required = actionArgs.optJSONObject(FacetViews.FORM)?.optJSONObject("required") ?: return emptyList()
        return required.keys().asSequence().filter { values[it].isNullOrBlank() }.map { required.optString(it) }.toList()
    }

    private fun typed(raw: String, type: String): Any = when (type) {
        "bool" -> raw == "true"
        "number" -> raw.toDoubleOrNull() ?: raw
        else -> raw
    }

    /** A `call` or `job` reply may carry a whole new view for the page that ran it; null when it does not. */
    fun replyView(data: JSONObject, messages: MutableList<String>? = null): PluginView? = PluginView.parse(data.optJSONObject("view"), messages)

    /** A reply's `data.notice` (docs/plugin-api.md 1.6): drawn instead of `data.message` by a host that knows notices. */
    fun replyNotice(data: JSONObject, messages: MutableList<String>? = null): ViewNode.Notice? =
        ViewNode.parse(data.optJSONObject("notice")?.let { JSONObject(it.toString()).put("type", "notice").apply { if (!has("id")) put("id", "reply") } }, messages) as? ViewNode.Notice

    /**
     * An error reply as the caution notice droidtop draws for it (docs/plugin-api.md 1.3): the error's own title and
     * subtitle in plain words when it gave them, its code and message in Info. PERMISSION_DENIED naming a permission
     * the plugin [declared] opens its Permissions; naming one it did not is logged, not trusted. SIGN_IN_REQUIRED and
     * BROWSER_CHECK_REQUIRED become droidtop's own Sign in / Open the site ([ViewNode.Notice.opensAccount]); an older
     * SIGN_IN_REQUIRED's own action is kept for a plugin that declares no `web.session` site. Info shows the error's
     * `detail` when it gave one, else its message.
     *
     * With no subtitle of its own, the sentence under the title is [SourceFailures]'s, never the raw message: a page
     * that failed with "f95zone answered HTTP 403" said exactly that (the owner, 2026-10-16: "When I clicked, it just
     * gave an f95zone error"; docs/SPEC.md 12a). A failure that reads as a sign-in wall also offers droidtop's Sign in,
     * which is drawn only for a plugin with a `web.session` site.
     */
    fun errorNotice(reply: PluginReply, declared: Set<String>, fallbackTitle: String, messages: MutableList<String>? = null): ViewNode.Notice {
        val title = (reply.title ?: fallbackTitle).cut(PluginViewLimits.length("noticeTitle"))
        // droidtop's words for a shared code, the plugin's only for FAILED and only when plain (Droidtop/tracker#784).
        val sentence = SourceFailures.forCode(reply.code, reply.message)
        val body = (reply.subtitle ?: sentence?.takeIf { reply.title == null })?.cut(PluginViewLimits.length("noticeSubtitle"))
        var opensPermissions = false
        var action: ViewAction? = null
        var opensAccount: PluginWebAccount.Need? = null
        when (reply.code) {
            PluginErrorCode.PERMISSION_DENIED -> reply.permission?.let { permission ->
                if (permission in declared) {
                    opensPermissions = true
                } else {
                    messages?.add("an error named the permission \"$permission\", which this plugin does not declare")
                }
            }
            PluginErrorCode.SIGN_IN_REQUIRED -> {
                opensAccount = PluginWebAccount.Need.SIGN_IN
                action = ViewAction.parse(reply.action)?.copy(title = "Sign in")
            }
            PluginErrorCode.BROWSER_CHECK_REQUIRED -> opensAccount = PluginWebAccount.Need.BROWSER_CHECK
            else -> if (sentence == SourceFailures.SIGN_IN) opensAccount = PluginWebAccount.Need.SIGN_IN
        }
        val said = (reply.detail ?: reply.message)?.takeIf { it.isNotBlank() }
        val details = listOfNotNull(reply.code?.name, said).joinToString(": ")
        return ViewNode.Notice(
            "error", title, body, details.cut(PluginViewLimits.length("noticeDetails")), "caution", banner = false, action = action,
            opensPermissions = opensPermissions, opensAccount = opensAccount,
        )
    }

    /**
     * The features this host's view renderer understands, sent as `host.viewFeatures` in every call envelope
     * (docs/plugin-api.md 1.6), so a plugin in any language can fall back for an older droidtop.
     */
    val VIEW_FEATURES: List<String> = listOf("notice", "confirm")

    /** The sentence a `call` reply asks droidtop to show, if any. */
    fun replyMessage(data: JSONObject): String? = data.text("message")?.cut(PluginView.MAX_SUBTITLE)
}

/**
 * One result a `library.sources` `search` returned in the contract 2 shape (docs/plugin-api.md
 * 1.6): `{id, title, subtitle?, columns?, badges?, platform?, ref?, version?, size?, get?}`. [refJson] is handed back
 * unread in `detail` and `acquire`; droidtop never parses it (12a checklist point 5). [version], [sizeBytes] and [get]
 * are the one search's ("Fetchers answer in the one search", Droidtop/tracker#218).
 */
data class SourceResultRow(
    val id: String,
    val title: String,
    val subtitle: String?,
    val columns: List<String>,
    val badges: List<String>,
    val platform: String?,
    val refJson: String,
    val version: String? = null,
    val sizeBytes: Long? = null,
    val get: SourceGet? = null,
    /** The result's own facets (contract 3, docs/plugin-api.md 1.8 "featured"): its pictures, facts and badges. */
    val facets: List<Facet> = emptyList(),
)

/**
 * A result that can be fetched without its detail page: `acquire` is started with [valuesJson] as its `values`,
 * titled [title] when the source gave one. Null on a result that needs its detail page first.
 */
data class SourceGet(val valuesJson: String, val title: String?)

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
                        version = obj.text("version")?.cut(PluginView.MAX_TITLE),
                        sizeBytes = (obj.opt("size") as? Number)?.toLong()?.takeIf { it > 0 },
                        get = get(obj.opt("get")),
                        facets = if (obj.has("facets")) Facets.read(obj).facets else emptyList(),
                    ),
                )
            }
        }
    }

    /** The roles a result's picture is taken from, best first: box art, never a screenshot, a video, a logo or an icon. */
    private val ART_ROLES = listOf("cover", "wide", "banner", "hero")

    /** The address of a result's box art among its `media` and `image` facets, or null when it has none. Pure. */
    fun boxArt(facets: List<Facet>): String? {
        val pictures = facets.mapNotNull { facet ->
            when (val body = facet.body) {
                is FacetBody.Media -> body
                is FacetBody.Image -> body.media
                else -> null
            }
        }
        return ART_ROLES.firstNotNullOfOrNull { role -> pictures.firstOrNull { it.role == role }?.url }
    }

    /**
     * `get`: an object `{values?, title?}` whose `values` are strings (a number or a boolean is written as text; any
     * other value is left out). `get: true` is read as `{}`. Over the action args limit, or of any other shape, there
     * is no Get from the row and the result opens its detail page, as it does without one.
     */
    internal fun get(raw: Any?): SourceGet? {
        val json = when (raw) {
            true -> JSONObject()
            is JSONObject -> raw
            else -> return null
        }
        val values = JSONObject()
        json.optJSONObject("values")?.let { given ->
            given.keys().forEach { key ->
                when (val value = given.opt(key)) {
                    is String -> values.put(key, value)
                    is Number, is Boolean -> values.put(key, value.toString())
                    else -> Unit
                }
            }
        }
        val text = values.toString()
        if (text.toByteArray(Charsets.UTF_8).size > PluginView.MAX_ARGS_BYTES) return null
        return SourceGet(text, json.text("title")?.cut(PluginView.MAX_TITLE))
    }
}

private fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.strings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList { for (i in 0 until array.length()) array.optString(i).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { add(it) } }
}

private fun String.cut(max: Int): String = if (length <= max) this else take(max - 1) + "…"
