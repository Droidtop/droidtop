package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * Facets drawn by the one plugin renderer (docs/plugin-api.md 1.10, "One mechanism"): admitted facets become the
 * view model droidtop already draws everywhere ([PluginView]), so a facet looks, focuses and reads aloud exactly as a
 * view's node does, on every surface that draws plugin pages. Pure.
 *
 * A group is a section; everything outside a group is one section first. A `page` call opens as a page whose reply is
 * facets ([PluginView.parse] reads those too). The kinds a node cannot show (media, a collection, an account, which
 * droidtop draws itself, and the item bundles, which have pages of their own) are left out here.
 */
object FacetViews {
    /**
     * The host's own mark in a facet call's args: which facet it came from and how its value is typed. Removed by
     * [PluginViewCall.args], which puts the facet's committed value in `args.value` (docs/plugin-api.md 1.10,
     * "Interaction") rather than in the view's `values`.
     */
    const val MARK = "@facet"

    /**
     * The host's mark on a form's submit: `{fields: {facetId: type}, required: {facetId: label}}`. Removed by
     * [PluginViewCall.args], which sends those fields' values, typed, as `args.values` (docs/plugin-api.md 1.10, `group`).
     */
    const val FORM = "@form"

    fun toView(facets: List<Facet>, title: String? = null): PluginView {
        val loose = facets.filter { it.body !is FacetBody.Group }
        val sections = buildList {
            if (loose.isNotEmpty()) add(section("facets", null, loose))
            for (facet in facets) {
                val group = facet.body as? FacetBody.Group ?: continue
                add(section(facet.id, group.title, group.facets, group.submit?.let { submit(facet.id, it, group.facets) }))
            }
        }
        return PluginView(title = title, subtitle = null, sections = sections)
    }

    /** A reply's `facets` as a view, or null when it carries none. */
    fun fromReply(json: JSONObject, messages: MutableList<String>? = null): PluginView? {
        if (json.optJSONArray("facets") == null) return null
        val reading = Facets.read(json)
        messages?.addAll(reading.messages)
        return toView(reading.facets, json.optString("title").takeIf { it.isNotBlank() })
    }

    private fun section(id: String, title: String?, facets: List<Facet>, submit: ViewNode? = null): ViewSection = ViewSection(
        id = id,
        title = title,
        nodes = facets.flatMap { f -> if (f.body is FacetBody.ListBody) rows(f) else listOfNotNull(node(f)) } + listOfNotNull(submit),
        needs = facets.mapNotNull { f -> f.needs?.let { f.id to it } }.toMap(),
    )

    /** One facet as a node; null for the kinds a node does not show. */
    fun node(facet: Facet): ViewNode? {
        val id = facet.id
        return when (val b = facet.body) {
            is FacetBody.Info -> ViewNode.Info(id, b.label, b.sub, b.value)
            // A status keeps its state for the host's glyph (Droidtop/tracker#644), and its words for the value column.
            is FacetBody.Status -> ViewNode.Info(id, b.label, b.value, stateWords(b.state), state = b.state)
            is FacetBody.Badge -> ViewNode.Info(id, b.text, null, null)
            is FacetBody.Progress -> ViewNode.Progress(id, b.label, b.sub, b.value)
            // A meter is the host's bar, filled to how much of its maximum it holds, with its figures under it.
            is FacetBody.Meter -> ViewNode.Progress(id, b.label, meterWords(b), meterPercent(b))
            is FacetBody.Chart -> ViewNode.Info(id, b.label, null, chartWords(b))
            is FacetBody.Action -> ViewNode.Button(id, b.label, b.sub, null, if (b.confirm == "none") null else b.label, action(b.call, id, null))
            // Inside a form an input has no call: droidtop only holds its value until the form's submit.
            is FacetBody.Toggle -> ViewNode.Toggle(id, b.label, b.sub, b.value, b.call?.let { action(it, id, "bool") })
            is FacetBody.Choice -> ViewNode.Choice(id, b.label, b.sub, b.options, b.value, b.call?.let { action(it, id, "string") })
            is FacetBody.Slider -> ViewNode.Slider(id, b.label, b.unit, b.min.toInt(), b.max.toInt(), b.value.toInt(), b.call?.let { action(it, id, "number") })
            is FacetBody.Input -> ViewNode.Text(id, b.label, b.hint, b.value, b.call?.let { action(it, id, "string") })
            // Show is droidtop's button; the value goes to droidtop's sheet, never into the page.
            is FacetBody.Secret -> ViewNode.Button(id, b.label, b.sub, "Show", null, action(b.call, id, null).copy(kind = ViewAction.Kind.SECRET))
            is FacetBody.Text -> ViewNode.Info(id, b.body.lineSequence().first().take(PluginView.MAX_TITLE), b.body, null)
            is FacetBody.Notice -> ViewNode.Notice(
                id, b.title, b.sub, b.details, if (b.tone in PluginViewLimits.tones) b.tone else "info", b.placement == "banner",
                b.call?.let { action(it, id, null) },
            )
            else -> null
        }
    }

    /** A form's submit: the group's last button, marked with the form's fields so its values go back with it. */
    fun submit(groupId: String, submit: FacetBody.Action, facets: List<Facet>): ViewNode {
        val fields = JSONObject()
        val required = JSONObject()
        for (f in facets) {
            val (type, label) = when (val b = f.body) {
                is FacetBody.Toggle -> "bool" to null
                is FacetBody.Choice -> "string" to null
                is FacetBody.Slider -> "number" to null
                is FacetBody.Input -> (if (b.inputKind == "number") "number" else "string") to b.label.takeUnless { b.optional }
                else -> continue
            }
            fields.put(f.id, type)
            label?.let { required.put(f.id, it) }
        }
        val action = action(submit.call, groupId, null)
        val args = JSONObject(action.argsJson).put(FORM, JSONObject().put("fields", fields).put("required", required))
        return ViewNode.Button("$groupId.submit", submit.label, submit.sub, null, if (submit.confirm == "none") null else submit.label, action.copy(argsJson = args.toString()))
    }

    /** A list's entries as rows, for a target that draws a list (search results, a watch list). */
    fun rows(facet: Facet): List<ViewNode> {
        val list = facet.body as? FacetBody.ListBody ?: return emptyList()
        return list.entries.map { e -> ViewNode.Row("${facet.id}.${e.id}", e.title, e.sub, emptyList(), e.badges, e.value, e.call?.let { action(it, facet.id, null) }) }
    }

    /** A facet call as a view action; for an input, marked so its value goes back as `args.value`. */
    fun action(call: FacetCall, facetId: String, valueType: String?): ViewAction {
        val args = call.args()
        if (valueType != null) args.put(MARK, JSONObject().put("id", facetId).put("type", valueType))
        val kind = when (call.kind) {
            FacetCall.Kind.CALL -> ViewAction.Kind.CALL
            FacetCall.Kind.JOB -> ViewAction.Kind.JOB
            FacetCall.Kind.PAGE -> ViewAction.Kind.VIEW
            FacetCall.Kind.GRANT -> ViewAction.Kind.GRANT
        }
        return ViewAction(kind, call.op, args.toString())
    }

    /** A status's state in droidtop's words. */
    fun stateWords(state: String): String = when (state) {
        "on" -> "On"
        "off" -> "Off"
        "busy" -> "Working"
        "ok" -> "OK"
        "warning" -> "Needs a look"
        "error" -> "Error"
        else -> "Unknown"
    }

    /** How full a meter is, 0 to 100; -1 when its maximum says nothing. Pure. */
    fun meterPercent(m: FacetBody.Meter): Int =
        if (m.max > 0) ((m.value / m.max) * 100).toInt().coerceIn(0, 100) else -1

    fun meterWords(m: FacetBody.Meter): String {
        val unit = m.unit?.let { " $it" }.orEmpty()
        return "${number(m.value)} of ${number(m.max)}$unit"
    }

    /** A chart as words where it cannot be drawn: the latest point, or the bars' values. */
    fun chartWords(c: FacetBody.Chart): String? {
        val unit = c.unit?.let { " $it" }.orEmpty()
        return if (c.style == "bars") c.bars.joinToString(" · ") { (label, v) -> "$label ${number(v)}$unit" }.ifBlank { null }
        else c.points.lastOrNull()?.let { "${number(it)}$unit" }
    }

    private fun number(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else "%.1f".format(v)
}
